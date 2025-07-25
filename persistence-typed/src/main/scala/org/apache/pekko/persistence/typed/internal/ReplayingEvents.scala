/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2016-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.persistence.typed.internal

import scala.concurrent.duration._
import scala.util.control.NonFatal
import org.apache.pekko
import pekko.actor.typed.{ Behavior, Signal }
import pekko.actor.typed.internal.PoisonPill
import pekko.actor.typed.internal.UnstashException
import pekko.actor.typed.scaladsl.{ AbstractBehavior, ActorContext, Behaviors, LoggerOps }
import pekko.annotation.{ InternalApi, InternalStableApi }
import pekko.event.Logging
import pekko.persistence._
import pekko.persistence.JournalProtocol._
import pekko.persistence.typed.EmptyEventSeq
import pekko.persistence.typed.EventsSeq
import pekko.persistence.typed.RecoveryCompleted
import pekko.persistence.typed.RecoveryFailed
import pekko.persistence.typed.ReplicaId
import pekko.persistence.typed.SingleEventSeq
import pekko.persistence.typed.internal.BehaviorSetup.SnapshotWithoutRetention
import pekko.persistence.typed.internal.EventSourcedBehaviorImpl.{ GetSeenSequenceNr, GetState }
import pekko.persistence.typed.internal.ReplayingEvents.ReplayingState
import pekko.persistence.typed.internal.Running.WithSeqNrAccessible
import pekko.persistence.typed.internal.Running.startReplicationStream
import pekko.util.OptionVal
import pekko.util.PrettyDuration._
import pekko.util.unused

import scala.collection.immutable

/**
 * *
 * INTERNAL API
 *
 * Third (of four) behavior of an EventSourcedBehavior.
 *
 * In this behavior we finally start replaying events, beginning from the last applied sequence number
 * (i.e. the one up-until-which the snapshot recovery has brought us).
 *
 * Once recovery is completed, the actor becomes [[Running]], stashed messages are flushed
 * and control is given to the user's handlers to drive the actors behavior from there.
 *
 * See next behavior [[Running]].
 * See previous behavior [[ReplayingSnapshot]].
 */
@InternalApi
private[pekko] object ReplayingEvents {

  @InternalApi
  private[pekko] final case class ReplayingState[State](
      seqNr: Long,
      state: State,
      eventSeenInInterval: Boolean,
      toSeqNr: Long,
      receivedPoisonPill: Boolean,
      recoveryStartTime: Long,
      version: VersionVector,
      seenSeqNrPerReplica: Map[ReplicaId, Long],
      eventsReplayed: Long)

  def apply[C, E, S](setup: BehaviorSetup[C, E, S], state: ReplayingState[S]): Behavior[InternalProtocol] =
    Behaviors.setup { _ =>
      // protect against event recovery stalling forever because of journal overloaded and such
      setup.startRecoveryTimer(snapshot = false)
      new ReplayingEvents[C, E, S](setup.setMdcPhase(PersistenceMdc.ReplayingEvents), state)
    }

}

