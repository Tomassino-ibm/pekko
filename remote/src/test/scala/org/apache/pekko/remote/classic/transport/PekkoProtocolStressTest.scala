/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.remote.classic.transport

import scala.concurrent.Await
import scala.concurrent.duration._

import com.typesafe.config.{ Config, ConfigFactory }

import org.apache.pekko
import pekko.actor._
import pekko.remote.{ EndpointException, RARP }
import pekko.remote.classic.transport.PekkoProtocolStressTest._
import pekko.remote.transport.FailureInjectorTransportAdapter.{ Drop, One }
import pekko.testkit._

object PekkoProtocolStressTest {
  val configA: Config =
    ConfigFactory.parseString("""
    pekko {
      #loglevel = DEBUG
      actor.provider = remote
      remote.artery.enabled = off

      remote.classic.log-remote-lifecycle-events = on

      remote.classic.transport-failure-detector {
        max-sample-size = 2
        min-std-deviation = 1 ms
        ## We want lots of lost connections in this test, keep it sensitive
        heartbeat-interval = 1 s
        acceptable-heartbeat-pause = 1 s
      }
      ## Keep gate duration in this test for a low value otherwise too much messages are dropped
      remote.classic.retry-gate-closed-for = 100 ms

      remote.classic.netty.tcp {
        applied-adapters = ["gremlin"]
        port = 0
      }

    }
    # test is using Java serialization and not priority to rewrite
    pekko.actor.allow-java-serialization = on
    pekko.actor.warn-about-java-serializer-usage = off
    """)

  object ResendFinal

  class SequenceVerifier(remote: ActorRef, controller: ActorRef) extends Actor {
    import context.dispatcher

    val limit = 100000
    var nextSeq = 0
    var maxSeq = -1
    var losses = 0

    def receive = {
      case "start"    => self ! "sendNext"
      case "sendNext" =>
        if (nextSeq < limit) {
          remote ! nextSeq
          nextSeq += 1
          if (nextSeq % 2000 == 0) context.system.scheduler.scheduleOnce(500.milliseconds, self, "sendNext")
          else self ! "sendNext"
        }
      case seq: Int =>
        if (seq > maxSeq) {
          losses += seq - maxSeq - 1
          maxSeq = seq
          // Due to the (bursty) lossyness of gate, we are happy with receiving at least one message from the upper
          // half (> 50000). Since messages are sent in bursts of 2000 0.5 seconds apart, this is reasonable.
          // The purpose of this test is not reliable delivery (there is a gremlin with 30% loss anyway) but respecting
          // the proper ordering.
          if (seq > limit * 0.5) {
            controller ! ((maxSeq, losses))
            context.system.scheduler.scheduleWithFixedDelay(1.second, 1.second, self, ResendFinal)
            context.become(done)
          }
        } else {
          controller ! s"Received out of order message. Previous: $maxSeq Received: $seq"
        }
    }

    // Make sure the other side eventually "gets the message"
    def done: Receive = {
      case ResendFinal =>
        controller ! ((maxSeq, losses))
    }
  }

}

class PekkoProtocolStressTest extends PekkoSpec(configA) with ImplicitSender with DefaultTimeout {

  val systemB = ActorSystem("systemB", system.settings.config)
  val remote = systemB.actorOf(Props(new Actor {
      def receive = {
        case seq: Int => sender() ! seq
      }
    }), "echo")

  val addressB = systemB.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
  val rootB = RootActorPath(addressB)
  val here = {
    system.actorSelection(rootB / "user" / "echo") ! Identify(None)
    expectMsgType[ActorIdentity].ref.get
  }

  "PekkoProtocolTransport" must {
    "guarantee at-most-once delivery and message ordering despite packet loss" taggedAs TimingTest in {
      system.eventStream.publish(TestEvent.Mute(DeadLettersFilter[Any]))
      systemB.eventStream.publish(TestEvent.Mute(DeadLettersFilter[Any]))
      Await.result(RARP(system).provider.transport.managementCommand(One(addressB, Drop(0.1, 0.1))), 3.seconds.dilated)

      system.actorOf(Props(classOf[SequenceVerifier], here, self)) ! "start"

      expectMsgPF(60.seconds) {
        case (received: Int, lost: Int) =>
          log.debug(s" ######## Received ${received - lost} messages from $received ########")
      }
    }
  }

  override def beforeTermination(): Unit = {
    system.eventStream.publish(
      TestEvent.Mute(
        EventFilter.warning(source = s"pekko://AkkaProtocolStressTest/user/$$a", start = "received dead letter"),
        EventFilter.warning(pattern = "received dead letter.*(InboundPayload|Disassociate)")))
    systemB.eventStream.publish(
      TestEvent.Mute(
        EventFilter[EndpointException](),
        EventFilter.error(start = "AssociationError"),
        EventFilter.warning(pattern = "received dead letter.*(InboundPayload|Disassociate)")))
  }

  override def afterTermination(): Unit = shutdown(systemB)

}
