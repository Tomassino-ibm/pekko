/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2015-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.stream.javadsl

import java.util.{ Comparator, Optional }
import java.util.concurrent.CompletionStage
import java.util.function.Supplier

import scala.annotation.{ nowarn, varargs }
import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

import org.apache.pekko
import pekko.NotUsed
import pekko.event.{ LogMarker, LoggingAdapter, MarkerLoggingAdapter }
import pekko.japi.{ function, Pair }
import pekko.stream._
import pekko.stream.impl.fusing.{ StatefulMapConcat, ZipWithIndexJava }
import pekko.util.ConstantFun
import pekko.util.FutureConverters._
import pekko.util.JavaDurationConverters._
import pekko.util.OptionConverters._
import pekko.util.ccompat.JavaConverters._
import pekko.util.ccompat._

/**
 * * Upcast a stream of elements to a stream of supertypes of that element. Useful in combination with
 * fan-in operators where you do not want to pay the cost of casting each element in a `map`.
 */
object SubSource {
  def upcast[U, T <: U, Mat](source: SubSource[T, Mat]): SubSource[U, Mat] = source.asInstanceOf[SubSource[U, Mat]]
}

/**
 * A “stream of streams” sub-flow of data elements, e.g. produced by `groupBy`.
 * SubFlows cannot contribute to the super-flow’s materialized value since they
 * are materialized later, during the runtime of the flow graph processing.
 */