@InternalApi
private[pekko] final class ReplayingEvents[C, E, S](
    override val setup: BehaviorSetup[C, E, S],
    var state: ReplayingState[S])
    extends AbstractBehavior[InternalProtocol](setup.context)
    with JournalInteractions[C, E, S]
    with SnapshotInteractions[C, E, S]
    with StashManagement[C, E, S]
    with WithSeqNrAccessible {

  import InternalProtocol._
  import ReplayingEvents.ReplayingState

  replayEvents(state.seqNr + 1L, state.toSeqNr)
  onRecoveryStart(setup.context)

  @InternalStableApi
  def onRecoveryStart(@unused context: ActorContext[_]): Unit = ()
  @InternalStableApi
  def onRecoveryComplete(@unused context: ActorContext[_]): Unit = ()
  @InternalStableApi
  def onRecoveryFailed(@unused context: ActorContext[_], @unused reason: Throwable, @unused event: Option[Any]): Unit =
    ()

  override def onMessage(msg: InternalProtocol): Behavior[InternalProtocol] = {
    msg match {
      case JournalResponse(r)                         => onJournalResponse(r)
      case SnapshotterResponse(r)                     => onSnapshotterResponse(r)
      case RecoveryTickEvent(snap)                    => onRecoveryTick(snap)
      case evt: ReplicatedEventEnvelope[E @unchecked] => onInternalCommand(evt)
      case pe: PublishedEventImpl                     => onInternalCommand(pe)
      case cmd: IncomingCommand[C @unchecked]         => onInternalCommand(cmd)
      case get: GetState[S @unchecked]                => stashInternal(get)
      case get: GetSeenSequenceNr                     => stashInternal(get)
      case RecoveryPermitGranted                      => Behaviors.unhandled // should not happen, we already have the permit
    }
  }

  override def onSignal: PartialFunction[Signal, Behavior[InternalProtocol]] = {
    case PoisonPill =>
      state = state.copy(receivedPoisonPill = true)
      this
    case signal =>
      if (setup.onSignal(state.state, signal, catchAndLog = true)) this
      else Behaviors.unhandled
  }

  private def onJournalResponse(response: JournalProtocol.Response): Behavior[InternalProtocol] = {
    try {
      response match {
        case ReplayedMessage(repr) =>
          var eventForErrorReporting: OptionVal[Any] = OptionVal.None
          try {
            val eventSeq = setup.eventAdapter.fromJournal(repr.payload, repr.manifest)
            def handleEvent(event: E): Unit = {
              eventForErrorReporting = OptionVal.Some(event)
              state = state.copy(seqNr = repr.sequenceNr, eventsReplayed = state.eventsReplayed + 1)

              val replicatedMetaAndSelfReplica: Option[(ReplicatedEventMetadata, ReplicaId, ReplicationSetup)] =
                setup.replication match {
                  case Some(replication) =>
                    val meta = repr.metadata match {
                      case Some(m) => m.asInstanceOf[ReplicatedEventMetadata]
                      case None    =>
                        throw new IllegalStateException(
                          s"Replicated Event Sourcing enabled but existing event has no metadata. Migration isn't supported yet.")

                    }
                    replication.setContext(recoveryRunning = true, meta.originReplica, meta.concurrent)
                    Some((meta, replication.replicaId, replication))
                  case None => None
                }

              val newState = setup.eventHandler(state.state, event)

              setup.replication match {
                case Some(replication) =>
                  replication.clearContext()
                case None =>
              }

              replicatedMetaAndSelfReplica match {
                case Some((meta, selfReplica, replication)) if meta.originReplica != selfReplica =>
                  // keep track of highest origin seqnr per other replica
                  state = state.copy(
                    state = newState,
                    eventSeenInInterval = true,
                    version = meta.version,
                    seenSeqNrPerReplica = state.seenSeqNrPerReplica + (meta.originReplica -> meta.originSequenceNr))
                  replication.clearContext()
                case Some((_, _, replication)) =>
                  replication.clearContext()
                  state = state.copy(state = newState, eventSeenInInterval = true)
                case _ =>
                  state = state.copy(state = newState, eventSeenInInterval = true)
              }
            }

            eventSeq match {
              case SingleEventSeq(event) => handleEvent(event)
              case EventsSeq(events)     => events.foreach(handleEvent)
              case EmptyEventSeq         => // no events
            }

            this
          } catch {
            case NonFatal(ex) =>
              state = state.copy(repr.sequenceNr)
              onRecoveryFailure(ex, eventForErrorReporting.toOption)
          }

        case RecoverySuccess(highestJournalSeqNr) =>
          val highestSeqNr = Math.max(highestJournalSeqNr, state.seqNr)
          state = state.copy(seqNr = highestSeqNr)
          setup.internalLogger.debug("Recovery successful, recovered until sequenceNr: [{}]", highestSeqNr)
          onRecoveryCompleted(state)

        case ReplayMessagesFailure(cause) =>
          onRecoveryFailure(cause, Some(response))

        case _ =>
          Behaviors.unhandled
      }
    } catch {
      case ex: UnstashException[_] =>
        // let supervisor handle it, don't treat it as recovery failure
        throw ex
      case NonFatal(cause) =>
        onRecoveryFailure(cause, None)
    }
  }

  private def onInternalCommand(cmd: InternalProtocol): Behavior[InternalProtocol] = {
    // during recovery, stash all incoming commands
    if (state.receivedPoisonPill) {
      if (setup.settings.logOnStashing)
        setup.internalLogger.debug("Discarding message [{}], because actor is to be stopped.", cmd)
      Behaviors.unhandled
    } else {
      stashInternal(cmd)
    }
  }

  protected def onRecoveryTick(snapshot: Boolean): Behavior[InternalProtocol] =
    if (!snapshot) {
      if (state.eventSeenInInterval) {
        state = state.copy(eventSeenInInterval = false)
        this
      } else {
        val msg =
          s"Replay timed out, didn't get event within [${setup.recoveryEventTimeout}], highest sequence number seen [${state.seqNr}]"
        onRecoveryFailure(new RecoveryTimedOut(msg), None)
      }
    } else {
      // snapshot timeout, but we're already in the events recovery phase
      Behaviors.unhandled
    }

  def onSnapshotterResponse(response: SnapshotProtocol.Response): Behavior[InternalProtocol] = {
    setup.internalLogger
      .warn("Unexpected [{}] from SnapshotStore, already in replaying events state.", Logging.simpleName(response))
    Behaviors.unhandled // ignore the response
  }

  /**
   * Called whenever a message replay fails.
   *
   * This method throws `JournalFailureException` which will be caught by the internal
   * supervision strategy to stop or restart the actor with backoff.
   *
   * @param cause failure cause.
   * @param event the event that was being processed when the exception was thrown
   */
  private def onRecoveryFailure(cause: Throwable, event: Option[Any]): Behavior[InternalProtocol] = {
    onRecoveryFailed(setup.context, cause, event)
    setup.onSignal(state.state, RecoveryFailed(cause), catchAndLog = true)
    setup.cancelRecoveryTimer()
    tryReturnRecoveryPermit("on replay failure: " + cause.getMessage)
    if (setup.internalLogger.isDebugEnabled) {
      setup.internalLogger.debug2(
        "Recovery failure for persistenceId [{}] after {}",
        setup.persistenceId,
        (System.nanoTime() - state.recoveryStartTime).nanos.pretty)
    }
    val sequenceNr = state.seqNr

    val msg = event match {
      case Some(_: Message) | None =>
        s"Exception during recovery. Last known sequence number [$sequenceNr]. " +
        s"PersistenceId [${setup.persistenceId.id}], due to: ${cause.getMessage}"
      case Some(evt) =>
        s"Exception during recovery while handling [${evt.getClass.getName}] with sequence number [$sequenceNr]. " +
        s"PersistenceId [${setup.persistenceId.id}], due to: ${cause.getMessage}"
    }

    throw new JournalFailureException(msg, cause)
  }

  private def onRecoveryCompleted(state: ReplayingState[S]): Behavior[InternalProtocol] =
    try {
      onRecoveryComplete(setup.context)
      tryReturnRecoveryPermit("replay completed successfully")
      if (setup.internalLogger.isDebugEnabled) {
        setup.internalLogger.debug2(
          "Recovery for persistenceId [{}] took {}",
          setup.persistenceId,
          (System.nanoTime() - state.recoveryStartTime).nanos.pretty)
      }

      setup.onSignal(state.state, RecoveryCompleted, catchAndLog = false)

      if (state.receivedPoisonPill && isInternalStashEmpty && !isUnstashAllInProgress)
        Behaviors.stopped
      else {
        val runningState = Running.RunningState[S](
          seqNr = state.seqNr,
          state = state.state,
          receivedPoisonPill = state.receivedPoisonPill,
          state.version,
          seenPerReplica = state.seenSeqNrPerReplica,
          replicationControl = Map.empty)
        val running = new Running(setup.setMdcPhase(PersistenceMdc.RunningCmds))
        val initialRunningState = setup.replication match {
          case Some(replication) => startReplicationStream(setup, runningState, replication)
          case None              => runningState
        }
        setup.retention match {
          case criteria: SnapshotCountRetentionCriteriaImpl if criteria.snapshotEveryNEvents <= state.eventsReplayed =>
            internalSaveSnapshot(initialRunningState)
            new running.StoringSnapshot(initialRunningState, immutable.Seq.empty, SnapshotWithoutRetention)
          case _ =>
            tryUnstashOne(new running.HandlingCommands(initialRunningState))
        }
      }
    } finally {
      setup.cancelRecoveryTimer()
    }

  override def currentSequenceNumber: Long =
    state.seqNr
}