@ccompatUsedUntil213
class SubSource[Out, Mat](
    delegate: scaladsl.SubFlow[Out, Mat, scaladsl.Source[Out, Mat]#Repr, scaladsl.RunnableGraph[Mat]]) {

  /** Converts this Flow to its Scala DSL counterpart */
  def asScala
      : scaladsl.SubFlow[Out, Mat, scaladsl.Source[Out, Mat]#Repr, scaladsl.RunnableGraph[Mat]] @uncheckedVariance =
    delegate

  /**
   * Flatten the sub-flows back into the super-source by performing a merge
   * without parallelism limit (i.e. having an unbounded number of sub-flows
   * active concurrently).
   *
   * This is identical in effect to `mergeSubstreamsWithParallelism(Integer.MAX_VALUE)`.
   */
  def mergeSubstreams(): Source[Out, Mat] =
    new Source(delegate.mergeSubstreams)

  /**
   * Flatten the sub-flows back into the super-source by performing a merge
   * with the given parallelism limit. This means that only up to `parallelism`
   * substreams will be executed at any given time. Substreams that are not
   * yet executed are also not materialized, meaning that back-pressure will
   * be exerted at the operator that creates the substreams when the parallelism
   * limit is reached.
   */
  def mergeSubstreamsWithParallelism(parallelism: Int): Source[Out, Mat] =
    new Source(delegate.mergeSubstreamsWithParallelism(parallelism))

  /**
   * Flatten the sub-flows back into the super-source by concatenating them.
   * This is usually a bad idea when combined with `groupBy` since it can
   * easily lead to deadlock—the concatenation does not consume from the second
   * substream until the first has finished and the `groupBy` operator will get
   * back-pressure from the second stream.
   *
   * This is identical in effect to `mergeSubstreamsWithParallelism(1)`.
   */
  def concatSubstreams(): Source[Out, Mat] =
    new Source(delegate.concatSubstreams)

  /**
   * Transform this [[SubSource]] by appending the given processing steps.
   * {{{
   *     +----------------------------+
   *     | Resulting Source           |
   *     |                            |
   *     |  +------+        +------+  |
   *     |  |      |        |      |  |
   *     |  | this | ~Out~> | flow | ~~> T
   *     |  |      |        |      |  |
   *     |  +------+        +------+  |
   *     +----------------------------+
   * }}}
   * The materialized value of the combined [[Flow]] will be the materialized
   * value of the current flow (ignoring the other Flow’s value), use
   * [[Flow#viaMat viaMat]] if a different strategy is needed.
   */
  def via[T, M](flow: Graph[FlowShape[Out, T], M]): SubSource[T, Mat] =
    new SubSource(delegate.via(flow))

  /**
   * Connect this [[SubSource]] to a [[Sink]], concatenating the processing steps of both.
   * This means that all sub-flows that result from the previous sub-stream operator
   * will be attached to the given sink.
   * {{{
   *     +----------------------------+
   *     | Resulting RunnableGraph    |
   *     |                            |
   *     |  +------+        +------+  |
   *     |  |      |        |      |  |
   *     |  | this | ~Out~> | sink |  |
   *     |  |      |        |      |  |
   *     |  +------+        +------+  |
   *     +----------------------------+
   * }}}
   */
  def to(sink: Graph[SinkShape[Out], _]): RunnableGraph[Mat] =
    RunnableGraph.fromGraph(delegate.to(sink))

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def map[T](f: function.Function[Out, T]): SubSource[T, Mat] =
    new SubSource(delegate.map(f.apply))

  /**
   * This is a simplified version of `wireTap(Sink)` that takes only a simple procedure.
   * Elements will be passed into this "side channel" function, and any of its results will be ignored.
   *
   * If the wire-tap operation is slow (it backpressures), elements that would've been sent to it will be dropped instead.
   * It is similar to [[#alsoTo]] which does backpressure instead of dropping elements.
   *
   * This operation is useful for inspecting the passed through element, usually by means of side-effecting
   * operations (such as `println`, or emitting metrics), for each element without having to modify it.
   *
   * For logging signals (elements, completion, error) consider using the [[log]] operator instead,
   * along with appropriate `ActorAttributes.logLevels`.
   *
   * '''Emits when''' upstream emits an element; the same element will be passed to the attached function,
   *                  as well as to the downstream operator
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def wireTap(f: function.Procedure[Out]): SubSource[Out, Mat] =
    new SubSource(delegate.wireTap(f(_)))

  /**
   * Transform each input element into an `Iterable` of output elements that is
   * then flattened into the output stream.
   *
   * Make sure that the `Iterable` is immutable or at least not modified after
   * being used as an output sequence. Otherwise the stream may fail with
   * `ConcurrentModificationException` or other more subtle errors may occur.
   *
   * The returned `Iterable` MUST NOT contain `null` values,
   * as they are illegal as stream elements - according to the Reactive Streams specification.
   *
   * '''Emits when''' the mapping function returns an element or there are still remaining elements
   * from the previously calculated collection
   *
   * '''Backpressures when''' downstream backpressures or there are still remaining elements from the
   * previously calculated collection
   *
   * '''Completes when''' upstream completes and all remaining elements has been emitted
   *
   * '''Cancels when''' downstream cancels
   */
  def mapConcat[T](f: function.Function[Out, java.lang.Iterable[T]]): SubSource[T, Mat] =
    new SubSource(delegate.mapConcat { elem =>
      f(elem).asScala
    })

  /**
   * Transform each stream element with the help of a state.
   *
   * The state creation function is invoked once when the stream is materialized and the returned state is passed to
   * the mapping function for mapping the first element. The mapping function returns a mapped element to emit
   * downstream and a state to pass to the next mapping function. The state can be the same for each mapping return,
   * be a new immutable state but it is also safe to use a mutable state. The returned `T` MUST NOT be `null` as it is
   * illegal as stream element - according to the Reactive Streams specification.
   *
   * For stateless variant see [[map]].
   *
   * The `onComplete` function is called only once when the upstream or downstream finished, You can do some clean-up here,
   * and if the returned value is not empty, it will be emitted to the downstream if available, otherwise the value will be dropped.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the mapping function returns an element and downstream is ready to consume it
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * @tparam S the type of the state
   * @tparam T the type of the output elements
   * @param create a function that creates the initial state
   * @param f a function that transforms the upstream element and the state into a pair of next state and output element
   * @param onComplete a function that transforms the ongoing state into an optional output element
   */
  def statefulMap[S, T](
      create: function.Creator[S],
      f: function.Function2[S, Out, Pair[S, T]],
      onComplete: function.Function[S, Optional[T]]): javadsl.SubSource[T, Mat] =
    new SubSource(
      delegate.statefulMap(() => create.create())(
        (s: S, out: Out) => f.apply(s, out).toScala,
        (s: S) => onComplete.apply(s).toScala))

  /**
   * Transform each stream element with the help of a resource.
   *
   * The resource creation function is invoked once when the stream is materialized and the returned resource is passed to
   * the mapping function for mapping the first element. The mapping function returns a mapped element to emit
   * downstream. The returned `T` MUST NOT be `null` as it is illegal as stream element - according to the Reactive Streams specification.
   *
   * The `close` function is called only once when the upstream or downstream finishes or fails. You can do some clean-up here,
   * and if the returned value is not empty, it will be emitted to the downstream if available, otherwise the value will be dropped.
   *
   * Early completion can be done with combination of the [[takeWhile]] operator.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * You can configure the default dispatcher for this Source by changing the `pekko.stream.materializer.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * '''Emits when''' the mapping function returns an element and downstream is ready to consume it
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * @tparam R the type of the resource
   * @tparam T the type of the output elements
   * @param create function that creates the resource
   * @param f function that transforms the upstream element and the resource to output element
   * @param close function that closes the resource, optionally outputting a last element
   * @since 1.1.0
   */
  def mapWithResource[R, T](
      create: function.Creator[R],
      f: function.Function2[R, Out, T],
      close: function.Function[R, Optional[T]]): javadsl.SubSource[T, Mat] =
    new SubSource(
      delegate.mapWithResource(() => create.create())(
        (resource, out) => f(resource, out),
        resource => close.apply(resource).toScala))

  /**
   * Transform each stream element with the help of an [[AutoCloseable]] resource and close it when the stream finishes or fails.
   *
   * The resource creation function is invoked once when the stream is materialized and the returned resource is passed to
   * the mapping function for mapping the first element. The mapping function returns a mapped element to emit
   * downstream. The returned `T` MUST NOT be `null` as it is illegal as stream element - according to the Reactive Streams specification.
   *
   * The [[AutoCloseable]] resource is closed only once when the upstream or downstream finishes or fails.
   *
   * Early completion can be done with combination of the [[takeWhile]] operator.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * You can configure the default dispatcher for this Source by changing the `pekko.stream.materializer.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * '''Emits when''' the mapping function returns an element and downstream is ready to consume it
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * @tparam R the type of the resource
   * @tparam T the type of the output elements
   * @param create function that creates the resource
   * @param f function that transforms the upstream element and the resource to output element
   * @since 1.1.0
   */
  def mapWithResource[R <: AutoCloseable, T](
      create: function.Creator[R],
      f: function.Function2[R, Out, T]): javadsl.SubSource[T, Mat] =
    mapWithResource(create, f,
      (resource: AutoCloseable) => {
        resource.close()
        Optional.empty()
      })

  /**
   * Transform each input element into an `Iterable` of output elements that is
   * then flattened into the output stream. The transformation is meant to be stateful,
   * which is enabled by creating the transformation function anew for every materialization —
   * the returned function will typically close over mutable objects to store state between
   * invocations. For the stateless variant see [[#mapConcat]].
   *
   * Make sure that the `Iterable` is immutable or at least not modified after
   * being used as an output sequence. Otherwise the stream may fail with
   * `ConcurrentModificationException` or other more subtle errors may occur.
   *
   * The returned `Iterable` MUST NOT contain `null` values,
   * as they are illegal as stream elements - according to the Reactive Streams specification.
   *
   * This operator doesn't handle upstream's completion signal since the state kept in the closure can be lost.
   * Use [[pekko.stream.javadsl.Flow.statefulMap]], or return an [[StatefulMapConcatAccumulator]] instead.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the mapping function returns an element or there are still remaining elements
   * from the previously calculated collection
   *
   * '''Backpressures when''' downstream backpressures or there are still remaining elements from the
   * previously calculated collection
   *
   * '''Completes when''' upstream completes and all remaining elements has been emitted
   *
   * '''Cancels when''' downstream cancels
   */
  def statefulMapConcat[T](f: function.Creator[function.Function[Out, java.lang.Iterable[T]]]): SubSource[T, Mat] = {
    import scaladsl.StatefulMapConcatAccumulatorFactory._
    new SubSource(delegate.via(new StatefulMapConcat(f.asFactory)))
  }

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `CompletionStage` and the
   * value of that future will be emitted downstream. The number of CompletionStages
   * that shall run in parallel is given as the first argument to ``mapAsync``.
   * These CompletionStages may complete in any order, but the elements that
   * are emitted downstream are in the same order as received from upstream.
   *
   * If the function `f` throws an exception or if the `CompletionStage` is completed
   * with failure and the supervision decision is [[pekko.stream.Supervision#stop]]
   * the stream will be completed with failure.
   *
   * If the function `f` throws an exception or if the `CompletionStage` is completed
   * with failure and the supervision decision is [[pekko.stream.Supervision#resume]] or
   * [[pekko.stream.Supervision#restart]] or the `CompletionStage` completed with `null`,
   * the element is dropped and the stream continues.
   *
   * The function `f` is always invoked on the elements in the order they arrive.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the CompletionStage returned by the provided function finishes for the next element in sequence
   *
   * '''Backpressures when''' the number of CompletionStages reaches the configured parallelism and the downstream
   * backpressures or the first CompletionStage is not completed
   *
   * '''Completes when''' upstream completes and all CompletionStages has been completed and all elements has been emitted
   *
   * '''Cancels when''' downstream cancels
   *
   * @see [[#mapAsyncUnordered]]
   */
  def mapAsync[T](parallelism: Int, f: function.Function[Out, CompletionStage[T]]): SubSource[T, Mat] =
    new SubSource(delegate.mapAsync(parallelism)(x => f(x).asScala))

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step. The function returns a `CompletionStage` and the
   * value of that future will be emitted downstream. The number of CompletionStages
   * that shall run in parallel is given as the first argument to ``mapAsyncUnordered``.
   * Each processed element will be emitted downstream as soon as it is ready, i.e. it is possible
   * that the elements are not emitted downstream in the same order as received from upstream.
   *
   * If the function `f` throws an exception or if the `CompletionStage` is completed
   * with failure and the supervision decision is [[pekko.stream.Supervision#stop]]
   * the stream will be completed with failure.
   *
   * If the function `f` throws an exception or if the `CompletionStage` is completed
   * with failure and the supervision decision is [[pekko.stream.Supervision#resume]] or
   * [[pekko.stream.Supervision#restart]] or the `CompletionStage` completed with `null`,
   * the element is dropped and the stream continues.
   *
   * The function `f` is always invoked on the elements in the order they arrive (even though the result of the futures
   * returned by `f` might be emitted in a different order).
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' any of the CompletionStage returned by the provided function complete
   *
   * '''Backpressures when''' the number of CompletionStage reaches the configured parallelism and the downstream backpressures
   *
   * '''Completes when''' upstream completes and all CompletionStage has been completed and all elements has been emitted
   *
   * '''Cancels when''' downstream cancels
   *
   * @see [[#mapAsync]]
   */
  def mapAsyncUnordered[T](parallelism: Int, f: function.Function[Out, CompletionStage[T]]): SubSource[T, Mat] =
    new SubSource(delegate.mapAsyncUnordered(parallelism)(x => f(x).asScala))

  /**
   * Transforms this stream. Works very similarly to [[#mapAsync]] but with an additional
   * partition step before the transform step. The transform function receives the an individual
   * stream entry and the calculated partition value for that entry. The max parallelism of per partition is 1.
   *
   * The function `partitioner` is always invoked on the elements in the order they arrive.
   * The function `f` is always invoked on the elements which in the same partition in the order they arrive.
   *
   * If the function `partitioner` or `f` throws an exception or if the [[CompletionStage]] is completed
   * with failure and the supervision decision is [[pekko.stream.Supervision.Stop]]
   * the stream will be completed with failure, otherwise the stream continues and the current element is dropped.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the Future returned by the provided function finishes for the next element in sequence
   *
   * '''Backpressures when''' the number of futures reaches the configured parallelism and the downstream
   * backpressures
   *
   * '''Completes when''' upstream completes and all futures have been completed and all elements have been emitted
   *
   * '''Cancels when''' downstream cancels
   *
   * @since 1.1.0
   * @see [[#mapAsync]]
   * @see [[#mapAsyncPartitionedUnordered]]
   */
  def mapAsyncPartitioned[T, P](
      parallelism: Int,
      partitioner: function.Function[Out, P],
      f: function.Function2[Out, P, CompletionStage[T]]): SubSource[T, Mat] =
    new SubSource(delegate.mapAsyncPartitioned(parallelism)(partitioner(_))(f(_, _).asScala))

  /**
   * Transforms this stream. Works very similarly to [[#mapAsyncUnordered]] but with an additional
   * partition step before the transform step. The transform function receives the an individual
   * stream entry and the calculated partition value for that entry.The max parallelism of per partition is 1.
   *
   * The function `partitioner` is always invoked on the elements in the order they arrive.
   * The function `f` is always invoked on the elements which in the same partition in the order they arrive.
   *
   * If the function `partitioner` or `f` throws an exception or if the [[CompletionStage]] is completed
   * with failure and the supervision decision is [[pekko.stream.Supervision.Stop]]
   * the stream will be completed with failure, otherwise the stream continues and the current element is dropped.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the Future returned by the provided function finishes and downstream available.
   *
   * '''Backpressures when''' the number of futures reaches the configured parallelism and the downstream
   * backpressures
   *
   * '''Completes when''' upstream completes and all futures have been completed and all elements have been emitted
   *
   * '''Cancels when''' downstream cancels
   *
   * @since 1.1.0
   * @see [[#mapAsyncUnordered]]
   * @see [[#mapAsyncPartitioned]]
   */
  def mapAsyncPartitionedUnordered[T, P](
      parallelism: Int,
      partitioner: function.Function[Out, P],
      f: function.Function2[Out, P, CompletionStage[T]]): SubSource[T, Mat] =
    new SubSource(delegate.mapAsyncPartitionedUnordered(parallelism)(partitioner(_))(f(_, _).asScala))

  /**
   * Only pass on those elements that satisfy the given predicate.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the given predicate returns true for the element
   *
   * '''Backpressures when''' the given predicate returns true for the element and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def filter(p: function.Predicate[Out]): SubSource[Out, Mat] =
    new SubSource(delegate.filter(p.test))

  /**
   * Only pass on those elements that NOT satisfy the given predicate.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the given predicate returns false for the element
   *
   * '''Backpressures when''' the given predicate returns false for the element and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def filterNot(p: function.Predicate[Out]): SubSource[Out, Mat] =
    new SubSource(delegate.filterNot(p.test))

  /**
   * Only pass on those elements that are distinct from the previous element.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the element is distinct from the previous element
   *
   * '''Backpressures when''' the element is distinct from the previous element and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * @since 1.2.0
   */
  def dropRepeated(): SubSource[Out, Mat] = new SubSource(delegate.dropRepeated())

  /**
   * Only pass on those elements that are distinct from the previous element according to the given predicate.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the element is distinct from the previous element
   *
   * '''Backpressures when''' the element is distinct from the previous element and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * @since 1.2.0
   */
  def dropRepeated(p: function.Function2[Out, Out, Boolean]): SubSource[Out, Mat] =
    new SubSource(delegate.dropRepeated(p.apply))

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step.
   * Non-matching elements are filtered out.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the provided partial function is defined for the element
   *
   * '''Backpressures when''' the partial function is defined for the element and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def collect[T](pf: PartialFunction[Out, T]): SubSource[T, Mat] =
    new SubSource(delegate.collect(pf))

  /**
   * Transform this stream by applying the given partial function to the first element
   * on which the function is defined as it pass through this processing step, and cancel the upstream publisher
   * after the first element is emitted.
   *
   * Non-matching elements are filtered out.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the provided partial function is defined for the first element
   *
   * '''Backpressures when''' the partial function is defined for the element and downstream backpressures
   *
   * '''Completes when''' upstream completes or the first element is emitted
   *
   * '''Cancels when''' downstream cancels
   */
  def collectFirst[T](pf: PartialFunction[Out, T]): SubSource[T, Mat] =
    new SubSource(delegate.collectFirst(pf))

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step.
   * Non-matching elements are filtered out.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the provided partial function is defined for the element
   *
   * '''Backpressures when''' the partial function is defined for the element and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   * @since 1.1.0
   */
  def collectWhile[T](pf: PartialFunction[Out, T]): SubSource[T, Mat] =
    new SubSource(delegate.collectWhile(pf))

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step, and cancel the
   * upstream publisher after the partial function is not applied.
   *
   * The stream will be completed without producing any elements if the partial function is not applied for
   * the first stream element, eg: there is a downstream buffer.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the provided partial function is defined for the element
   *
   * '''Backpressures when''' the partial function is defined for the element and downstream backpressures
   *
   * '''Completes when''' upstream completes or the partial function is not applied.
   *
   * '''Cancels when''' downstream cancels
   */
  def collectType[T](clazz: Class[T]): javadsl.SubSource[T, Mat] =
    new SubSource(delegate.collectType[T](ClassTag[T](clazz)))

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * `n` must be positive, otherwise IllegalArgumentException is thrown.
   *
   * '''Emits when''' the specified number of elements has been accumulated or upstream completed
   *
   * '''Backpressures when''' a group has been assembled and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def grouped(n: Int): SubSource[java.util.List[Out @uncheckedVariance], Mat] =
    new SubSource(delegate.grouped(n).map(_.asJava)) // TODO optimize to one step

  /**
   * Chunk up this stream into groups of elements that have a cumulative weight greater than or equal to
   * the `minWeight`, with the last group possibly smaller than requested `minWeight` due to end-of-stream.
   *
   * `minWeight` must be positive, otherwise IllegalArgumentException is thrown.
   * `costFn` must return a non-negative result for all inputs, otherwise the stage will fail
   * with an IllegalArgumentException.
   *
   * '''Emits when''' the cumulative weight of elements is greater than or equal to the `minWeight` or upstream completed
   *
   * '''Backpressures when''' a buffered group weighs more than `minWeight` and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def groupedWeighted(minWeight: Long,
      costFn: function.Function[Out, java.lang.Long]): SubSource[java.util.List[Out @uncheckedVariance], Mat] =
    new SubSource(delegate.groupedWeighted(minWeight)(costFn.apply).map(_.asJava)) // TODO optimize to one step

  /**
   * Partitions this stream into chunks by a delimiter function, which is applied to each incoming element,
   * when the result of the function is not the same as the previous element's result, a chunk is emitted.
   *
   * The `f` function must return a non-null value for all elements, otherwise the stage will fail.
   *
   * '''Emits when''' the delimiter function returns a different value than the previous element's result
   *
   * '''Backpressures when''' a chunk has been assembled and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * @since 1.2.0
   */
  def groupedAdjacentBy[R](f: function.Function[Out, R]): SubSource[java.util.List[Out @uncheckedVariance], Mat] =
    new SubSource(delegate.groupedAdjacentBy(f.apply).map(_.asJava))

  /**
   * Partitions this stream into chunks by a delimiter function, which is applied to each incoming element,
   * when the result of the function is not the same as the previous element's result, or the accumulated weight exceeds
   * the `maxWeight`, a chunk is emitted.
   *
   * The `f` function must return a non-null value , and the `costFn` must return a non-negative result for all inputs,
   * otherwise the stage will fail.
   *
   * '''Emits when''' the delimiter function returns a different value than the previous element's result, or exceeds the `maxWeight`.
   *
   * '''Backpressures when''' a chunk has been assembled and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * @since 1.2.0
   */
  def groupedAdjacentByWeighted[R](f: function.Function[Out, R],
      maxWeight: Long,
      costFn: java.util.function.Function[Out, java.lang.Long])
      : SubSource[java.util.List[Out @uncheckedVariance], Mat] =
    new SubSource(delegate.groupedAdjacentByWeighted(f.apply, maxWeight)(costFn.apply).map(_.asJava))

  /**
   * Apply a sliding window over the stream and return the windows as groups of elements, with the last group
   * possibly smaller than requested due to end-of-stream.
   *
   * `n` must be positive, otherwise IllegalArgumentException is thrown.
   * `step` must be positive, otherwise IllegalArgumentException is thrown.
   *
   * '''Emits when''' enough elements have been collected within the window or upstream completed
   *
   * '''Backpressures when''' a window has been assembled and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  /**
   * Ensure stream boundedness by limiting the number of elements from upstream.
   * If the number of incoming elements exceeds max, it will signal
   * upstream failure `StreamLimitException` downstream.
   *
   * Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * The stream will be completed without producing any elements if `n` is zero
   * or negative.
   *
   * '''Emits when''' the specified number of elements to take has not yet been reached
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' the defined number of elements has been taken or upstream completes
   *
   * '''Cancels when''' the defined number of elements has been taken or downstream cancels
   *
   * See also [[Flow.take]], [[Flow.takeWithin]], [[Flow.takeWhile]]
   */
  def limit(n: Int): javadsl.SubSource[Out, Mat] = new SubSource(delegate.limit(n))

  /**
   * Ensure stream boundedness by evaluating the cost of incoming elements
   * using a cost function. Exactly how many elements will be allowed to travel downstream depends on the
   * evaluated cost of each element. If the accumulated cost exceeds max, it will signal
   * upstream failure `StreamLimitException` downstream.
   *
   * Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * The stream will be completed without producing any elements if `n` is zero
   * or negative.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the specified number of elements to take has not yet been reached
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' the defined number of elements has been taken or upstream completes
   *
   * '''Cancels when''' the defined number of elements has been taken or downstream cancels
   *
   * See also [[Flow.take]], [[Flow.takeWithin]], [[Flow.takeWhile]]
   */
  def limitWeighted(n: Long, costFn: function.Function[Out, java.lang.Long]): javadsl.SubSource[Out, Mat] = {
    new SubSource(delegate.limitWeighted(n)(costFn.apply))
  }

  def sliding(n: Int, step: Int = 1): SubSource[java.util.List[Out @uncheckedVariance], Mat] =
    new SubSource(delegate.sliding(n, step).map(_.asJava)) // TODO optimize to one step

  /**
   * Similar to `fold` but is not a terminal operation,
   * emits its current value which starts at `zero` and then
   * applies the current and next value to the given function `f`,
   * emitting the next current value.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[pekko.stream.Supervision#restart]] current value starts at `zero` again
   * the stream will continue.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * Note that the `zero` value must be immutable.
   *
   * '''Emits when''' the function scanning the element returns a new element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def scan[T](zero: T, f: function.Function2[T, Out, T]): SubSource[T, Mat] =
    new SubSource(delegate.scan(zero)(f.apply))

  /**
   * Similar to `scan` but with an asynchronous function,
   * emits its current value which starts at `zero` and then
   * applies the current and next value to the given function `f`,
   * emitting a `Future` that resolves to the next current value.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[pekko.stream.Supervision.Restart]] current value starts at `zero` again
   * the stream will continue.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[pekko.stream.Supervision.Resume]] current value starts at the previous
   * current value, or zero when it doesn't have one, and the stream will continue.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * Note that the `zero` value must be immutable.
   *
   * '''Emits when''' the future returned by f` completes
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes and the last future returned by `f` completes
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[#scan]]
   */
  def scanAsync[T](zero: T, f: function.Function2[T, Out, CompletionStage[T]]): SubSource[T, Mat] =
    new SubSource(delegate.scanAsync(zero) { (out, in) =>
      f(out, in).asScala
    })

  /**
   * Similar to `scan` but only emits its result when the upstream completes,
   * after which it also completes. Applies the given function `f` towards its current and next value,
   * yielding the next current value.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[pekko.stream.Supervision#restart]] current value starts at `zero` again
   * the stream will continue.
   *
   * Note that the `zero` value must be immutable.
   *
   * '''Emits when''' upstream completes
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def fold[T](zero: T, f: function.Function2[T, Out, T]): SubSource[T, Mat] =
    new SubSource(delegate.fold(zero)(f.apply))

  /**
   * Similar to `scan` but only emits its result when the upstream completes or the predicate `p` returns `false`.
   * after which it also completes. Applies the given function towards its current and next value,
   * yielding the next current value.
   *
   * If the function `f` throws an exception and the supervision decision is
   * [[pekko.stream.Supervision.Restart]] current value starts at `zero` again
   * the stream will continue.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * Note that the `zero` value must be immutable.
   *
   * '''Emits when''' upstream completes or the predicate `p` returns `false`
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[FlowOps.fold]]
   */
  def foldWhile[T](zero: T, p: function.Predicate[T], f: function.Function2[T, Out, T]): SubSource[T, Mat] =
    new SubSource(delegate.foldWhile(zero)(p.test)(f.apply))

  /**
   * Similar to `fold` but with an asynchronous function.
   * Applies the given function towards its current and next value,
   * yielding the next current value.
   *
   * If the function `f` returns a failure and the supervision decision is
   * [[pekko.stream.Supervision.Restart]] current value starts at `zero` again
   * the stream will continue.
   *
   * Note that the `zero` value must be immutable.
   *
   * '''Emits when''' upstream completes
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def foldAsync[T](zero: T, f: function.Function2[T, Out, CompletionStage[T]]): SubSource[T, Mat] =
    new SubSource(delegate.foldAsync(zero) { (out, in) =>
      f(out, in).asScala
    })

  /**
   * Similar to `fold` but uses first element as zero element.
   * Applies the given function towards its current and next value,
   * yielding the next current value.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' upstream completes
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def reduce(f: function.Function2[Out, Out, Out @uncheckedVariance]): SubSource[Out, Mat] =
    new SubSource(delegate.reduce(f.apply))

  /**
   * Intersperses stream with provided element, similar to how [[scala.collection.immutable.List.mkString]]
   * injects a separator between a List's elements.
   *
   * Additionally can inject start and end marker elements to stream.
   *
   * Examples:
   *
   * {{{
   * Source<Integer, ?> nums = Source.from(Arrays.asList(0, 1, 2, 3));
   * nums.intersperse(",");            //   1 , 2 , 3
   * nums.intersperse("[", ",", "]");  // [ 1 , 2 , 3 ]
   * }}}
   *
   * In case you want to only prepend or only append an element (yet still use the `intercept` feature
   * to inject a separator between elements, you may want to use the following pattern instead of the 3-argument
   * version of intersperse (See [[Source.concat]] for semantics details):
   *
   * {{{
   * Source.single(">> ").concat(flow.intersperse(","))
   * flow.intersperse(",").concat(Source.single("END"))
   * }}}
   *
   * '''Emits when''' upstream emits (or before with the `start` element if provided)
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def intersperse(start: Out, inject: Out, end: Out): SubSource[Out, Mat] =
    new SubSource(delegate.intersperse(start, inject, end))

  /**
   * Intersperses stream with provided element, similar to how [[scala.collection.immutable.List.mkString]]
   * injects a separator between a List's elements.
   *
   * Additionally can inject start and end marker elements to stream.
   *
   * Examples:
   *
   * {{{
   * Source<Integer, ?> nums = Source.from(Arrays.asList(0, 1, 2, 3));
   * nums.intersperse(",");            //   1 , 2 , 3
   * nums.intersperse("[", ",", "]");  // [ 1 , 2 , 3 ]
   * }}}
   *
   * '''Emits when''' upstream emits (or before with the `start` element if provided)
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def intersperse(inject: Out): SubSource[Out, Mat] =
    new SubSource(delegate.intersperse(inject))

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the given number of elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
   *
   * '''Emits when''' the configured time elapses since the last group has been emitted or `n` elements is buffered
   *
   * '''Backpressures when''' downstream backpressures, and there are `n+1` buffered elements
   *
   * '''Completes when''' upstream completes (emits last group)
   *
   * '''Cancels when''' downstream completes
   *
   * `maxNumber` must be positive, and `duration` must be greater than 0 seconds, otherwise
   * IllegalArgumentException is thrown.
   */
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "Akka 2.5.12")
  def groupedWithin(maxNumber: Int, duration: FiniteDuration): SubSource[java.util.List[Out @uncheckedVariance], Mat] =
    new SubSource(delegate.groupedWithin(maxNumber, duration).map(_.asJava)) // TODO optimize to one step

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the given number of elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
   *
   * '''Emits when''' the configured time elapses since the last group has been emitted or `n` elements is buffered
   *
   * '''Backpressures when''' downstream backpressures, and there are `n+1` buffered elements
   *
   * '''Completes when''' upstream completes (emits last group)
   *
   * '''Cancels when''' downstream completes
   *
   * `maxNumber` must be positive, and `duration` must be greater than 0 seconds, otherwise
   * IllegalArgumentException is thrown.
   */
  @nowarn("msg=deprecated")
  def groupedWithin(
      maxNumber: Int,
      duration: java.time.Duration): SubSource[java.util.List[Out @uncheckedVariance], Mat] =
    groupedWithin(maxNumber, duration.asScala)

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the weight of the elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
   *
   * '''Emits when''' the configured time elapses since the last group has been emitted or weight limit reached
   *
   * '''Backpressures when''' downstream backpressures, and buffered group (+ pending element) weighs more than `maxWeight`
   *
   * '''Completes when''' upstream completes (emits last group)
   *
   * '''Cancels when''' downstream completes
   *
   * `maxWeight` must be positive, and `duration` must be greater than 0 seconds, otherwise
   * IllegalArgumentException is thrown.
   */
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "Akka 2.5.12")
  def groupedWeightedWithin(
      maxWeight: Long,
      costFn: function.Function[Out, java.lang.Long],
      duration: FiniteDuration): javadsl.SubSource[java.util.List[Out @uncheckedVariance], Mat] =
    new SubSource(delegate.groupedWeightedWithin(maxWeight, duration)(costFn.apply).map(_.asJava))

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the weight of the elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
   *
   * '''Emits when''' the configured time elapses since the last group has been emitted or weight limit reached
   *
   * '''Backpressures when''' downstream backpressures, and buffered group (+ pending element) weighs more than `maxWeight`
   *
   * '''Completes when''' upstream completes (emits last group)
   *
   * '''Cancels when''' downstream completes
   *
   * `maxWeight` must be positive, and `duration` must be greater than 0 seconds, otherwise
   * IllegalArgumentException is thrown.
   */
  @nowarn("msg=deprecated")
  def groupedWeightedWithin(
      maxWeight: Long,
      costFn: function.Function[Out, java.lang.Long],
      duration: java.time.Duration): javadsl.SubSource[java.util.List[Out @uncheckedVariance], Mat] =
    groupedWeightedWithin(maxWeight, costFn, duration.asScala)

  /**
   * Chunk up this stream into groups of elements received within a time window,
   * or limited by the weight and number of the elements, whatever happens first.
   * Empty groups will not be emitted if no elements are received from upstream.
   * The last group before end-of-stream will contain the buffered elements
   * since the previously emitted group.
   *
   * '''Emits when''' the configured time elapses since the last group has been emitted or weight limit reached
   *
   * '''Backpressures when''' downstream backpressures, and buffered group (+ pending element) weighs more than
   * `maxWeight` or has more than `maxNumber` elements
   *
   * '''Completes when''' upstream completes (emits last group)
   *
   * '''Cancels when''' downstream completes
   *
   * `maxWeight` must be positive, `maxNumber` must be positive, and `duration` must be greater than 0 seconds,
   * otherwise IllegalArgumentException is thrown.
   */
  def groupedWeightedWithin(
      maxWeight: Long,
      maxNumber: Int,
      costFn: function.Function[Out, java.lang.Long],
      duration: java.time.Duration): javadsl.SubSource[java.util.List[Out @uncheckedVariance], Mat] =
    new SubSource(delegate.groupedWeightedWithin(maxWeight, maxNumber, duration.asScala)(costFn.apply).map(_.asJava))

  /**
   * Discard the given number of elements at the beginning of the stream.
   * No elements will be dropped if `n` is zero or negative.
   *
   * '''Emits when''' the specified number of elements has been dropped already
   *
   * '''Backpressures when''' the specified number of elements has been dropped and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def drop(n: Long): SubSource[Out, Mat] =
    new SubSource(delegate.drop(n))

  /**
   * Discard the elements received within the given duration at beginning of the stream.
   *
   * '''Emits when''' the specified time elapsed and a new upstream element arrives
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "Akka 2.5.12")
  def dropWithin(duration: FiniteDuration): SubSource[Out, Mat] =
    new SubSource(delegate.dropWithin(duration))

  /**
   * Discard the elements received within the given duration at beginning of the stream.
   *
   * '''Emits when''' the specified time elapsed and a new upstream element arrives
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  @nowarn("msg=deprecated")
  def dropWithin(duration: java.time.Duration): SubSource[Out, Mat] =
    dropWithin(duration.asScala)

  /**
   * Terminate processing (and cancel the upstream publisher) after predicate
   * returns false for the first time,
   * Due to input buffering some elements may have been requested from upstream publishers
   * that will then not be processed downstream of this step.
   *
   * The stream will be completed without producing any elements if predicate is false for
   * the first stream element.
   *
   * '''Emits when''' the predicate is true
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' predicate returned false (or 1 after predicate returns false if `inclusive` or upstream completes
   *
   * '''Cancels when''' predicate returned false or downstream cancels
   */
  def takeWhile(p: function.Predicate[Out]): SubSource[Out, Mat] = takeWhile(p, inclusive = false)

  /**
   * Terminate processing (and cancel the upstream publisher) after predicate
   * returns false for the first time, including the first failed element iff inclusive is true
   * Due to input buffering some elements may have been requested from upstream publishers
   * that will then not be processed downstream of this step.
   *
   * The stream will be completed without producing any elements if predicate is false for
   * the first stream element.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the predicate is true
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' predicate returned false (or 1 after predicate returns false if `inclusive`) or upstream completes
   *
   * '''Cancels when''' predicate returned false or downstream cancels
   */
  def takeWhile(p: function.Predicate[Out], inclusive: Boolean): SubSource[Out, Mat] =
    new SubSource(delegate.takeWhile(p.test, inclusive))

  /**
   * Terminate processing (and cancel the upstream publisher) after predicate
   * returns true for the first time,
   * Due to input buffering some elements may have been requested from upstream publishers
   * that will then not be processed downstream of this step.
   *
   * '''Emits when''' the predicate is false or the first time the predicate is true
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' after predicate returned true or upstream completes
   *
   * '''Cancels when''' after predicate returned true or downstream cancels
   *
   * See also [[SubSource.limit]], [[SubSource.limitWeighted]], [[SubSource.takeWhile]]
   * @since 1.2.0
   */
  def takeUntil(p: function.Predicate[Out]): SubSource[Out, Mat] = new SubSource(delegate.takeUntil(p.test))

  /**
   * Discard elements at the beginning of the stream while predicate is true.
   * All elements will be taken after predicate returns false first time.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' predicate returned false and for all following stream elements
   *
   * '''Backpressures when''' predicate returned false and downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def dropWhile(p: function.Predicate[Out]): SubSource[Out, Mat] =
    new SubSource(delegate.dropWhile(p.test))

  /**
   * Shifts elements emission in time by a specified amount. It allows to store elements
   * in internal buffer while waiting for next element to be emitted. Depending on the defined
   * [[pekko.stream.DelayOverflowStrategy]] it might drop elements or backpressure the upstream if
   * there is no space available in the buffer.
   *
   * Delay precision is 10ms to avoid unnecessary timer scheduling cycles
   *
   * Internal buffer has default capacity 16. You can set buffer size by calling `withAttributes(inputBuffer)`
   *
   * '''Emits when''' there is a pending element in the buffer and configured time for this element elapsed
   *  * EmitEarly - strategy do not wait to emit element if buffer is full
   *
   * '''Backpressures when''' depending on OverflowStrategy
   *  * Backpressure - backpressures when buffer is full
   *  * DropHead, DropTail, DropBuffer - never backpressures
   *  * Fail - fails the stream if buffer gets full
   *
   * '''Completes when''' upstream completes and buffered elements has been drained
   *
   * '''Cancels when''' downstream cancels
   *
   * @param of time to shift all messages
   * @param strategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "Akka 2.5.12")
  def delay(of: FiniteDuration, strategy: DelayOverflowStrategy): SubSource[Out, Mat] =
    new SubSource(delegate.delay(of, strategy))

  /**
   * Shifts elements emission in time by a specified amount. It allows to store elements
   * in internal buffer while waiting for next element to be emitted. Depending on the defined
   * [[pekko.stream.DelayOverflowStrategy]] it might drop elements or backpressure the upstream if
   * there is no space available in the buffer.
   *
   * Delay precision is 10ms to avoid unnecessary timer scheduling cycles
   *
   * Internal buffer has default capacity 16. You can set buffer size by calling `withAttributes(inputBuffer)`
   *
   * '''Emits when''' there is a pending element in the buffer and configured time for this element elapsed
   *  * EmitEarly - strategy do not wait to emit element if buffer is full
   *
   * '''Backpressures when''' depending on OverflowStrategy
   *  * Backpressure - backpressures when buffer is full
   *  * DropHead, DropTail, DropBuffer - never backpressures
   *  * Fail - fails the stream if buffer gets full
   *
   * '''Completes when''' upstream completes and buffered elements has been drained
   *
   * '''Cancels when''' downstream cancels
   *
   * @param of time to shift all messages
   * @param strategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  @nowarn("msg=deprecated")
  def delay(of: java.time.Duration, strategy: DelayOverflowStrategy): SubSource[Out, Mat] =
    delay(of.asScala, strategy)

  /**
   * Shifts elements emission in time by an amount individually determined through delay strategy a specified amount.
   * It allows to store elements in internal buffer while waiting for next element to be emitted. Depending on the defined
   * [[pekko.stream.DelayOverflowStrategy]] it might drop elements or backpressure the upstream if
   * there is no space available in the buffer.
   *
   * It determines delay for each ongoing element invoking `DelayStrategy.nextDelay(elem: T): FiniteDuration`.
   *
   * Note that elements are not re-ordered: if an element is given a delay much shorter than its predecessor,
   * it will still have to wait for the preceding element before being emitted.
   * It is also important to notice that [[DelayStrategy]] can be stateful.
   *
   * Delay precision is 10ms to avoid unnecessary timer scheduling cycles.
   *
   * Internal buffer has default capacity 16. You can set buffer size by calling `addAttributes(inputBuffer)`
   *
   * '''Emits when''' there is a pending element in the buffer and configured time for this element elapsed
   *  * EmitEarly - strategy do not wait to emit element if buffer is full
   *
   * '''Backpressures when''' depending on OverflowStrategy
   *  * Backpressure - backpressures when buffer is full
   *  * DropHead, DropTail, DropBuffer - never backpressures
   *  * Fail - fails the stream if buffer gets full
   *
   * '''Completes when''' upstream completes and buffered elements have been drained
   *
   * '''Cancels when''' downstream cancels
   *
   * @param delayStrategySupplier creates new [[DelayStrategy]] object for each materialization
   * @param overFlowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def delayWith(
      delayStrategySupplier: Supplier[DelayStrategy[Out]],
      overFlowStrategy: DelayOverflowStrategy): SubSource[Out, Mat] =
    new SubSource(delegate.delayWith(() => DelayStrategy.asScala(delayStrategySupplier.get), overFlowStrategy))

  /**
   * Recover allows to send last element on failure and gracefully complete the stream
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * '''Emits when''' element is available from the upstream or upstream is failed and pf returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or upstream failed with exception pf can handle
   *
   * '''Cancels when''' downstream cancels
   */
  def recover(pf: PartialFunction[Throwable, Out]): SubSource[Out, Mat] =
    new SubSource(delegate.recover(pf))

  /**
   * RecoverWith allows to switch to alternative Source on flow failure. It will stay in effect after
   * a failure has been recovered so that each time there is a failure it is fed into the `pf` and a new
   * Source may be materialized.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * '''Emits when''' element is available from the upstream or upstream is failed and element is available
   * from alternative Source
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or upstream failed with exception pf can handle
   *
   * '''Cancels when''' downstream cancels
   */
  def recoverWith(pf: PartialFunction[Throwable, Graph[SourceShape[Out], NotUsed]]): SubSource[Out, Mat] =
    new SubSource(delegate.recoverWith(pf))

  /**
   * RecoverWithRetries allows to switch to alternative Source on flow failure. It will stay in effect after
   * a failure has been recovered up to `attempts` number of times so that each time there is a failure
   * it is fed into the `pf` and a new Source may be materialized. Note that if you pass in 0, this won't
   * attempt to recover at all.
   *
   * A negative `attempts` number is interpreted as "infinite", which results in the exact same behavior as `recoverWith`.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * '''Emits when''' element is available from the upstream or upstream is failed and element is available
   * from alternative Source
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or upstream failed with exception pf can handle
   *
   * '''Cancels when''' downstream cancels
   */
  def recoverWithRetries(
      attempts: Int,
      pf: PartialFunction[Throwable, Graph[SourceShape[Out], NotUsed]]): SubSource[Out, Mat] =
    new SubSource(delegate.recoverWithRetries(attempts, pf))

  /**
   * onErrorComplete allows to complete the stream when an upstream error occurs.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * '''Emits when''' element is available from the upstream
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or failed with exception is an instance of the provided type
   *
   * '''Cancels when''' downstream cancels
   *  @since 1.1.0
   */
  def onErrorComplete(): SubSource[Out, Mat] = onErrorComplete(classOf[Throwable])

  /**
   * onErrorComplete allows to complete the stream when an upstream error occurs.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * '''Emits when''' element is available from the upstream
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or failed with exception is an instance of the provided type
   *
   * '''Cancels when''' downstream cancels
   *  @since 1.1.0
   */
  def onErrorComplete(clazz: Class[_ <: Throwable]): SubSource[Out, Mat] = onErrorComplete(ex => clazz.isInstance(ex))

  /**
   * onErrorComplete allows to complete the stream when an upstream error occurs.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * '''Emits when''' element is available from the upstream
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or failed with predicate return ture
   *
   * '''Cancels when''' downstream cancels
   *  @since 1.1.0
   */
  def onErrorComplete(predicate: java.util.function.Predicate[_ >: Throwable]): SubSource[Out, Mat] =
    new SubSource(delegate.onErrorComplete {
      case ex: Throwable if predicate.test(ex) => true
    })

  /**
   * While similar to [[recover]] this operator can be used to transform an error signal to a different one *without* logging
   * it as an error in the process. So in that sense it is NOT exactly equivalent to `recover(t => throw t2)` since recover
   * would log the `t2` error.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * Similarly to [[recover]] throwing an exception inside `mapError` _will_ be logged.
   *
   * '''Emits when''' element is available from the upstream or upstream is failed and pf returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or upstream failed with exception pf can handle
   *
   * '''Cancels when''' downstream cancels
   */
  def mapError(pf: PartialFunction[Throwable, Throwable]): SubSource[Out, Mat] =
    new SubSource(delegate.mapError(pf))

  /**
   * While similar to [[recover]] this operator can be used to transform an error signal to a different one *without* logging
   * it as an error in the process. So in that sense it is NOT exactly equivalent to `recover(t => throw t2)` since recover
   * would log the `t2` error.
   *
   * Since the underlying failure signal onError arrives out-of-band, it might jump over existing elements.
   * This operator can recover the failure signal, but not the skipped elements, which will be dropped.
   *
   * Similarly to [[recover]] throwing an exception inside `mapError` _will_ be logged.
   *
   * '''Emits when''' element is available from the upstream or upstream is failed and pf returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or upstream failed with exception pf can handle
   *
   * '''Cancels when''' downstream cancels
   */
  def mapError[E <: Throwable](clazz: Class[E], f: function.Function[E, Throwable]): javadsl.SubSource[Out, Mat] =
    mapError {
      case err if clazz.isInstance(err) => f(clazz.cast(err))
    }

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * number of elements. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * The stream will be completed without producing any elements if `n` is zero
   * or negative.
   *
   * '''Emits when''' the specified number of elements to take has not yet been reached
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' the defined number of elements has been taken or upstream completes
   *
   * '''Cancels when''' the defined number of elements has been taken or downstream cancels
   */
  def take(n: Long): SubSource[Out, Mat] =
    new SubSource(delegate.take(n))

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * duration. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * Note that this can be combined with [[#take]] to limit the number of elements
   * within the duration.
   *
   * '''Emits when''' an upstream element arrives
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or timer fires
   *
   * '''Cancels when''' downstream cancels or timer fires
   */
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "Akka 2.5.12")
  def takeWithin(duration: FiniteDuration): SubSource[Out, Mat] =
    new SubSource(delegate.takeWithin(duration))

  /**
   * Terminate processing (and cancel the upstream publisher) after the given
   * duration. Due to input buffering some elements may have been
   * requested from upstream publishers that will then not be processed downstream
   * of this step.
   *
   * Note that this can be combined with [[#take]] to limit the number of elements
   * within the duration.
   *
   * '''Emits when''' an upstream element arrives
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or timer fires
   *
   * '''Cancels when''' downstream cancels or timer fires
   */
  @nowarn("msg=deprecated")
  def takeWithin(duration: java.time.Duration): SubSource[Out, Mat] =
    takeWithin(duration.asScala)

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by conflating elements into a summary
   * until the subscriber is ready to accept them. For example a conflate step might average incoming numbers if the
   * upstream publisher is faster.
   *
   * This version of conflate allows to derive a seed from the first element and change the aggregated type to be
   * different than the input type. See [[Flow.conflate]] for a simpler version that does not change types.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' downstream stops backpressuring and there is a conflated element available
   *
   * '''Backpressures when''' never
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * see also [[SubSource.conflate]] [[SubSource.batch]] [[SubSource.batchWeighted]]
   *
   * @param seed Provides the first state for a conflated value using the first unconsumed element as a start
   * @param aggregate Takes the currently aggregated value and the current pending element to produce a new aggregate
   */
  def conflateWithSeed[S](
      seed: function.Function[Out, S],
      aggregate: function.Function2[S, Out, S]): SubSource[S, Mat] =
    new SubSource(delegate.conflateWithSeed(seed.apply)(aggregate.apply))

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by conflating elements into a summary
   * until the subscriber is ready to accept them. For example a conflate step might average incoming numbers if the
   * upstream publisher is faster.
   *
   * This version of conflate does not change the output type of the stream. See [[SubSource.conflateWithSeed]] for a
   * more flexible version that can take a seed function and transform elements while rolling up.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' downstream stops backpressuring and there is a conflated element available
   *
   * '''Backpressures when''' never
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * see also [[SubSource.conflateWithSeed]] [[SubSource.batch]] [[SubSource.batchWeighted]]
   *
   * @param aggregate Takes the currently aggregated value and the current pending element to produce a new aggregate
   */
  def conflate(aggregate: function.Function2[Out, Out, Out]): SubSource[Out, Mat] =
    new SubSource(delegate.conflate(aggregate.apply))

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by aggregating elements into batches
   * until the subscriber is ready to accept them. For example a batch step might store received elements in
   * an array up to the allowed max limit if the upstream publisher is faster.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' downstream stops backpressuring and there is an aggregated element available
   *
   * '''Backpressures when''' there are `max` batched elements and 1 pending element and downstream backpressures
   *
   * '''Completes when''' upstream completes and there is no batched/pending element waiting
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[SubSource.conflate]], [[SubSource.batchWeighted]]
   *
   * @param max maximum number of elements to batch before backpressuring upstream (must be positive non-zero)
   * @param seed Provides the first state for a batched value using the first unconsumed element as a start
   * @param aggregate Takes the currently batched value and the current pending element to produce a new aggregate
   */
  def batch[S](
      max: Long,
      seed: function.Function[Out, S],
      aggregate: function.Function2[S, Out, S]): SubSource[S, Mat] =
    new SubSource(delegate.batch(max, seed.apply)(aggregate.apply))

  /**
   * Allows a faster upstream to progress independently of a slower subscriber by aggregating elements into batches
   * until the subscriber is ready to accept them. For example a batch step might concatenate `ByteString`
   * elements up to the allowed max limit if the upstream publisher is faster.
   *
   * This element only rolls up elements if the upstream is faster, but if the downstream is faster it will not
   * duplicate elements.
   *
   * Batching will apply for all elements, even if a single element cost is greater than the total allowed limit.
   * In this case, previous batched elements will be emitted, then the "heavy" element will be emitted (after
   * being applied with the `seed` function) without batching further elements with it, and then the rest of the
   * incoming elements are batched.
   *
   * '''Emits when''' downstream stops backpressuring and there is a batched element available
   *
   * '''Backpressures when''' there are `max` weighted batched elements + 1 pending element and downstream backpressures
   *
   * '''Completes when''' upstream completes and there is no batched/pending element waiting
   *
   * '''Cancels when''' downstream cancels
   *
   * See also [[SubSource.conflate]], [[SubSource.batch]]
   *
   * @param max maximum weight of elements to batch before backpressuring upstream (must be positive non-zero)
   * @param costFn a function to compute a single element weight
   * @param seed Provides the first state for a batched value using the first unconsumed element as a start
   * @param aggregate Takes the currently batched value and the current pending element to produce a new batch
   */
  def batchWeighted[S](
      max: Long,
      costFn: function.Function[Out, java.lang.Long],
      seed: function.Function[Out, S],
      aggregate: function.Function2[S, Out, S]): SubSource[S, Mat] =
    new SubSource(delegate.batchWeighted(max, costFn.apply, seed.apply)(aggregate.apply))

  /**
   * Allows a faster downstream to progress independently of a slower upstream by extrapolating elements from an older
   * element until new element comes from the upstream. For example an expand step might repeat the last element for
   * the subscriber until it receives an update from upstream.
   *
   * This element will never "drop" upstream elements as all elements go through at least one extrapolation step.
   * This means that if the upstream is actually faster than the upstream it will be backpressured by the downstream
   * subscriber.
   *
   * Expand does not support [[pekko.stream.Supervision#restart]] and [[pekko.stream.Supervision#resume]].
   * Exceptions from the `expander` function will complete the stream with failure.
   *
   * See also [[#extrapolate]] for a version that always preserves the original element and allows for an initial "startup" element.
   *
   * '''Emits when''' downstream stops backpressuring
   *
   * '''Backpressures when''' downstream backpressures or iterator runs empty
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * @param expander       Takes the current extrapolation state to produce an output element and the next extrapolation
   *                       state.
   * @see [[#extrapolate]]
   */
  def expand[U](expander: function.Function[Out, java.util.Iterator[U]]): SubSource[U, Mat] =
    new SubSource(delegate.expand(in => expander(in).asScala))

  /**
   * Allows a faster downstream to progress independent of a slower upstream.
   *
   * This is achieved by introducing "extrapolated" elements - based on those from upstream - whenever downstream
   * signals demand.
   *
   * Extrapolate does not support [[pekko.stream.Supervision#restart]] and [[pekko.stream.Supervision#resume]].
   * Exceptions from the `extrapolate` function will complete the stream with failure.
   *
   * See also [[#expand]] for a version that can overwrite the original element.
   *
   * '''Emits when''' downstream stops backpressuring, AND EITHER upstream emits OR initial element is present OR
   * `extrapolate` is non-empty and applicable
   *
   * '''Backpressures when''' downstream backpressures or current `extrapolate` runs empty
   *
   * '''Completes when''' upstream completes and current `extrapolate` runs empty
   *
   * '''Cancels when''' downstream cancels
   *
   * @param extrapolator takes the current upstream element and provides a sequence of "extrapolated" elements based
   *                    on the original, to be emitted in case downstream signals demand.
   * @see [[#expand]]
   */
  def extrapolate(extrapolator: function.Function[Out @uncheckedVariance, java.util.Iterator[Out @uncheckedVariance]])
      : SubSource[Out, Mat] =
    new SubSource(delegate.extrapolate(in => extrapolator(in).asScala))

  /**
   * Allows a faster downstream to progress independent of a slower upstream.
   *
   * This is achieved by introducing "extrapolated" elements - based on those from upstream - whenever downstream
   * signals demand.
   *
   * Extrapolate does not support [[pekko.stream.Supervision#restart]] and [[pekko.stream.Supervision#resume]].
   * Exceptions from the `extrapolate` function will complete the stream with failure.
   *
   * See also [[#expand]] for a version that can overwrite the original element.
   *
   * '''Emits when''' downstream stops backpressuring, AND EITHER upstream emits OR initial element is present OR
   * `extrapolate` is non-empty and applicable
   *
   * '''Backpressures when''' downstream backpressures or current `extrapolate` runs empty
   *
   * '''Completes when''' upstream completes and current `extrapolate` runs empty
   *
   * '''Cancels when''' downstream cancels
   *
   * @param extrapolator takes the current upstream element and provides a sequence of "extrapolated" elements based
   *                    on the original, to be emitted in case downstream signals demand.
   * @param initial the initial element to be emitted, in case upstream is able to stall the entire stream.
   * @see [[#expand]]
   */
  def extrapolate(
      extrapolator: function.Function[Out @uncheckedVariance, java.util.Iterator[Out @uncheckedVariance]],
      initial: Out @uncheckedVariance): SubSource[Out, Mat] =
    new SubSource(delegate.extrapolate(in => extrapolator(in).asScala, Some(initial)))

  /**
   * Adds a fixed size buffer in the flow that allows to store elements from a faster upstream until it becomes full.
   * Depending on the defined [[pekko.stream.OverflowStrategy]] it might drop elements or backpressure the upstream if
   * there is no space available
   *
   * '''Emits when''' downstream stops backpressuring and there is a pending element in the buffer
   *
   * '''Backpressures when''' downstream backpressures or depending on OverflowStrategy:
   *  <ul>
   *    <li>Backpressure - backpressures when buffer is full</li>
   *    <li>DropHead, DropTail, DropBuffer - never backpressures</li>
   *    <li>Fail - fails the stream if buffer gets full</li>
   *  </ul>
   *
   * '''Completes when''' upstream completes and buffered elements has been drained
   *
   * '''Cancels when''' downstream cancels
   *
   * @param size The size of the buffer in element count
   * @param overflowStrategy Strategy that is used when incoming elements cannot fit inside the buffer
   */
  def buffer(size: Int, overflowStrategy: OverflowStrategy): SubSource[Out, Mat] =
    new SubSource(delegate.buffer(size, overflowStrategy))

  /**
   * Takes up to `n` elements from the stream (less than `n` only if the upstream completes before emitting `n` elements)
   * and returns a pair containing a strict sequence of the taken element
   * and a stream representing the remaining elements. If ''n'' is zero or negative, then this will return a pair
   * of an empty collection and a stream containing the whole upstream unchanged.
   *
   * In case of an upstream error, depending on the current state
   *  - the master stream signals the error if less than `n` elements has been seen, and therefore the substream
   *    has not yet been emitted
   *  - the tail substream signals the error after the prefix and tail has been emitted by the main stream
   *    (at that point the main stream has already completed)
   *
   * '''Emits when''' the configured number of prefix elements are available. Emits this prefix, and the rest
   * as a substream
   *
   * '''Backpressures when''' downstream backpressures or substream backpressures
   *
   * '''Completes when''' prefix elements has been consumed and substream has been consumed
   *
   * '''Cancels when''' downstream cancels or substream cancels
   */
  def prefixAndTail(n: Int): SubSource[
    pekko.japi.Pair[java.util.List[Out @uncheckedVariance], javadsl.Source[Out @uncheckedVariance, NotUsed]], Mat] =
    new SubSource(delegate.prefixAndTail(n).map { case (taken, tail) => pekko.japi.Pair(taken.asJava, tail.asJava) })

  /**
   * Takes up to `n` elements from the stream (less than `n` only if the upstream completes before emitting `n` elements),
   * then apply `f` on these elements in order to obtain a flow, this flow is then materialized and the rest of the input is processed by this flow (similar to via).
   * This method returns a flow consuming the rest of the stream producing the materialized flow's output.
   *
   * '''Emits when''' the materialized flow emits.
   *  Notice the first `n` elements are buffered internally before materializing the flow and connecting it to the rest of the upstream - producing elements at its own discretion (might 'swallow' or multiply elements).
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' the materialized flow completes.
   *  If upstream completes before producing `n` elements, `f` will be applied with the provided elements,
   *  the resulting flow will be materialized and signalled for upstream completion, it can then complete or continue to emit elements at its own discretion.
   *
   * '''Cancels when''' the materialized flow cancels.
   *  Notice that when downstream cancels prior to prefix completion, the cancellation cause is stashed until prefix completion (or upstream completion) and then handed to the materialized flow.
   *
   *  @param n the number of elements to accumulate before materializing the downstream flow.
   *  @param f a function that produces the downstream flow based on the upstream's prefix.
   */
  def flatMapPrefix[Out2, Mat2](
      n: Int,
      f: function.Function[java.util.List[Out], javadsl.Flow[Out, Out2, Mat2]]): javadsl.SubSource[Out2, Mat] = {
    val newDelegate = delegate.flatMapPrefix(n)(seq => f(seq.asJava).asScala)
    new javadsl.SubSource(newDelegate)
  }

  /**
   * Transform each input element into a `Source` of output elements that is
   * then flattened into the output stream by concatenation,
   * fully consuming one Source after the other.
   *
   * '''Emits when''' a currently consumed substream has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes and all consumed substreams complete
   *
   * '''Cancels when''' downstream cancels
   */
  def flatMapConcat[T, M](f: function.Function[Out, _ <: Graph[SourceShape[T], M]]): SubSource[T, Mat] =
    new SubSource(delegate.flatMapConcat(x => f(x)))

  /**
   * Transform each input element into a `Source` of output elements that is
   * then flattened into the output stream by concatenation,
   * fully consuming one Source after the other.
   * `parallelism` can be used to config the max inflight sources, which will be materialized at the same time.
   *
   * '''Emits when''' a currently consumed substream has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes and all consumed substreams complete
   *
   * '''Cancels when''' downstream cancels
   * @since 1.2.0
   */
  def flatMapConcat[T, M](
      parallelism: Int,
      f: function.Function[Out, _ <: Graph[SourceShape[T], M]]): SubSource[T, Mat] =
    new SubSource(delegate.flatMapConcat(parallelism, x => f(x)))

  /**
   * Transform each input element into a `Source` of output elements that is
   * then flattened into the output stream by merging, where at most `breadth`
   * substreams are being consumed at any given time.
   *
   * '''Emits when''' a currently consumed substream has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes and all consumed substreams complete
   *
   * '''Cancels when''' downstream cancels
   */
  def flatMapMerge[T, M](breadth: Int, f: function.Function[Out, _ <: Graph[SourceShape[T], M]]): SubSource[T, Mat] =
    new SubSource(delegate.flatMapMerge(breadth, o => f(o)))

  /**
   * Transforms each input element into a `Source` of output elements that is
   * then flattened into the output stream until a new input element is received
   * at which point the current (now previous) substream is cancelled (which is why
   * this operator is sometimes also called "flatMapLatest").
   *
   * '''Emits when''' the current substream has an element available
   *
   * '''Backpressures when''' never
   *
   * '''Completes when''' upstream completes and the current substream completes
   *
   * '''Cancels when''' downstream cancels
   *
   * @since 1.2.0
   */
  def switchMap[T, M](f: function.Function[Out, _ <: Graph[SourceShape[T], M]]): SubSource[T, Mat] =
    new SubSource(delegate.switchMap(o => f(o)))

  /**
   * Concatenate the given [[Source]] to this [[Flow]], meaning that once this
   * Flow’s input is exhausted and all result elements have been generated,
   * the Source’s elements will be produced.
   *
   * Note that the [[Source]] is materialized together with this Flow and is "detached" meaning it will
   * in effect behave as a one element buffer in front of both the sources, that eagerly demands an element on start
   * (so it can not be combined with `Source.lazy` to defer materialization of `that`).
   *
   * The second source is then kept from producing elements by asserting back-pressure until its time comes.
   *
   * When needing a concat operator that is not detached use [[#concatLazyMat]]
   *
   * '''Emits when''' element is available from current stream or from the given [[Source]] when current is completed
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' given [[Source]] completes
   *
   * '''Cancels when''' downstream cancels
   */
  def concat[M](that: Graph[SourceShape[Out], M]): SubSource[Out, Mat] =
    new SubSource(delegate.concat(that))

  /**
   * Concatenate the given [[Source]] to this [[Flow]], meaning that once this
   * Flow’s input is exhausted and all result elements have been generated,
   * the Source’s elements will be produced.
   *
   * Note that the [[Source]] is materialized together with this Flow. If `lazy` materialization is what is needed
   * the operator can be combined with for example `Source.lazySource` to defer materialization of `that` until the
   * time when this source completes.
   *
   * The second source is then kept from producing elements by asserting back-pressure until its time comes.
   *
   * For a concat operator that is detached, use [[#concat]]
   *
   * If this [[Flow]] gets upstream error - no elements from the given [[Source]] will be pulled.
   *
   * '''Emits when''' element is available from current stream or from the given [[Source]] when current is completed
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' given [[Source]] completes
   *
   * '''Cancels when''' downstream cancels
   */
  def concatLazy[M](that: Graph[SourceShape[Out], M]): SubSource[Out, Mat] =
    new SubSource(delegate.concatLazy(that))

  /**
   * Concatenate the given [[Source]]s to this [[Source]], meaning that once this
   * Flow’s input is exhausted and all result elements have been generated,
   * the Source’s elements will be produced.
   *
   * Note that the [[Source]]s is materialized together with this Flow. If `lazy` materialization is what is needed
   * the operator can be combined with for example `Source.lazySource` to defer materialization of `that` until the
   * time when this source completes.
   *
   * The second source is then kept from producing elements by asserting back-pressure until its time comes.
   *
   * For a concat operator that is detached, use [[#concat]]
   *
   * If this [[Source]] gets upstream error - no elements from the given [[Source]]s will be pulled.
   *
   * '''Emits when''' element is available from current stream or from the given [[Source]]s when current is completed
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' all the given [[Source]]s completes
   *
   * '''Cancels when''' downstream cancels
   */
  @varargs
  @SafeVarargs
  def concatAllLazy(those: Graph[SourceShape[Out], _]*): SubSource[Out, Mat] =
    new SubSource(delegate.concatAllLazy(those: _*))

  /**
   * Prepend the given [[Source]] to this [[Flow]], meaning that before elements
   * are generated from this Flow, the Source's elements will be produced until it
   * is exhausted, at which point Flow elements will start being produced.
   *
   * Note that the [[Source]] is materialized together with this Flow and is "detached" meaning
   * in effect behave as a one element buffer in front of both the sources, that eagerly demands an element on start
   * (so it can not be combined with `Source.lazy` to defer materialization of `that`).
   *
   * This flow will then be kept from producing elements by asserting back-pressure until its time comes.
   *
   * When needing a prepend operator that is not detached use [[#prependLazy]]
   *
   * '''Emits when''' element is available from the given [[Source]] or from current stream when the [[Source]] is completed
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' this [[Flow]] completes
   *
   * '''Cancels when''' downstream cancels
   */
  def prepend[M](that: Graph[SourceShape[Out], M]): SubSource[Out, Mat] =
    new SubSource(delegate.prepend(that))

  /**
   * Prepend the given [[Source]] to this [[Flow]], meaning that before elements
   * are generated from this Flow, the Source's elements will be produced until it
   * is exhausted, at which point Flow elements will start being produced.
   *
   * Note that the [[Source]] is materialized together with this Flow and will then be kept from producing elements
   * by asserting back-pressure until its time comes.
   *
   * When needing a prepend operator that is also detached use [[#prepend]]
   *
   * If the given [[Source]] gets upstream error - no elements from this [[Flow]] will be pulled.
   *
   * '''Emits when''' element is available from the given [[Source]] or from current stream when the [[Source]] is completed
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' this [[Flow]] completes
   *
   * '''Cancels when''' downstream cancels
   */
  def prependLazy[M](that: Graph[SourceShape[Out], M]): SubSource[Out, Mat] =
    new SubSource(delegate.prependLazy(that))

  /**
   * Provides a secondary source that will be consumed if this source completes without any
   * elements passing by. As soon as the first element comes through this stream, the alternative
   * will be cancelled.
   *
   * Note that this Flow will be materialized together with the [[Source]] and just kept
   * from producing elements by asserting back-pressure until its time comes or it gets
   * cancelled.
   *
   * On errors the operator is failed regardless of source of the error.
   *
   * '''Emits when''' element is available from first stream or first stream closed without emitting any elements and an element
   *                  is available from the second stream
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' the primary stream completes after emitting at least one element, when the primary stream completes
   *                      without emitting and the secondary stream already has completed or when the secondary stream completes
   *
   * '''Cancels when''' downstream cancels and additionally the alternative is cancelled as soon as an element passes
   *                    by from this stream.
   */
  def orElse[M](secondary: Graph[SourceShape[Out], M]): javadsl.SubSource[Out, Mat] =
    new SubSource(delegate.orElse(secondary))

  /**
   * Attaches the given [[Sink]] to this [[Source]], meaning that elements that passes
   * through will also be sent to the [[Sink]].
   *
   * It is similar to [[#wireTap]] but will backpressure instead of dropping elements when the given [[Sink]] is not ready.
   *
   * '''Emits when''' element is available and demand exists both from the Sink and the downstream.
   *
   * '''Backpressures when''' downstream or Sink backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream or Sink cancels
   */
  def alsoTo(that: Graph[SinkShape[Out], _]): SubSource[Out, Mat] =
    new SubSource(delegate.alsoTo(that))

  /**
   * Attaches the given [[Sink]]s to this [[Source]], meaning that elements that passes
   * through will also be sent to all those [[Sink]]s.
   *
   * It is similar to [[#wireTap]] but will backpressure instead of dropping elements when the given [[Sink]]s is not ready.
   *
   * '''Emits when''' element is available and demand exists both from the Sinks and the downstream.
   *
   * '''Backpressures when''' downstream or any of the [[Sink]]s backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream or any of the [[Sink]]s cancels
   */
  @varargs
  @SafeVarargs
  def alsoToAll(those: Graph[SinkShape[Out], _]*): SubSource[Out, Mat] =
    new SubSource(delegate.alsoToAll(those: _*))

  /**
   * Attaches the given [[Sink]] to this [[Flow]], meaning that elements will be sent to the [[Sink]]
   * instead of being passed through if the predicate `when` returns `true`.
   *
   * '''Emits when''' emits when an element is available from the input and the chosen output has demand
   *
   * '''Backpressures when''' the currently chosen output back-pressures
   *
   * '''Completes when''' upstream completes and no output is pending
   *
   * '''Cancels when''' any of the downstreams cancel
   */
  def divertTo(that: Graph[SinkShape[Out], _], when: function.Predicate[Out]): SubSource[Out, Mat] =
    new SubSource(delegate.divertTo(that, when.test))

  /**
   * Attaches the given [[Sink]] to this [[Flow]] as a wire tap, meaning that elements that pass
   * through will also be sent to the wire-tap Sink, without the latter affecting the mainline flow.
   * If the wire-tap Sink backpressures, elements that would've been sent to it will be dropped instead.
   *
   * It is similar to [[#alsoTo]] which does backpressure instead of dropping elements.
   *
   * '''Emits when''' element is available and demand exists from the downstream; the element will
   * also be sent to the wire-tap Sink if there is demand.
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def wireTap(that: Graph[SinkShape[Out], _]): SubSource[Out, Mat] =
    new SubSource(delegate.wireTap(that))

  /**
   * Merge the given [[Source]] to this [[Flow]], taking elements as they arrive from input streams,
   * picking randomly when several elements ready.
   *
   * '''Emits when''' one of the inputs has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' all upstreams complete
   *
   * '''Cancels when''' downstream cancels
   */
  def merge(that: Graph[SourceShape[Out], _]): SubSource[Out, Mat] =
    new SubSource(delegate.merge(that))

  /**
   * Merge the given [[Source]]s to the current one, taking elements as they arrive from input streams,
   * picking randomly when several elements ready.
   *
   * '''Emits when''' one of the inputs has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' all upstreams complete (eagerComplete=false) or one upstream completes (eagerComplete=true), default value is `false`
   *
   * '''Cancels when''' downstream cancels
   */
  def mergeAll(
      those: java.util.List[_ <: Graph[SourceShape[Out], _ <: Any]],
      eagerComplete: Boolean): SubSource[Out, Mat] = {
    val seq = if (those != null) CollectionUtil.toSeq(those).collect {
      case source: Source[Out @unchecked, _] => source.asScala
      case other                             => other
    }
    else immutable.Seq()
    new SubSource(delegate.mergeAll(seq, eagerComplete))
  }

  /**
   * Interleave is a deterministic merge of the given [[Source]] with elements of this [[Source]].
   * It first emits `segmentSize` number of elements from this flow to downstream, then - same amount for `that` source,
   * then repeat process.
   *
   * Example:
   * {{{
   * Source.from(Arrays.asList(1, 2, 3)).interleave(Source.from(Arrays.asList(4, 5, 6, 7), 2)
   * // 1, 2, 4, 5, 3, 6, 7
   * }}}
   *
   * After one of sources is complete than all the rest elements will be emitted from the second one
   *
   * If one of sources gets upstream error - stream completes with failure.
   *
   * '''Emits when''' element is available from the currently consumed upstream
   *
   * '''Backpressures when''' downstream backpressures. Signal to current
   * upstream, switch to next upstream when received `segmentSize` elements
   *
   * '''Completes when''' this [[Source]] and given one completes
   *
   * '''Cancels when''' downstream cancels
   */
  def interleave(that: Graph[SourceShape[Out], _], segmentSize: Int): SubSource[Out, Mat] =
    new SubSource(delegate.interleave(that, segmentSize))

  /**
   * Interleave is a deterministic merge of the given [[Source]] with elements of this [[Flow]].
   * It first emits `segmentSize` number of elements from this flow to downstream, then - same amount for `that` source,
   * then repeat process.
   *
   * If eagerClose is false and one of the upstreams complete the elements from the other upstream will continue passing
   * through the interleave operator. If eagerClose is true and one of the upstream complete interleave will cancel the
   * other upstream and complete itself.
   *
   * If this [[Flow]] or [[Source]] gets upstream error - stream completes with failure.
   *
   * '''Emits when''' element is available from the currently consumed upstream
   *
   * '''Backpressures when''' downstream backpressures. Signal to current
   * upstream, switch to next upstream when received `segmentSize` elements
   *
   * '''Completes when''' the [[Flow]] and given [[Source]] completes
   *
   * '''Cancels when''' downstream cancels
   */
  def interleaveAll(
      those: java.util.List[_ <: Graph[SourceShape[Out], _ <: Any]],
      segmentSize: Int,
      eagerClose: Boolean): SubSource[Out, Mat] = {
    val seq = if (those != null) CollectionUtil.toSeq(those).collect {
      case source: Source[Out @unchecked, _] => source.asScala
      case other                             => other
    }
    else immutable.Seq()
    new SubSource(delegate.interleaveAll(seq, segmentSize, eagerClose))
  }

  /**
   * MergeLatest joins elements from N input streams into stream of lists of size N.
   * i-th element in list is the latest emitted element from i-th input stream.
   * MergeLatest emits list for each element emitted from some input stream,
   * but only after each input stream emitted at least one element.
   *
   * '''Emits when''' an element is available from some input and each input emits at least one element from stream start
   *
   * '''Completes when''' all upstreams complete (eagerClose=false) or one upstream completes (eagerClose=true)
   */
  def mergeLatest[M](
      that: Graph[SourceShape[Out], M],
      eagerComplete: Boolean): javadsl.SubSource[java.util.List[Out], Mat] =
    new SubSource(delegate.mergeLatest(that, eagerComplete).map(_.asJava))

  /**
   * Merge two sources. Prefer one source if both sources have elements ready.
   *
   * '''emits''' when one of the inputs has an element available. If multiple have elements available, prefer the 'right' one when 'preferred' is 'true', or the 'left' one when 'preferred' is 'false'.
   *
   * '''backpressures''' when downstream backpressures
   *
   * '''completes''' when all upstreams complete (This behavior is changeable to completing when any upstream completes by setting `eagerComplete=true`.)
   */
  def mergePreferred[M](
      that: Graph[SourceShape[Out], M],
      preferred: Boolean,
      eagerComplete: Boolean): javadsl.SubSource[Out, Mat] =
    new SubSource(delegate.mergePreferred(that, preferred, eagerComplete))

  /**
   * Merge two sources. Prefer the sources depending on the 'priority' parameters.
   *
   * '''emits''' when one of the inputs has an element available, preferring inputs based on the 'priority' parameters if both have elements available
   *
   * '''backpressures''' when downstream backpressures
   *
   * '''completes''' when both upstreams complete (This behavior is changeable to completing when any upstream completes by setting `eagerComplete=true`.)
   */
  def mergePrioritized[M](
      that: Graph[SourceShape[Out], M],
      leftPriority: Int,
      rightPriority: Int,
      eagerComplete: Boolean): javadsl.SubSource[Out, Mat] =
    new SubSource(delegate.mergePrioritized(that, leftPriority, rightPriority, eagerComplete))

  /**
   * Merge the given [[Source]] to this [[Source]], taking elements as they arrive from input streams,
   * picking always the smallest of the available elements (waiting for one element from each side
   * to be available). This means that possible contiguity of the input streams is not exploited to avoid
   * waiting for elements, this merge will block when one of the inputs does not have more elements (and
   * does not complete).
   *
   * '''Emits when''' all of the inputs have an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' all upstreams complete
   *
   * '''Cancels when''' downstream cancels
   */
  def mergeSorted[M](that: Graph[SourceShape[Out], M], comp: Comparator[Out]): javadsl.SubSource[Out, Mat] =
    new SubSource(delegate.mergeSorted(that)(Ordering.comparatorToOrdering(comp)))

  /**
   * Combine the elements of current [[Flow]] and the given [[Source]] into a stream of tuples.
   *
   * '''Emits when''' all of the inputs has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' any upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zip[T](source: Graph[SourceShape[T], _]): SubSource[pekko.japi.Pair[Out @uncheckedVariance, T], Mat] =
    new SubSource(delegate.zip(source).map { case (o, t) => pekko.japi.Pair.create(o, t) })

  /**
   * Combine the elements of current flow and the given [[Source]] into a stream of tuples.
   *
   * '''Emits when''' at first emits when both inputs emit, and then as long as any input emits (coupled to the default value of the completed input).
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' all upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zipAll[U, A >: Out](
      that: Graph[SourceShape[U], _],
      thisElem: A,
      thatElem: U): SubSource[pekko.japi.Pair[A, U], Mat] =
    new SubSource(delegate.zipAll(that, thisElem, thatElem).map { case (a, u) => Pair.create(a, u) })

  /**
   * Combine the elements of current [[Flow]] and the given [[Source]] into a stream of tuples, picking always the latest element of each.
   *
   * '''Emits when''' all of the inputs have at least an element available, and then each time an element becomes
   *                  available on either of the inputs
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' any upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zipLatest[T](source: Graph[SourceShape[T], _]): SubSource[pekko.japi.Pair[Out @uncheckedVariance, T], Mat] =
    new SubSource(delegate.zipLatest(source).map { case (o, t) => pekko.japi.Pair.create(o, t) })

  /**
   * Put together the elements of current [[Flow]] and the given [[Source]]
   * into a stream of combined elements using a combiner function.
   *
   * '''Emits when''' all of the inputs has an element available
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' any upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zipWith[Out2, Out3](
      that: Graph[SourceShape[Out2], _],
      combine: function.Function2[Out, Out2, Out3]): SubSource[Out3, Mat] =
    new SubSource(delegate.zipWith[Out2, Out3](that)(combinerToScala(combine)))

  /**
   * Put together the elements of current [[Flow]] and the given [[Source]]
   * into a stream of combined elements using a combiner function, picking always the latest element of each.
   *
   * '''Emits when''' all of the inputs have at least an element available, and then each time an element becomes
   *                  available on either of the inputs
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' any upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zipLatestWith[Out2, Out3](
      that: Graph[SourceShape[Out2], _],
      combine: function.Function2[Out, Out2, Out3]): SubSource[Out3, Mat] =
    new SubSource(delegate.zipLatestWith[Out2, Out3](that)(combinerToScala(combine)))

  /**
   * Combine the elements of current [[Source]] into a stream of tuples consisting
   * of all elements paired with their index. Indices start at 0.
   *
   * '''Emits when''' upstream emits an element and is paired with their index
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def zipWithIndex: javadsl.SubSource[pekko.japi.Pair[Out @uncheckedVariance, java.lang.Long], Mat] =
    new SubSource(delegate.via(
      ZipWithIndexJava.asInstanceOf[Graph[FlowShape[Out, Pair[Out, java.lang.Long]], NotUsed]]))

  /**
   * If the first element has not passed through this operator before the provided timeout, the stream is failed
   * with a [[org.apache.pekko.stream.InitialTimeoutException]].
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses before first element arrives
   *
   * '''Cancels when''' downstream cancels
   */
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "Akka 2.5.12")
  def initialTimeout(timeout: FiniteDuration): SubSource[Out, Mat] =
    new SubSource(delegate.initialTimeout(timeout))

  /**
   * If the first element has not passed through this operator before the provided timeout, the stream is failed
   * with a [[org.apache.pekko.stream.InitialTimeoutException]].
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses before first element arrives
   *
   * '''Cancels when''' downstream cancels
   */
  @nowarn("msg=deprecated")
  def initialTimeout(timeout: java.time.Duration): SubSource[Out, Mat] =
    initialTimeout(timeout.asScala)

  /**
   * If the completion of the stream does not happen until the provided timeout, the stream is failed
   * with a [[org.apache.pekko.stream.CompletionTimeoutException]].
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses before upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "Akka 2.5.12")
  def completionTimeout(timeout: FiniteDuration): SubSource[Out, Mat] =
    new SubSource(delegate.completionTimeout(timeout))

  /**
   * If the completion of the stream does not happen until the provided timeout, the stream is failed
   * with a [[org.apache.pekko.stream.CompletionTimeoutException]].
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses before upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  @nowarn("msg=deprecated")
  def completionTimeout(timeout: java.time.Duration): SubSource[Out, Mat] =
    completionTimeout(timeout.asScala)

  /**
   * If the time between two processed elements exceeds the provided timeout, the stream is failed
   * with a [[org.apache.pekko.stream.StreamIdleTimeoutException]]. The timeout is checked periodically,
   * so the resolution of the check is one period (equals to timeout value).
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses between two emitted elements
   *
   * '''Cancels when''' downstream cancels
   */
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "Akka 2.5.12")
  def idleTimeout(timeout: FiniteDuration): SubSource[Out, Mat] =
    new SubSource(delegate.idleTimeout(timeout))

  /**
   * If the time between two processed elements exceeds the provided timeout, the stream is failed
   * with a [[org.apache.pekko.stream.StreamIdleTimeoutException]]. The timeout is checked periodically,
   * so the resolution of the check is one period (equals to timeout value).
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses between two emitted elements
   *
   * '''Cancels when''' downstream cancels
   */
  @nowarn("msg=deprecated")
  def idleTimeout(timeout: java.time.Duration): SubSource[Out, Mat] =
    idleTimeout(timeout.asScala)

  /**
   * If the time between the emission of an element and the following downstream demand exceeds the provided timeout,
   * the stream is failed with a [[org.apache.pekko.stream.BackpressureTimeoutException]]. The timeout is checked periodically,
   * so the resolution of the check is one period (equals to timeout value).
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses between element emission and downstream demand.
   *
   * '''Cancels when''' downstream cancels
   */
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "Akka 2.5.12")
  def backpressureTimeout(timeout: FiniteDuration): SubSource[Out, Mat] =
    new SubSource(delegate.backpressureTimeout(timeout))

  /**
   * If the time between the emission of an element and the following downstream demand exceeds the provided timeout,
   * the stream is failed with a [[org.apache.pekko.stream.BackpressureTimeoutException]]. The timeout is checked periodically,
   * so the resolution of the check is one period (equals to timeout value).
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes or fails if timeout elapses between element emission and downstream demand.
   *
   * '''Cancels when''' downstream cancels
   */
  @nowarn("msg=deprecated")
  def backpressureTimeout(timeout: java.time.Duration): SubSource[Out, Mat] =
    backpressureTimeout(timeout.asScala)

  /**
   * Injects additional elements if upstream does not emit for a configured amount of time. In other words, this
   * operator attempts to maintains a base rate of emitted elements towards the downstream.
   *
   * If the downstream backpressures then no element is injected until downstream demand arrives. Injected elements
   * do not accumulate during this period.
   *
   * Upstream elements are always preferred over injected elements.
   *
   * '''Emits when''' upstream emits an element or if the upstream was idle for the configured period
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "Akka 2.5.12")
  def keepAlive(maxIdle: FiniteDuration, injectedElem: function.Creator[Out]): SubSource[Out, Mat] =
    new SubSource(delegate.keepAlive(maxIdle, () => injectedElem.create()))

  /**
   * Injects additional elements if upstream does not emit for a configured amount of time. In other words, this
   * operator attempts to maintains a base rate of emitted elements towards the downstream.
   *
   * If the downstream backpressures then no element is injected until downstream demand arrives. Injected elements
   * do not accumulate during this period.
   *
   * Upstream elements are always preferred over injected elements.
   *
   * '''Emits when''' upstream emits an element or if the upstream was idle for the configured period
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  @nowarn("msg=deprecated")
  def keepAlive(maxIdle: java.time.Duration, injectedElem: function.Creator[Out]): SubSource[Out, Mat] =
    keepAlive(maxIdle.asScala, injectedElem)

  /**
   * Sends elements downstream with speed limited to `elements/per`. In other words, this operator set the maximum rate
   * for emitting messages. This operator works for streams where all elements have the same cost or length.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as element costs. If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Elements that costs more than the allowed burst will be delayed proportionally
   * to their cost minus available tokens, meeting the target rate. Bucket is full when stream just materialized and
   * started.
   *
   * The burst size is calculated based on the given rate (`cost/per`) as 0.1 * rate, for example:
   * - rate < 20/second => burst size 1
   * - rate 20/second => burst size 2
   * - rate 100/second => burst size 10
   * - rate 200/second => burst size 20
   *
   * The throttle `mode` is [[pekko.stream.ThrottleMode.Shaping]], which makes pauses before emitting messages to
   * meet throttle rate.
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures or the incoming rate is higher than the speed limit
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def throttle(elements: Int, per: java.time.Duration): javadsl.SubSource[Out, Mat] =
    new SubSource(delegate.throttle(elements, per.asScala))

  /**
   * Sends elements downstream with speed limited to `elements/per`. In other words, this operator set the maximum rate
   * for emitting messages. This operator works for streams where all elements have the same cost or length.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size or maximumBurst).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as element costs If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Elements that costs more than the allowed burst will be delayed proportionally
   * to their cost minus available tokens, meeting the target rate. Bucket is full when stream just materialized and started.
   *
   * Parameter `mode` manages behavior when upstream is faster than throttle rate:
   *  - [[pekko.stream.ThrottleMode.Shaping]] makes pauses before emitting messages to meet throttle rate
   *  - [[pekko.stream.ThrottleMode.Enforcing]] fails with exception when upstream is faster than throttle rate
   *
   * It is recommended to use non-zero burst sizes as they improve both performance and throttling precision by allowing
   * the implementation to avoid using the scheduler when input rates fall below the enforced limit and to reduce
   * most of the inaccuracy caused by the scheduler resolution (which is in the range of milliseconds).
   *
   *  WARNING: Be aware that throttle is using scheduler to slow down the stream. This scheduler has minimal time of triggering
   *  next push. Consequently it will slow down the stream as it has minimal pause for emitting. This can happen in
   *  case burst is 0 and speed is higher than 30 events per second. You need to increase the `maximumBurst`  if
   *  elements arrive with small interval (30 milliseconds or less). Use the overloaded `throttle` method without
   *  `maximumBurst` parameter to automatically calculate the `maximumBurst` based on the given rate (`cost/per`).
   *  In other words the throttler always enforces the rate limit when `maximumBurst` parameter is given, but in
   *  certain cases (mostly due to limited scheduler resolution) it enforces a tighter bound than what was prescribed.
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures or the incoming rate is higher than the speed limit
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "Akka 2.5.12")
  def throttle(elements: Int, per: FiniteDuration, maximumBurst: Int, mode: ThrottleMode): javadsl.SubSource[Out, Mat] =
    new SubSource(delegate.throttle(elements, per, maximumBurst, mode))

  /**
   * Sends elements downstream with speed limited to `elements/per`. In other words, this operator set the maximum rate
   * for emitting messages. This operator works for streams where all elements have the same cost or length.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size or maximumBurst).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as element costs If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Elements that costs more than the allowed burst will be delayed proportionally
   * to their cost minus available tokens, meeting the target rate. Bucket is full when stream just materialized and started.
   *
   * Parameter `mode` manages behavior when upstream is faster than throttle rate:
   *  - [[pekko.stream.ThrottleMode.Shaping]] makes pauses before emitting messages to meet throttle rate
   *  - [[pekko.stream.ThrottleMode.Enforcing]] fails with exception when upstream is faster than throttle rate
   *
   * It is recommended to use non-zero burst sizes as they improve both performance and throttling precision by allowing
   * the implementation to avoid using the scheduler when input rates fall below the enforced limit and to reduce
   * most of the inaccuracy caused by the scheduler resolution (which is in the range of milliseconds).
   *
   *  WARNING: Be aware that throttle is using scheduler to slow down the stream. This scheduler has minimal time of triggering
   *  next push. Consequently it will slow down the stream as it has minimal pause for emitting. This can happen in
   *  case burst is 0 and speed is higher than 30 events per second. You need to increase the `maximumBurst`  if
   *  elements arrive with small interval (30 milliseconds or less). Use the overloaded `throttle` method without
   *  `maximumBurst` parameter to automatically calculate the `maximumBurst` based on the given rate (`cost/per`).
   *  In other words the throttler always enforces the rate limit when `maximumBurst` parameter is given, but in
   *  certain cases (mostly due to limited scheduler resolution) it enforces a tighter bound than what was prescribed.
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures or the incoming rate is higher than the speed limit
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def throttle(
      elements: Int,
      per: java.time.Duration,
      maximumBurst: Int,
      mode: ThrottleMode): javadsl.SubSource[Out, Mat] =
    new SubSource(delegate.throttle(elements, per.asScala, maximumBurst, mode))

  /**
   * Sends elements downstream with speed limited to `cost/per`. Cost is
   * calculating for each element individually by calling `calculateCost` function.
   * This operator works for streams when elements have different cost(length).
   * Streams of `ByteString` for example.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as element costs. If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Elements that costs more than the allowed burst will be delayed proportionally
   * to their cost minus available tokens, meeting the target rate. Bucket is full when stream just materialized and
   * started.
   *
   * The burst size is calculated based on the given rate (`cost/per`) as 0.1 * rate, for example:
   * - rate < 20/second => burst size 1
   * - rate 20/second => burst size 2
   * - rate 100/second => burst size 10
   * - rate 200/second => burst size 20
   *
   * The throttle `mode` is [[pekko.stream.ThrottleMode.Shaping]], which makes pauses before emitting messages to
   * meet throttle rate.
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures or the incoming rate is higher than the speed limit
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def throttle(
      cost: Int,
      per: java.time.Duration,
      costCalculation: function.Function[Out, Integer]): javadsl.SubSource[Out, Mat] =
    new SubSource(delegate.throttle(cost, per.asScala, costCalculation.apply _))

  /**
   * Sends elements downstream with speed limited to `cost/per`. Cost is
   * calculating for each element individually by calling `calculateCost` function.
   * This operator works for streams when elements have different cost(length).
   * Streams of `ByteString` for example.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size or maximumBurst).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as element costs. If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Elements that costs more than the allowed burst will be delayed proportionally
   * to their cost minus available tokens, meeting the target rate. Bucket is full when stream just materialized and started.
   *
   * Parameter `mode` manages behavior when upstream is faster than throttle rate:
   *  - [[pekko.stream.ThrottleMode.Shaping]] makes pauses before emitting messages to meet throttle rate
   *  - [[pekko.stream.ThrottleMode.Enforcing]] fails with exception when upstream is faster than throttle rate. Enforcing
   *  cannot emit elements that cost more than the maximumBurst
   *
   * It is recommended to use non-zero burst sizes as they improve both performance and throttling precision by allowing
   * the implementation to avoid using the scheduler when input rates fall below the enforced limit and to reduce
   * most of the inaccuracy caused by the scheduler resolution (which is in the range of milliseconds).
   *
   *  WARNING: Be aware that throttle is using scheduler to slow down the stream. This scheduler has minimal time of triggering
   *  next push. Consequently it will slow down the stream as it has minimal pause for emitting. This can happen in
   *  case burst is 0 and speed is higher than 30 events per second. You need to increase the `maximumBurst`  if
   *  elements arrive with small interval (30 milliseconds or less). Use the overloaded `throttle` method without
   *  `maximumBurst` parameter to automatically calculate the `maximumBurst` based on the given rate (`cost/per`).
   *  In other words the throttler always enforces the rate limit when `maximumBurst` parameter is given, but in
   *  certain cases (mostly due to limited scheduler resolution) it enforces a tighter bound than what was prescribed.
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures or the incoming rate is higher than the speed limit
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "Akka 2.5.12")
  def throttle(
      cost: Int,
      per: FiniteDuration,
      maximumBurst: Int,
      costCalculation: function.Function[Out, Integer],
      mode: ThrottleMode): javadsl.SubSource[Out, Mat] =
    new SubSource(delegate.throttle(cost, per, maximumBurst, costCalculation.apply _, mode))

  /**
   * Sends elements downstream with speed limited to `cost/per`. Cost is
   * calculating for each element individually by calling `calculateCost` function.
   * This operator works for streams when elements have different cost(length).
   * Streams of `ByteString` for example.
   *
   * Throttle implements the token bucket model. There is a bucket with a given token capacity (burst size or maximumBurst).
   * Tokens drops into the bucket at a given rate and can be `spared` for later use up to bucket capacity
   * to allow some burstiness. Whenever stream wants to send an element, it takes as many
   * tokens from the bucket as element costs. If there isn't any, throttle waits until the
   * bucket accumulates enough tokens. Elements that costs more than the allowed burst will be delayed proportionally
   * to their cost minus available tokens, meeting the target rate. Bucket is full when stream just materialized and started.
   *
   * Parameter `mode` manages behavior when upstream is faster than throttle rate:
   *  - [[pekko.stream.ThrottleMode.Shaping]] makes pauses before emitting messages to meet throttle rate
   *  - [[pekko.stream.ThrottleMode.Enforcing]] fails with exception when upstream is faster than throttle rate. Enforcing
   *  cannot emit elements that cost more than the maximumBurst
   *
   * It is recommended to use non-zero burst sizes as they improve both performance and throttling precision by allowing
   * the implementation to avoid using the scheduler when input rates fall below the enforced limit and to reduce
   * most of the inaccuracy caused by the scheduler resolution (which is in the range of milliseconds).
   *
   *  WARNING: Be aware that throttle is using scheduler to slow down the stream. This scheduler has minimal time of triggering
   *  next push. Consequently it will slow down the stream as it has minimal pause for emitting. This can happen in
   *  case burst is 0 and speed is higher than 30 events per second. You need to increase the `maximumBurst`  if
   *  elements arrive with small interval (30 milliseconds or less). Use the overloaded `throttle` method without
   *  `maximumBurst` parameter to automatically calculate the `maximumBurst` based on the given rate (`cost/per`).
   *  In other words the throttler always enforces the rate limit when `maximumBurst` parameter is given, but in
   *  certain cases (mostly due to limited scheduler resolution) it enforces a tighter bound than what was prescribed.
   *
   * '''Emits when''' upstream emits an element and configured time per each element elapsed
   *
   * '''Backpressures when''' downstream backpressures or the incoming rate is higher than the speed limit
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def throttle(
      cost: Int,
      per: java.time.Duration,
      maximumBurst: Int,
      costCalculation: function.Function[Out, Integer],
      mode: ThrottleMode): javadsl.SubSource[Out, Mat] =
    new SubSource(delegate.throttle(cost, per.asScala, maximumBurst, costCalculation.apply _, mode))

  /**
   * This is a simplified version of throttle that spreads events evenly across the given time interval.
   *
   * Use this operator when you need just slow down a stream without worrying about exact amount
   * of time between events.
   *
   * If you want to be sure that no time interval has no more than specified number of events you need to use
   * [[throttle]] with maximumBurst attribute.
   * @see [[#throttle]]
   */
  @deprecated("Use throttle without `maximumBurst` parameter instead.", "Akka 2.5.12")
  def throttleEven(elements: Int, per: FiniteDuration, mode: ThrottleMode): javadsl.SubSource[Out, Mat] =
    new SubSource(delegate.throttleEven(elements, per, mode))

  /**
   * This is a simplified version of throttle that spreads events evenly across the given time interval.
   *
   * Use this operator when you need just slow down a stream without worrying about exact amount
   * of time between events.
   *
   * If you want to be sure that no time interval has no more than specified number of events you need to use
   * [[throttle]] with maximumBurst attribute.
   * @see [[#throttle]]
   */
  @deprecated("Use throttle without `maximumBurst` parameter instead.", "Akka 2.5.12")
  def throttleEven(elements: Int, per: java.time.Duration, mode: ThrottleMode): javadsl.SubSource[Out, Mat] =
    throttleEven(elements, per.asScala, mode)

  /**
   * This is a simplified version of throttle that spreads events evenly across the given time interval.
   *
   * Use this operator when you need just slow down a stream without worrying about exact amount
   * of time between events.
   *
   * If you want to be sure that no time interval has no more than specified number of events you need to use
   * [[throttle]] with maximumBurst attribute.
   * @see [[#throttle]]
   */
  @deprecated("Use throttle without `maximumBurst` parameter instead.", "Akka 2.5.12")
  def throttleEven(
      cost: Int,
      per: FiniteDuration,
      costCalculation: (Out) => Int,
      mode: ThrottleMode): javadsl.SubSource[Out, Mat] =
    new SubSource(delegate.throttleEven(cost, per, costCalculation.apply _, mode))

  /**
   * This is a simplified version of throttle that spreads events evenly across the given time interval.
   *
   * Use this operator when you need just slow down a stream without worrying about exact amount
   * of time between events.
   *
   * If you want to be sure that no time interval has no more than specified number of events you need to use
   * [[throttle]] with maximumBurst attribute.
   * @see [[#throttle]]
   */
  @deprecated("Use throttle without `maximumBurst` parameter instead.", "Akka 2.5.12")
  def throttleEven(
      cost: Int,
      per: java.time.Duration,
      costCalculation: (Out) => Int,
      mode: ThrottleMode): javadsl.SubSource[Out, Mat] =
    throttleEven(cost, per.asScala, costCalculation, mode)

  /**
   * Detaches upstream demand from downstream demand without detaching the
   * stream rates; in other words acts like a buffer of size 1.
   *
   * '''Emits when''' upstream emits an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def detach: javadsl.SubSource[Out, Mat] = new SubSource(delegate.detach)

  /**
   * Delays the initial element by the specified duration.
   *
   * '''Emits when''' upstream emits an element if the initial delay is already elapsed
   *
   * '''Backpressures when''' downstream backpressures or initial delay is not yet elapsed
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  @deprecated("Use the overloaded one which accepts java.time.Duration instead.", since = "Akka 2.5.12")
  def initialDelay(delay: FiniteDuration): SubSource[Out, Mat] =
    new SubSource(delegate.initialDelay(delay))

  /**
   * Delays the initial element by the specified duration.
   *
   * '''Emits when''' upstream emits an element if the initial delay is already elapsed
   *
   * '''Backpressures when''' downstream backpressures or initial delay is not yet elapsed
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  @nowarn("msg=deprecated")
  def initialDelay(delay: java.time.Duration): SubSource[Out, Mat] =
    initialDelay(delay.asScala)

  /**
   * Change the attributes of this [[Source]] to the given ones and seal the list
   * of attributes. This means that further calls will not be able to remove these
   * attributes, but instead add new ones. Note that this
   * operation has no effect on an empty Flow (because the attributes apply
   * only to the contained processing operators).
   */
  def withAttributes(attr: Attributes): SubSource[Out, Mat] =
    new SubSource(delegate.withAttributes(attr))

  /**
   * Add the given attributes to this Source. Further calls to `withAttributes`
   * will not remove these attributes. Note that this
   * operation has no effect on an empty Flow (because the attributes apply
   * only to the contained processing operators).
   */
  def addAttributes(attr: Attributes): SubSource[Out, Mat] =
    new SubSource(delegate.addAttributes(attr))

  /**
   * Add a ``name`` attribute to this Flow.
   */
  def named(name: String): SubSource[Out, Mat] =
    new SubSource(delegate.named(name))

  /**
   * Put an asynchronous boundary around this `SubSource`
   */
  def async: SubSource[Out, Mat] =
    new SubSource(delegate.async)

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
   *
   * The `extract` function will be applied to each element before logging, so it is possible to log only those fields
   * of a complex object flowing through this element.
   *
   * Uses the given [[LoggingAdapter]] for logging.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def log(name: String, extract: function.Function[Out, Any], log: LoggingAdapter): SubSource[Out, Mat] =
    new SubSource(delegate.log(name, e => extract.apply(e))(log))

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
   *
   * The `extract` function will be applied to each element before logging, so it is possible to log only those fields
   * of a complex object flowing through this element.
   *
   * Uses an internally created [[LoggingAdapter]] which uses `org.apache.pekko.stream.Log` as it's source (use this class to configure slf4j loggers).
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def log(name: String, extract: function.Function[Out, Any]): SubSource[Out, Mat] =
    this.log(name, extract, null)

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
   *
   * Uses the given [[LoggingAdapter]] for logging.
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def log(name: String, log: LoggingAdapter): SubSource[Out, Mat] =
    this.log(name, ConstantFun.javaIdentityFunction[Out], log)

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow.
   *
   * Uses an internally created [[LoggingAdapter]] which uses `org.apache.pekko.stream.Log` as it's source (use this class to configure slf4j loggers).
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def log(name: String): SubSource[Out, Mat] =
    this.log(name, ConstantFun.javaIdentityFunction[Out], null)

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
   *
   * The `extract` function will be applied to each element before logging, so it is possible to log only those fields
   * of a complex object flowing through this element.
   *
   * Uses the given [[MarkerLoggingAdapter]] for logging.
   *
   * Adheres to the [[ActorAttributes.SupervisionStrategy]] attribute.
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def logWithMarker(
      name: String,
      marker: function.Function[Out, LogMarker],
      extract: function.Function[Out, Any],
      log: MarkerLoggingAdapter): SubSource[Out, Mat] =
    new SubSource(delegate.logWithMarker(name, e => marker.apply(e), e => extract.apply(e))(log))

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
   *
   * The `extract` function will be applied to each element before logging, so it is possible to log only those fields
   * of a complex object flowing through this element.
   *
   * Uses an internally created [[MarkerLoggingAdapter]] which uses `org.apache.pekko.stream.Log` as it's source (use this class to configure slf4j loggers).
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def logWithMarker(
      name: String,
      marker: function.Function[Out, LogMarker],
      extract: function.Function[Out, Any]): SubSource[Out, Mat] =
    this.logWithMarker(name, marker, extract, null)

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow:
   *
   * Uses the given [[MarkerLoggingAdapter]] for logging.
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def logWithMarker(
      name: String,
      marker: function.Function[Out, LogMarker],
      log: MarkerLoggingAdapter): SubSource[Out, Mat] =
    this.logWithMarker(name, marker, ConstantFun.javaIdentityFunction[Out], log)

  /**
   * Logs elements flowing through the stream as well as completion and erroring.
   *
   * By default element and completion signals are logged on debug level, and errors are logged on Error level.
   * This can be adjusted according to your needs by providing a custom [[Attributes.LogLevels]] attribute on the given Flow.
   *
   * Uses an internally created [[MarkerLoggingAdapter]] which uses `org.apache.pekko.stream.Log` as it's source (use this class to configure slf4j loggers).
   *
   * '''Emits when''' the mapping function returns an element
   *
   * '''Backpressures when''' downstream backpressures
   *
   * '''Completes when''' upstream completes
   *
   * '''Cancels when''' downstream cancels
   */
  def logWithMarker(name: String, marker: function.Function[Out, LogMarker]): SubSource[Out, Mat] =
    this.logWithMarker(name, marker, ConstantFun.javaIdentityFunction[Out], null)

  /**
   * Aggregate input elements into an arbitrary data structure that can be completed and emitted downstream
   * when custom condition is met which can be triggered by aggregate or timer.
   * It can be thought of a more general [[groupedWeightedWithin]].
   *
   * '''Emits when''' the aggregation function decides the aggregate is complete or the timer function returns true
   *
   * '''Backpressures when''' downstream backpressures and the aggregate is complete
   *
   * '''Completes when''' upstream completes and the last aggregate has been emitted downstream
   *
   * '''Cancels when''' downstream cancels
   *
   * @param allocate    allocate the initial data structure for aggregated elements
   * @param aggregate   update the aggregated elements, return true if ready to emit after update.
   * @param harvest     this is invoked before emit within the current stage/operator
   * @param emitOnTimer decide whether the current aggregated elements can be emitted, the custom function is invoked on every interval
   */
  @deprecated("Use the overloaded one which accepts an Optional instead.", since = "1.2.0")
  def aggregateWithBoundary[Agg, Emit](allocate: java.util.function.Supplier[Agg],
      aggregate: function.Function2[Agg, Out, Pair[Agg, Boolean]],
      harvest: function.Function[Agg, Emit],
      emitOnTimer: Pair[java.util.function.Predicate[Agg], java.time.Duration])
      : javadsl.SubSource[Emit, Mat] = {
    new SubSource(
      asScala.aggregateWithBoundary(() => allocate.get())(
        aggregate = (agg, out) => aggregate.apply(agg, out).toScala,
        harvest = agg => harvest.apply(agg),
        emitOnTimer = Option(emitOnTimer).map {
          case Pair(predicate, duration) => (agg => predicate.test(agg), duration.asScala)
        }))
  }

  /**
   * Aggregate input elements into an arbitrary data structure that can be completed and emitted downstream
   * when custom condition is met which can be triggered by aggregate or timer.
   * It can be thought of a more general [[groupedWeightedWithin]].
   *
   * '''Emits when''' the aggregation function decides the aggregate is complete or the timer function returns true
   *
   * '''Backpressures when''' downstream backpressures and the aggregate is complete
   *
   * '''Completes when''' upstream completes and the last aggregate has been emitted downstream
   *
   * '''Cancels when''' downstream cancels
   *
   * @param allocate    allocate the initial data structure for aggregated elements
   * @param aggregate   update the aggregated elements, return true if ready to emit after update.
   * @param harvest     this is invoked before emit within the current stage/operator
   * @param emitOnTimer decide whether the current aggregated elements can be emitted, the custom function is invoked on every interval
   */
  def aggregateWithBoundary[Agg, Emit](allocate: java.util.function.Supplier[Agg],
      aggregate: function.Function2[Agg, Out, Pair[Agg, Boolean]],
      harvest: function.Function[Agg, Emit],
      emitOnTimer: Optional[Pair[java.util.function.Predicate[Agg], java.time.Duration]])
      : javadsl.SubSource[Emit, Mat] = {
    import org.apache.pekko.util.OptionConverters._
    new SubSource(
      asScala.aggregateWithBoundary(() => allocate.get())(
        aggregate = (agg, out) => aggregate.apply(agg, out).toScala,
        harvest = agg => harvest.apply(agg),
        emitOnTimer = emitOnTimer.toScala.map {
          case Pair(predicate, duration) => (agg => predicate.test(agg), duration.asScala)
        }))
  }
}
