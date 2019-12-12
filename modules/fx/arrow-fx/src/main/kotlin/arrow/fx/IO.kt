package arrow.fx

import arrow.Kind
import arrow.core.Either
import arrow.core.Either.Left
import arrow.core.Eval
import arrow.core.NonFatal
import arrow.core.Option
import arrow.core.Right
import arrow.core.Some
import arrow.core.andThen
import arrow.core.compose
import arrow.core.identity
import arrow.core.nonFatalOrThrow
import arrow.fx.OnCancel.Companion.CancellationException
import arrow.fx.OnCancel.Silent
import arrow.fx.OnCancel.ThrowCancellationException
import arrow.fx.internal.BIOBracket
import arrow.fx.internal.BIOFiber
import arrow.fx.internal.BIOForkedStart
import arrow.fx.internal.BIOResult
import arrow.fx.internal.ForwardCancelable
import arrow.fx.internal.Platform.maxStackDepthSize
import arrow.fx.internal.Platform.onceOnly
import arrow.fx.internal.Platform.unsafeResync
import arrow.fx.internal.ShiftTick
import arrow.fx.internal.UnsafePromise
import arrow.fx.internal.scheduler
import arrow.fx.internal.toEither
import arrow.fx.typeclasses.CancelToken
import arrow.fx.typeclasses.Disposable
import arrow.fx.typeclasses.Duration
import arrow.fx.typeclasses.ExitCase
import arrow.fx.typeclasses.Fiber
import arrow.fx.typeclasses.mapUnit
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

typealias IOProc<A> = ((Either<Throwable, A>) -> Unit) -> Unit
typealias IOProcF<A> = ((Either<Throwable, A>) -> Unit) -> IOOf<Unit>

typealias BIOProc<E, A> = ((BIOResult<E, A>) -> Unit) -> Unit
typealias BIOProcF<E, A> = ((BIOResult<E, A>) -> Unit) -> IOOf<Unit>

class ForBIO private constructor() {
  companion object
}
typealias BIOOf<E, A> = arrow.Kind2<ForBIO, E, A>
typealias BIOPartialOf<E> = Kind<ForBIO, E>

inline fun <E, A> BIOOf<E, A>.fix(): BIO<E, A> =
  this as BIO<E, A>

typealias IO<A> = BIO<Nothing, A>
typealias IOOf<A> = BIOOf<Nothing, A>
typealias ForIO = BIOPartialOf<Nothing>

@Suppress("StringLiteralDuplication")
sealed class BIO<out E, out A> : BIOOf<E, A> {

  companion object : IOParMap2, IOParMap3, IORacePair, IORaceTriple {

    /**
     * Delay a suspended effect.
     *
     * ```kotlin:ank:playground:extension
     * import arrow.fx.IO
     * import kotlinx.coroutines.Dispatchers
     *
     * fun main(args: Array<String>) {
     *   //sampleStart
     *   suspend fun helloWorld(): Unit = println("Hello World!")
     *
     *   val result = IO.effect { helloWorld() }
     *   //sampleEnd
     *   println(result.unsafeRunSync())
     * }
     * ```
     */
    fun <A> effect(f: suspend () -> A): BIO<Nothing, A> =
      Effect(effect = f)

    /**
     * Delay a suspended effect on provided [CoroutineContext].
     *
     * @param ctx [CoroutineContext] to run evaluation on.
     *
     * ```kotlin:ank:playground:extension
     * import arrow.fx.IO
     * import kotlinx.coroutines.Dispatchers
     *
     * fun main(args: Array<String>) {
     *   //sampleStart
     *   suspend fun getThreadSuspended(): String = Thread.currentThread().name
     *
     *   val result = IO.effect(Dispatchers.Default) { getThreadSuspended() }
     *   //sampleEnd
     *   println(result)
     * }
     * ```
     */
    fun <A> effect(ctx: CoroutineContext, f: suspend () -> A): IO<A> =
      Effect(ctx, f)

    /** @see effect */
    operator fun <A> invoke(ctx: CoroutineContext, f: suspend () -> A): IO<A> =
      effect(ctx, f)

    /** @see effect */
    operator fun <A> invoke(f: suspend () -> A): IO<A> =
      effect(EmptyCoroutineContext, f)

    /**
     * Just wrap a pure value [A] into [IO].
     *
     * ```kotlin:ank:playground
     * import arrow.fx.IO
     *
     * fun main(args: Array<String>) {
     *   //sampleStart
     *   val result = IO.just("Hello from just!")
     *   //sampleEnd
     *   println(result.unsafeRunSync())
     * }
     * ```
     */
    fun <A> just(a: A): IO<A> = Pure(a)

    /**
     * Raise an error in a pure way without actually throwing.
     *
     * ```kotlin:ank:playground
     * import arrow.fx.IO
     *
     * fun main(args: Array<String>) {
     *   //sampleStart
     *   val result: IO<Int> = IO.raiseError<Int>(RuntimeException("Boom"))
     *   //sampleEnd
     *   println(result.unsafeRunSync())
     * }
     * ```
     */
    fun <A> raiseError(e: Throwable): IO<A> = RaiseError(e)

    /**
     *  Sleeps for a given [duration] without blocking a thread.
     *
     * ```kotlin:ank:playground
     * import arrow.fx.IO
     * import arrow.fx.typeclasses.seconds
     *
     * fun main(args: Array<String>) {
     *   val result =
     *   //sampleStart
     *   IO.sleep(3.seconds).flatMap {
     *     IO.effect { println("Hello World!") }
     *   }
     *   //sampleEnd
     *   result.unsafeRunSync()
     * }
     * ```
     **/
    fun sleep(duration: Duration, continueOn: CoroutineContext = IODispatchers.CommonPool): IO<Unit> =
      cancelable<Unit> { cb ->
        val cancelRef = scheduler.schedule(ShiftTick(continueOn, cb), duration.amount, duration.timeUnit)
        later { cancelRef.cancel(false); Unit }
      }

    /**
     * Wraps a function into [IO] to execute it _later_.
     *
     * @param f function to wrap into [IO].
     *
     * ```kotlin:ank:playground
     * import arrow.fx.IO
     *
     * fun main(args: Array<String>) {
     *   //sampleStart
     *   val result = IO { "Hello from operator invoke!" }
     *   //sampleEnd
     *   println(result.unsafeRunSync())
     * }
     * ```
     */
    fun <A> later(f: () -> A): IO<A> =
      defer { Pure(f()) }

    /**
     * Defer a computation that results in an [IO] value.
     *
     * ```kotlin:ank:playground
     * import arrow.fx.IO
     *
     * fun main(args: Array<String>) {
     *   //sampleStart
     *   val result = IO.defer { IO { "Hello from IO in defer" } }
     *   //sampleEnd
     *   println(result.unsafeRunSync())
     * }
     * ```
     */
    fun <E, A> defer(f: () -> BIOOf<E, A>): BIO<E, A> =
      Suspend(f)

    /**
     * Create an [IO] that executes an asynchronous process on evaluation.
     * This combinator can be used to wrap callbacks or other similar impure code **that require no cancellation code**.
     *
     * ```kotlin:ank:playground
     * import arrow.core.*
     * import arrow.fx.*
     * import java.lang.RuntimeException
     *
     * typealias Callback = (List<String>?, Throwable?) -> Unit
     *
     * class GithubId
     * object GithubService {
     *   fun getUsernames(callback: Callback) {
     *     //execute operation and call callback at some point in future
     *   }
     * }
     *
     * fun main(args: Array<String>) {
     *   //sampleStart
     *   fun getUsernames(): IO<List<String>> =
     *     IO.async { cb: (Either<Throwable, List<String>>) -> Unit ->
     *       GithubService.getUsernames { names, throwable ->
     *         when {
     *           names != null -> cb(Right(names))
     *           throwable != null -> cb(Left(throwable))
     *           else -> cb(Left(RuntimeException("Null result and no exception")))
     *         }
     *       }
     *     }
     *
     *   val result = getUsernames()
     *   //sampleEnd
     *   println(result.unsafeRunSync())
     * }
     * ```
     *
     * @param k an asynchronous computation that might fail typed as [IOProc].
     * @see cancelable for an operator that supports cancelation.
     * @see asyncF for a version that can suspend side effects in the registration function.
     */
    fun <E, A> async(k: BIOProc<E, A>): BIO<E, A> =
      Async { _: IOConnection, ff: (BIOResult<E, A>) -> Unit ->
        onceOnly(ff).let { callback: (BIOResult<E, A>) -> Unit ->
          try {
            k(callback)
          } catch (throwable: Throwable) {
            callback(BIOResult.Error(throwable.nonFatalOrThrow()))
          }
        }
      }

    @JvmName("asyncIO")
    fun <A> async(k: IOProc<A>): IO<A> =
      async(k.toBIO())

    /**
     * Create an [IO] that executes an asynchronous process on evaluation.
     * This combinator can be used to wrap callbacks or other similar impure code **that require no cancellation code**.
     *
     * ```kotlin:ank:playground
     * import arrow.core.*
     * import arrow.fx.*
     * import java.lang.RuntimeException
     *
     * typealias Callback = (List<String>?, Throwable?) -> Unit
     *
     * object GithubService {
     *   fun getUsernames(callback: Callback) {
     *     //execute operation and call callback at some point in future
     *   }
     * }
     *
     * fun main(args: Array<String>) {
     *   //sampleStart
     *   fun getUsernames(): IO<List<String>> =
     *     IO.asyncF { cb: (Either<Throwable, List<String>>) -> Unit ->
     *       IO {
     *         GithubService.getUsernames { names, throwable ->
     *           when {
     *             names != null -> cb(Right(names))
     *             throwable != null -> cb(Left(throwable))
     *             else -> cb(Left(RuntimeException("Null result and no exception")))
     *           }
     *         }
     *       }
     *     }
     *
     *   val result = getUsernames()
     *   //sampleEnd
     *   println(result.unsafeRunSync())
     * }
     * ```
     *
     * @param k a deferred asynchronous computation that might fail typed as [IOProcF].
     * @see async for a version that can suspend side effects in the registration function.
     * @see cancelableF for an operator that supports cancelation.
     */
    fun <E, A> asyncF(k: BIOProcF<E, A>): BIO<E, A> =
      Async { conn: IOConnection, ff: (BIOResult<E, A>) -> Unit ->
        val conn2 = IOConnection()
        conn.push(conn2.cancel())
        onceOnly(conn, ff).let { callback: (BIOResult<E, A>) -> Unit ->
          val fa = try {
            k(callback)
          } catch (t: Throwable) {
            if (NonFatal(t)) {
              IO { callback(BIOResult.Error(t)) }
            } else {
              throw t
            }
          }

          IORunLoop.startCancelable(fa, conn2) { result ->
            result.fold({ e -> callback(BIOResult.Error(e)) }, mapUnit, mapUnit)
          }
        }
      }

    /**
     * Creates a cancelable instance of [IO] that executes an asynchronous process on evaluation.
     * This combinator can be used to wrap callbacks or other similar impure code that requires cancellation code.
     *
     * ```kotlin:ank:playground
     * import arrow.core.*
     * import arrow.fx.*
     * import java.lang.RuntimeException
     *
     * typealias Callback = (List<String>?, Throwable?) -> Unit
     *
     * class GithubId
     * object GithubService {
     *   private val listeners: MutableMap<GithubId, Callback> = mutableMapOf()
     *   fun getUsernames(callback: Callback): GithubId {
     *     val id = GithubId()
     *     listeners[id] = callback
     *     //execute operation and call callback at some point in future
     *     return id
     *   }
     *
     *   fun unregisterCallback(id: GithubId): Unit {
     *     listeners.remove(id)
     *   }
     * }
     *
     * fun main(args: Array<String>) {
     *   //sampleStart
     *   fun getUsernames(): IO<List<String>> =
     *     IO.cancelable { cb: (Either<Throwable, List<String>>) -> Unit ->
     *       val id = GithubService.getUsernames { names, throwable ->
     *         when {
     *           names != null -> cb(Right(names))
     *           throwable != null -> cb(Left(throwable))
     *           else -> cb(Left(RuntimeException("Null result and no exception")))
     *         }
     *       }
     *
     *       IO { GithubService.unregisterCallback(id) }
     *     }
     *
     *   val result = getUsernames()
     *   //sampleEnd
     *   println(result.unsafeRunSync())
     * }
     * ```
     *
     * @param cb an asynchronous computation that might fail.
     * @see async for wrapping impure APIs without cancelation
     */
    fun <E, A> cancelable(cb: ((BIOResult<E, A>) -> Unit) -> CancelToken<ForIO>): BIO<E, A> =
      Async { conn: IOConnection, cbb: (BIOResult<E, A>) -> Unit ->
        onceOnly(conn, cbb).let { cbb2 ->
          val cancelable = ForwardCancelable()
          conn.push(cancelable.cancel())
          if (conn.isNotCanceled()) {
            cancelable.complete(try {
              cb(cbb2)
            } catch (throwable: Throwable) {
              cbb2(BIOResult.Error(throwable.nonFatalOrThrow()))
              unit
            })
          }
        }
      }

    @JvmName("cancelableIO")
    fun <A> cancelable(cb: ((Either<Throwable, A>) -> Unit) -> CancelToken<ForIO>): IO<A> =
      cancelable(cb.toBIO())

    /**
     * Creates a cancelable instance of [IO] that executes an asynchronous process on evaluation.
     * This combinator can be used to wrap callbacks or other similar impure code that requires cancellation code.
     *
     * ```kotlin:ank:playground
     * import arrow.core.*
     * import arrow.fx.*
     * import java.lang.RuntimeException
     *
     * typealias Callback = (List<String>?, Throwable?) -> Unit
     *
     * class GithubId
     * object GithubService {
     *   private val listeners: MutableMap<GithubId, Callback> = mutableMapOf()
     *   fun getUsernames(callback: Callback): GithubId {
     *     val id = GithubId()
     *     listeners[id] = callback
     *     //execute operation and call callback at some point in future
     *     return id
     *   }
     *
     *   fun unregisterCallback(id: GithubId): Unit {
     *     listeners.remove(id)
     *   }
     * }
     *
     * fun main(args: Array<String>) {
     *   //sampleStart
     *   fun getUsernames(): IO<List<String>> =
     *     IO.cancelableF { cb: (Either<Throwable, List<String>>) -> Unit ->
     *       IO {
     *         val id = GithubService.getUsernames { names, throwable ->
     *           when {
     *             names != null -> cb(Right(names))
     *             throwable != null -> cb(Left(throwable))
     *             else -> cb(Left(RuntimeException("Null result and no exception")))
     *           }
     *         }
     *
     *         IO { GithubService.unregisterCallback(id) }
     *       }
     *     }
     *
     *   val result = getUsernames()
     *   //sampleEnd
     *   println(result.unsafeRunSync())
     * }
     * ```
     *
     * @param cb a deferred asynchronous computation that might fail.
     * @see asyncF for wrapping impure APIs without cancelation
     */
    fun <E, A> cancelableF(cb: ((BIOResult<E, A>) -> Unit) -> IOOf<CancelToken<ForIO>>): BIO<E, A> =
      Async { conn: IOConnection, cbb: (BIOResult<E, A>) -> Unit ->
        val cancelable = ForwardCancelable()
        val conn2 = IOConnection()
        conn.push(cancelable.cancel())
        conn.push(conn2.cancel())

        onceOnly(conn, cbb).let { cbb2 ->
          val fa: IOOf<CancelToken<ForIO>> = try {
            cb(cbb2)
          } catch (throwable: Throwable) {
            cbb2(BIOResult.Error(throwable.nonFatalOrThrow()))
            just(unit)
          }

          IORunLoop.startCancelable(fa, conn2) { result ->
            conn.pop()
            result.fold({ }, cancelable::complete, cancelable::complete)
          }
        }
      }

    /**
     * A pure [IO] value of [Unit].
     *
     * ```kotlin:ank:playground
     * import arrow.fx.IO
     *
     * fun main(args: Array<String>) {
     *   //sampleStart
     *   val result = IO.unit
     *   //sampleEnd
     *   println(result.unsafeRunSync())
     * }
     * ```
     */
    val unit: IO<Unit> =
      just(Unit)

    /**
     * A lazy [IO] value of [Unit].
     *
     * ```kotlin:ank:playground
     * import arrow.fx.IO
     *
     * fun main(args: Array<String>) {
     *   //sampleStart
     *   val result = IO.lazy
     *   //sampleEnd
     *   println(result.unsafeRunSync())
     * }
     * ```
     */
    val lazy: IO<Unit> =
      invoke { }

    /**
     * Evaluates an [Eval] instance within a safe [IO] context.
     *
     * ```kotlin:ank:playground
     * import arrow.fx.IO
     * import arrow.core.Eval
     *
     * fun main(args: Array<String>) {
     *   fun longCalculation(): Int = 9999
     *   //sampleStart
     *   val result = IO.eval(Eval.later { longCalculation() })
     *   //sampleEnd
     *   println(result.unsafeRunSync())
     * }
     * ```
     */
    fun <A> eval(eval: Eval<A>): IO<A> =
      when (eval) {
        is Eval.Now -> just(eval.value)
        else -> invoke { eval.value() }
      }

    /**
     * Perform a recursive operation in a stack-safe way, by checking the inner [Either] value.
     * If you want to continue the recursive operation return [Either.Left] with the intermediate result [A],
     * [Either.Right] indicates the terminal event and *must* thus return the resulting value [B].
     *
     * ```kotlin:ank:playground
     * import arrow.core.*
     * import arrow.fx.IO
     *
     * fun main(args: Array<String>) {
     *   //sampleStart
     *   val result = IO.tailRecM(0) { i ->
     *     IO.just(
     *      if(i == 5000) Right(i)
     *      else Left(i + 1)
     *     )
     *   }
     *   //sampleEnd
     *   println(result.unsafeRunSync())
     * }
     * ```
     */
    fun <E, A, B> tailRecM(a: A, f: (A) -> BIOOf<E, Either<A, B>>): BIO<E, B> =
      f(a).fix().flatMap {
        when (it) {
          is Either.Left -> tailRecM(it.a, f)
          is Either.Right -> just(it.b)
        }
      }

    /**
     * A pure [IO] value that never returns.
     * Useful when you need to model non-terminating cases.
     *
     * ```kotlin:ank:playground
     * import arrow.fx.IO
     *
     * fun main(args: Array<String>) {
     *   //sampleStart
     *   val result: IO<Int> = IO.never
     *   //sampleEnd
     *   println(result.unsafeRunSync())
     * }
     * ```
     */
    val never: IO<Nothing> =
      async<Nothing> { }
  }

  /**
   * Run the [IO] in a suspended environment.
   *
   * ```kotlin:ank:playground
   * import arrow.fx.IO
   *
   * fun main(args: Array<String>) {
   *   //sampleStart
   *   val io = IO.effect { println("Hello World!") }
   *
   *   kotlinx.coroutines.runBlocking {
   *     io.suspended()
   *   }
   *   //sampleEnd
   * }
   * ```
   *
   * **BEWARE** this does **not** support cancelation since Kotlin has no cancelation support for `suspend` on the language level.
   */
  suspend fun suspended(): Either<E, A> = suspendCoroutine { cont ->
    IORunLoop.start(this) {
      it.fold(cont::resumeWithException,
        { e -> cont.resume(Left(e)) },
        { a -> cont.resume(Right(a)) })
    }
  }

  /**
   * Transform the [IO] wrapped value of [A] into [B] preserving the [IO] structure.
   *
   * @param f a pure function that maps the value [A] to a value [B].
   * @returns an [IO] that results in a value [B].
   *
   * ```kotlin:ank:playground
   * import arrow.fx.IO
   *
   * fun main(args: Array<String>) {
   *   val result =
   *   //sampleStart
   *   IO.just("Hello").map { "$it World" }
   *   //sampleEnd
   *   println(result.unsafeRunSync())
   * }
   * ```
   */
  open fun <B> map(f: (A) -> B): BIO<E, B> =
    Map(this, f, 0)

  /**
   * Continue the evaluation on provided [CoroutineContext]
   *
   * @param ctx [CoroutineContext] to run evaluation on
   * @returns an [IO] that'll run the following computations on [ctx].
   *
   * ```kotlin:ank:playground
   * import arrow.fx.IO
   * import kotlinx.coroutines.Dispatchers
   *
   * fun main(args: Array<String>) {
   *   //sampleStart
   *   val result = IO.unit.continueOn(Dispatchers.Default).flatMap {
   *     IO { Thread.currentThread().name }
   *   }
   *   //sampleEnd
   *   println(result.unsafeRunSync())
   * }
   * ```
   */
  open fun continueOn(ctx: CoroutineContext): BIO<E, A> =
    ContinueOn(this, ctx)

  fun attempt(): BIO<Throwable, Either<E, A>> =
    Bind(this, IOFrame.attemptBIO())

  fun <B> redeem(
    ft: (Throwable) -> B,
    fe: (E) -> B,
    fb: (A) -> B
  ): IO<B> =
    Bind(this, IOFrame.Companion.Redeem(ft, fe, fb))

  fun <E2, B> redeemWith(
    ft: (Throwable) -> BIOOf<E2, B>,
    fe: (E) -> BIOOf<E2, B>,
    fb: (A) -> BIOOf<E2, B>
  ): BIO<E2, B> =
    Bind(this, IOFrame.Companion.RedeemWith(ft, fe, fb))

  /**
   * [runAsync] allows you to run any [IO] in a referential transparent manner.
   *
   * Reason it can happen in a referential transparent manner is because nothing is actually running when this method is invoked.
   * The combinator can be used to define how several programs have to run in a safe manner.
   */
  fun runAsync(cb: (BIOResult<E, A>) -> IOOf<Unit>): IO<Unit> =
    IO { unsafeRunAsync(cb.andThen { it.fix().unsafeRunAsync { } }) }

  /**
   * [unsafeRunAsync] allows you to run any [IO] and receive the values in a callback [cb]
   * and thus **has** the ability to run `NonBlocking` but that depends on the implementation.
   * When the underlying effects/program runs blocking on the callers thread this method will run blocking.
   *
   * To start this on `NonBlocking` use `NonBlocking.shift().followedBy(io).unsafeRunAsync { }`.
   *
   * @param cb the callback that is called with the computations result represented as an [Either].
   * @see [unsafeRunAsyncCancellable] to run in a cancellable manner.
   * @see [runAsync] to run in a referential transparent manner.
   */
  fun unsafeRunAsync(cb: (BIOResult<E, A>) -> Unit): Unit =
    IORunLoop.start(this, cb)

  /**
   * A pure version of [unsafeRunAsyncCancellable], it defines how an [IO] is ran in a cancelable manner but it doesn't run yet.
   *
   * It receives the values in a callback [cb] and thus **has** the ability to run `NonBlocking` but that depends on the implementation.
   * When the underlying effects/program runs blocking on the callers thread this method will run blocking.
   *
   * @param cb the callback that is called with the computations result represented as an [Either].
   * @return a [Disposable] that can be used to cancel the computation.
   * @see [unsafeRunAsync] to run in an unsafe and non-cancellable manner.
   * @see [unsafeRunAsyncCancellable] to run in a non-referential transparent manner.
   */
  fun runAsyncCancellable(onCancel: OnCancel = Silent, cb: (BIOResult<E, A>) -> IOOf<Unit>): IO<Disposable> =
    async<Disposable> { ccb ->
      val conn = IOConnection()
      val onCancelCb =
        when (onCancel) {
          ThrowCancellationException ->
            cb andThen { it.fix().unsafeRunAsync { } }
          Silent ->
            { either -> either.fold({ if (!conn.isCanceled() || it != CancellationException) cb(either) }, { cb(either) }, { cb(either) }) }
        }
      ccb(Either.Right(conn.toDisposable()))
      IORunLoop.startCancelable(this, conn, onCancelCb)
    }

  /**
   * [unsafeRunAsyncCancellable] allows you to run any [IO] and receive the values in a callback [cb] while being cancelable.
   * It **has** the ability to run `NonBlocking` but that depends on the implementation, when the underlying
   * effects/program runs blocking on the callers thread this method will run blocking.
   *
   * To start this on `NonBlocking` use `NonBlocking.shift().followedBy(io).unsafeRunAsync { }`.
   *
   * @param cb the callback that is called with the computations result represented as an [Either].
   * @returns [Disposable] or cancel reference that cancels the running [IO].
   * @see [unsafeRunAsyncCancellable] to run in a cancellable manner.
   * @see [runAsync] to run in a referential transparent manner.
   */
  fun unsafeRunAsyncCancellable(onCancel: OnCancel = Silent, cb: (BIOResult<E, A>) -> Unit): Disposable =
    runAsyncCancellable(onCancel, cb andThen { unit }).unsafeRunSync().value()

  /**
   * [unsafeRunSync] allows you to run any [IO] to its wrapped value [A].
   *
   * It's called unsafe because it immediately runs the effects wrapped in [IO],
   * and thus is **not** referentially transparent.
   *
   * **NOTE** this function is intended for testing, it should never appear in your mainline production code!
   *
   * @return the resulting value
   * @see [unsafeRunAsync] or [unsafeRunAsyncCancellable] that run the value as [Either].
   * @see [runAsync] to run in a referential transparent manner.
   */
  fun unsafeRunSync(): Either<E, A> =
    unsafeRunTimed(Duration.INFINITE)
      .fold({ throw IllegalArgumentException("IO execution should yield a valid result") }, ::identity)

  /**
   * Run with a limitation on how long to await for *individual* async results.
   * It's possible that this methods runs forever i.e. for an infinite recursive [IO].
   *
   * **NOTE** this function is intended for testing, it should never appear in your mainline production code!
   *
   * @see unsafeRunSync
   */
  fun unsafeRunTimed(limit: Duration): Option<Either<E, A>> =
    IORunLoop.step(this).unsafeRunTimedTotal(limit)

  internal abstract fun unsafeRunTimedTotal(limit: Duration): Option<Either<E, A>>

  /** Makes the source [IO] uncancelable such that a [Fiber.cancel] signal has no effect. */
  fun uncancelable(): BIO<E, A> =
    ContextSwitch(this, ContextSwitch.makeUncancelable, ContextSwitch.disableUncancelable)

  internal data class Pure<out A>(val a: A) : BIO<Nothing, A>() {
    // Pure can be replaced by its value
    override fun <B> map(f: (A) -> B): BIO<Nothing, B> = Suspend { Pure(f(a)) }

    override fun unsafeRunTimedTotal(limit: Duration): Option<Either<Nothing, A>> = Some(Right(a))
  }

  internal data class RaiseError(val exception: Throwable) : BIO<Nothing, Nothing>() {
    // Errors short-circuit
    override fun <B> map(f: (Nothing) -> B): BIO<Nothing, B> = this

    override fun unsafeRunTimedTotal(limit: Duration): Option<Nothing> = throw exception
  }

  internal data class RaiseLeft<E>(val left: E) : BIO<E, Nothing>() {
    // Errors short-circuit
    override fun <B> map(f: (Nothing) -> B): BIO<E, B> = this

    override fun unsafeRunTimedTotal(limit: Duration): Option<Either<E, Nothing>> = Some(Left(left))
  }

  internal data class Delay<out A>(val thunk: () -> A) : BIO<Nothing, A>() {
    override fun unsafeRunTimedTotal(limit: Duration): Option<Either<Nothing, A>> = throw AssertionError("Unreachable")
  }

  internal data class Suspend<E, out A>(val thunk: () -> BIOOf<E, A>) : BIO<E, A>() {
    override fun unsafeRunTimedTotal(limit: Duration): Option<Either<E, A>> = throw AssertionError("Unreachable")
  }

  internal data class Async<out E, out A>(val shouldTrampoline: Boolean = false, val k: (IOConnection, (BIOResult<E, A>) -> Unit) -> Unit) : BIO<E, A>() {
    override fun unsafeRunTimedTotal(limit: Duration): Option<Either<E, A>> = unsafeResync(this, limit)
  }

  internal data class Effect<out A>(val ctx: CoroutineContext? = null, val effect: suspend () -> A) : IO<A>() {
    override fun unsafeRunTimedTotal(limit: Duration): Option<Either<Nothing, A>> = unsafeResync(this, limit)
  }

  internal data class Bind<E, A, E2, out B>(val cont: BIO<E, A>, val g: (A) -> BIO<E2, B>) : BIO<E2, B>() {
    override fun unsafeRunTimedTotal(limit: Duration): Option<Either<E2, B>> = throw AssertionError("Unreachable")
  }

  internal data class ContinueOn<E, A>(val cont: BIO<E, A>, val cc: CoroutineContext) : BIO<E, A>() {
    // If a ContinueOn follows another ContinueOn, execute only the latest
    override fun continueOn(ctx: CoroutineContext): BIO<E, A> = ContinueOn(cont, ctx)

    override fun unsafeRunTimedTotal(limit: Duration): Option<Either<E, A>> = throw AssertionError("Unreachable")
  }

  internal data class ContextSwitch<E, A>(
    val source: BIO<E, A>,
    val modify: (IOConnection) -> IOConnection,
    val restore: ((a: Any?, e: Any?, t: Throwable?, old: IOConnection, new: IOConnection) -> IOConnection)?
  ) : BIO<E, A>() {
    override fun unsafeRunTimedTotal(limit: Duration): Option<Either<E, A>> = throw AssertionError("Unreachable")

    companion object {
      // Internal reusable reference.
      internal val makeUncancelable: (IOConnection) -> IOConnection = { KindConnection.uncancelable }

      internal val disableUncancelable: (Any?, Any?, Throwable?, IOConnection, IOConnection) -> IOConnection =
        { _, _, _, old, _ -> old }
    }
  }

  internal data class Map<E, A, out B>(val source: BIOOf<E, A>, val g: (A) -> B, val index: Int) : BIO<E, B>(), (A) -> BIO<E, B> {
    override fun invoke(value: A): BIO<E, B> = Pure(g(value))

    override fun <C> map(f: (B) -> C): BIO<E, C> =
    // Allowed to do maxStackDepthSize map operations in sequence before
      // starting a new Map fusion in order to avoid stack overflows
      if (index != maxStackDepthSize) Map(source, g.andThen(f), index + 1)
      else Map(this, f, 0)

    override fun unsafeRunTimedTotal(limit: Duration): Option<Either<E, B>> = throw AssertionError("Unreachable")
  }
}

/**
 * Handle the error by mapping the error to a value of [A].
 *
 * ```kotlin:ank:playground
 * import arrow.fx.IO
 * import arrow.fx.handleError
 *
 * fun main(args: Array<String>) {
 *   //sampleStart
 *   val result = IO.raiseError<Int>(RuntimeException("Boom"))
 *     .handleError { e -> "Goodbye World! after $e" }
 *   //sampleEnd
 *   println(result.unsafeRunSync())
 * }
 * ```
 *
 * @see handleErrorWith for a version that can resolve the error using an effect
 */
fun <E, A> BIOOf<E, A>.handleError(f: (Throwable) -> A, fe: (E) -> A): IO<A> =
  handleErrorWith({ e -> BIO.Pure(f(e)) }, { e -> BIO.Pure(fe(e)) })

/**
 * Handle the error by resolving the error with an effect that results in [A].
 *
 * ```kotlin:ank:playground
 * import arrow.fx.IO
 * import arrow.fx.handleErrorWith
 * import arrow.fx.typeclasses.milliseconds
 *
 * fun main(args: Array<String>) {
 *   fun getMessage(e: Throwable): IO<String> = IO.sleep(250.milliseconds)
 *     .followedBy(IO.effect { "Delayed goodbye World! after $e" })
 *
 *   //sampleStart
 *   val result = IO.raiseError<Int>(RuntimeException("Boom"))
 *     .handleErrorWith { e -> getMessage(e) }
 *   //sampleEnd
 *   println(result.unsafeRunSync())
 * }
 * ```
 *
 * @see handleErrorWith for a version that can resolve the error using an effect
 */
fun <E, A, E2> BIOOf<E, A>.handleErrorWith(f: (Throwable) -> BIOOf<E2, A>, fe: (E) -> BIOOf<E2, A>): BIO<E2, A> =
  BIO.Bind(fix(), IOFrame.Companion.ErrorHandler(f, fe))

fun <E, A> BIOOf<E, A>.fallbackTo(f: () -> A): BIOOf<E, A> =
  handleError({ f() }, { f() })

fun <E, A, E2> BIOOf<E, A>.fallbackWith(fa: BIOOf<E2, A>): BIO<E2, A> =
  handleErrorWith({ fa }, { fa })

/**
 * Transform the [IO] value of [A] by sequencing an effect [IO] that results in [B].
 *
 * @param f function that returns the [IO] effect resulting in [B] based on the input [A].
 * @returns an effect that results in [B].
 *
 * ```kotlin:ank:playground
 * import arrow.fx.IO
 *
 * fun main(args: Array<String>) {
 *   val result =
 *   //sampleStart
 *   IO.just("Hello").flatMap { IO { "$it World" } }
 *   //sampleEnd
 *   println(result.unsafeRunSync())
 * }
 * ```
 */
fun <E, A, E2 : E, B> BIOOf<E, A>.flatMap(f: (A) -> BIOOf<E2, B>): BIO<E2, B> =
  when (val bio = fix()) {
    is BIO.Pure -> BIO.Suspend { f(bio.a).fix() }
    is BIO.RaiseError,
    is BIO.RaiseLeft -> bio as BIO<E2, B>
    else -> BIO.Bind(bio) { f(it).fix() }
  }

fun <E, A, E2> BIOOf<E, A>.flatMapLeft(f: (E) -> BIOOf<E2, A>): BIO<E2, A> =
  when (val bio = fix()) {
    is BIO.RaiseLeft -> f(bio.left).fix()
    is BIO.Pure,
    is BIO.RaiseError -> bio as BIO<E2, A>
    else -> BIO.Bind(bio, IOFrame.Companion.MapError(f))
  }

fun <E, A, E2> BIOOf<E, A>.mapLeft(f: (E) -> E2): BIO<E2, A> =
  bimap(f, ::identity)

fun <E, A, E2, B> BIOOf<E, A>.bimap(fe: (E) -> E2, fa: (A) -> B): BIO<E2, B> =
  mapLeft(fe).map(fa)

/**
 * Compose this [IO] with another [IO] [fb] while ignoring the output.
 *
 * ```kotlin:ank:playground
 * import arrow.fx.IO
 *
 * fun main(args: Array<String>) {
 *   //sampleStart
 *   val result = IO.effect { println("Hello World!") }
 *     .followedBy(IO.effect { println("Goodbye World!") })
 *   //sampleEnd
 *   println(result.unsafeRunSync())
 * }
 * ```
 *
 * @see flatMap if you need to act on the output of the original [IO].
 */
fun <E, A, E2 : E, B> BIOOf<E, A>.followedBy(fb: BIOOf<E2, B>): BIO<E2, B> =
  flatMap { fb }

/**
 * Given both the value and the function are within [IO], **ap**ply the function to the value.
 *
 * ```kotlin:ank:playground
 * import arrow.fx.IO
 *
 * fun main() {
 *   //sampleStart
 *   val someF: IO<(Int) -> Long> = IO.just { i: Int -> i.toLong() + 1 }
 *   val a = IO.just(3).ap(someF)
 *   val b = IO.raiseError<Int>(RuntimeException("Boom")).ap(someF)
 *   val c = IO.just(3).ap(IO.raiseError<(Int) -> Long>(RuntimeException("Boom")))
 *   //sampleEnd
 *   println("a: $a, b: $b, c: $c")
 * }
 * ```
 */
fun <E, A, B> BIOOf<E, A>.ap(ff: BIOOf<E, (A) -> B>): BIO<E, B> =
  fix().flatMap { a -> ff.fix().map { it(a) } }

/**
 * A way to safely acquire a resource and release in the face of errors and cancellation.
 * It uses [ExitCase] to distinguish between different exit cases when releasing the acquired resource.
 *
 * [Bracket] exists out of a three stages:
 *   1. acquisition
 *   2. consumption
 *   3. releasing
 *
 * 1. Resource acquisition is **NON CANCELABLE**.
 *   If resource acquisition fails, meaning no resource was actually successfully acquired then we short-circuit the effect.
 *   Reason being, we cannot [release] what we did not `acquire` first. Same reason we cannot call [use].
 *   If it is successful we pass the result to stage 2 [use].
 *
 * 2. Resource consumption is like any other [IO] effect. The key difference here is that it's wired in such a way that
 *   [release] **will always** be called either on [ExitCase.Canceled], [ExitCase.Error] or [ExitCase.Completed].
 *   If it failed than the resulting [IO] from [bracketCase] will be `IO.raiseError(e)`, otherwise the result of [use].
 *
 * 3. Resource releasing is **NON CANCELABLE**, otherwise it could result in leaks.
 *   In the case it throws the resulting [IO] will be either the error or a composed error if one occurred in the [use] stage.
 *
 * @param use is the action to consume the resource and produce an [IO] with the result.
 * Once the resulting [IO] terminates, either successfully, error or disposed,
 * the [release] function will run to clean up the resources.
 *
 * @param release the allocated resource after the resulting [IO] of [use] is terminates.
 *
 * ```kotlin:ank:playground
 * import arrow.fx.*
 * import arrow.fx.typeclasses.ExitCase
 *
 * class File(url: String) {
 *   fun open(): File = this
 *   fun close(): Unit {}
 *   fun content(): IO<String> =
 *     IO.just("This file contains some interesting content!")
 * }
 *
 * fun openFile(uri: String): IO<File> = IO { File(uri).open() }
 * fun closeFile(file: File): IO<Unit> = IO { file.close() }
 *
 * fun main(args: Array<String>) {
 *   //sampleStart
 *   val safeComputation = openFile("data.json").bracketCase(
 *     release = { file, exitCase ->
 *       when (exitCase) {
 *         is ExitCase.Completed -> { /* do something */ }
 *         is ExitCase.Canceled -> { /* do something */ }
 *         is ExitCase.Error -> { /* do something */ }
 *       }
 *       closeFile(file)
 *     },
 *     use = { file -> file.content() }
 *   )
 *   //sampleEnd
 *   println(safeComputation.unsafeRunSync())
 * }
 *  ```
 */
fun <E, E2 : E, A, B> BIOOf<E, A>.bracketCase(release: (A, ExitCase<E>) -> IOOf<Unit>, use: (A) -> BIOOf<E2, B>): BIO<E2, B> =
  BIOBracket(fix(), release, use)

/**
 * Meant for specifying tasks with safe resource acquisition and release in the face of errors and interruption.
 * It would be the the equivalent of `try/catch/finally` statements in mainstream imperative languages for resource
 * acquisition and release.
 *
 * @param release is the action that's supposed to release the allocated resource after `use` is done, irregardless
 * of its exit condition.
 *
 * ```kotlin:ank:playground
 * import arrow.fx.IO
 *
 * class File(url: String) {
 *   fun open(): File = this
 *   fun close(): Unit {}
 *   override fun toString(): String = "This file contains some interesting content!"
 * }
 *
 * fun openFile(uri: String): IO<File> = IO { File(uri).open() }
 * fun closeFile(file: File): IO<Unit> = IO { file.close() }
 * fun fileToString(file: File): IO<String> = IO { file.toString() }
 *
 * fun main(args: Array<String>) {
 *   //sampleStart
 *   val safeComputation = openFile("data.json").bracket({ file: File -> closeFile(file) }, { file -> fileToString(file) })
 *   //sampleEnd
 *   println(safeComputation.unsafeRunSync())
 * }
 * ```
 */
fun <E, E2 : E, A, B> BIOOf<E, A>.bracket(release: (A) -> IOOf<Unit>, use: (A) -> BIOOf<E2, B>): BIO<E2, B> =
  bracketCase({ a, _ -> release(a) }, use)

/**
 * Executes the given [finalizer] when the source is finished, either in success or in error, or if canceled.
 *
 * As best practice, prefer [bracket] for the acquisition and release of resources.
 *
 * @see [guaranteeCase] for the version that can discriminate between termination conditions
 * @see [bracket] for the more general operation
 */
fun <E, A> BIOOf<E, A>.guarantee(finalizer: IOOf<Unit>): BIO<E, A> =
  guaranteeCase { finalizer }

/**
 * Executes the given `finalizer` when the source is finished, either in success or in error, or if canceled, allowing
 * for differentiating between exit conditions. That's thanks to the [ExitCase] argument of the finalizer.
 *
 * As best practice, it's not a good idea to release resources via `guaranteeCase` in polymorphic code.
 * Prefer [bracketCase] for the acquisition and release of resources.
 *
 * @see [guarantee] for the simpler version
 * @see [bracketCase] for the more general operation
 *
 */
fun <E, A> BIOOf<E, A>.guaranteeCase(finalizer: (ExitCase<E>) -> IOOf<Unit>): BIO<E, A> =
  BIOBracket.guaranteeCase(fix(), finalizer)

/**
 * Create a new [IO] that upon execution starts the receiver [IO] within a [Fiber] on [ctx].
 *
 * ```kotlin:ank:playground
 * import arrow.fx.*
 * import arrow.fx.extensions.fx
 * import kotlinx.coroutines.Dispatchers
 *
 * fun main(args: Array<String>) {
 *   //sampleStart
 *   val result = IO.fx {
 *     val (join, cancel) = !IO.effect {
 *       println("Hello from a fiber on ${Thread.currentThread().name}")
 *     }.fork(Dispatchers.Default)
 *   }
 *
 *   //sampleEnd
 *   result.unsafeRunSync()
 * }
 * ```
 *
 * @receiver [IO] to execute on [ctx] within a new suspended [IO].
 * @param ctx [CoroutineContext] to execute the source [IO] on.
 * @return [IO] with suspended execution of source [IO] on context [ctx].
 */
fun <E, A> BIOOf<E, A>.fork(ctx: CoroutineContext): BIO<E, Fiber<BIOPartialOf<E>, A>> =
  BIO.Async { _, cb ->
    val promise = UnsafePromise<E, A>()
    val conn = IOConnection()
    IORunLoop.startCancelable(BIOForkedStart(this, ctx), conn, promise::complete)
    cb(BIOResult.Right(BIOFiber(promise, conn)))
  }

// IO API overloads

fun <A> Either<Nothing, A>.value(): A =
  when (this) {
    is Left -> TODO()
    is Either.Right -> this.b
  }

fun <A> IOOf<A>.unsafeRunAsyncCancellable(onCancel: OnCancel = Silent, cb: (Either<Throwable, A>) -> Unit): Disposable =
  fix().runAsyncCancellable(onCancel, cb.compose<BIOResult<Nothing, A>, Either<Throwable, A>, Unit> { it.toEither() }.andThen { IO.unit }).unsafeRunSync().value()

fun <A> IOOf<A>.unsafeRunAsync(cb: (Either<Throwable, A>) -> Unit): Unit =
  IORunLoop.start(fix(), cb.compose { it.toEither() })

fun <A> IOOf<A>.runAsync(cb: (Either<Throwable, A>) -> IOOf<Unit>): IO<Unit> =
  IO { fix().unsafeRunAsync(cb.andThen { it.fix().unsafeRunAsync { } }) }

fun <A> IOOf<A>.unsafeRunSync(): A =
  fix().unsafeRunTimed(Duration.INFINITE)
    .fold({ throw IllegalArgumentException("IO execution should yield a valid result") }, ::identity)
    .value()

/**
 * Redeem an [IO] to an [IO] of [B] by resolving the error **or** mapping the value [A] to [B].
 *
 * ```kotlin:ank:playground
 * import arrow.fx.IO
 *
 * fun main(args: Array<String>) {
 *   val result =
 *   //sampleStart
 *   IO.raiseError<Int>(RuntimeException("Hello from Error"))
 *     .redeem({ e -> e.message ?: "" }, Int::toString)
 *   //sampleEnd
 *   println(result.unsafeRunSync())
 * }
 * ```
 */
fun <A, B> IOOf<A>.redeem(ft: (Throwable) -> B, fb: (A) -> B): IO<B> =
  BIO.Bind(fix(), IOFrame.Companion.Redeem<Nothing, A, B>(ft, ::identity, fb))

/**
 * Redeem an [IO] to an [IO] of [B] by resolving the error **or** mapping the value [A] to [B] **with** an effect.
 *
 * ```kotlin:ank:playground
 * import arrow.fx.IO
 *
 * fun main(args: Array<String>) {
 *   val result =
 *   //sampleStart
 *   IO.just("1")
 *     .redeemWith({ e -> IO.just(-1) }, { str -> IO { str.toInt() } })
 *   //sampleEnd
 *   println(result.unsafeRunSync())
 * }
 * ```
 */
fun <A, B> IOOf<A>.redeemWith(fe: (Throwable) -> IOOf<B>, fb: (A) -> IOOf<B>): IO<B> =
  BIO.Bind(fix(), IOFrame.Companion.RedeemWith<Nothing, A, Nothing, B>(fe, ::identity, fb))

/**
 * Safely attempts the [IO] and lift any errors to the value side into [Either].
 *
 * ```kotlin:ank:playground
 * import arrow.fx.IO
 *
 * fun main(args: Array<String>) {
 *   //sampleStart
 *   val resultA = IO.raiseError<Int>(RuntimeException("Boom!")).attempt()
 *   val resultB = IO.just("Hello").attempt()
 *   //sampleEnd
 *   println("resultA: ${resultA.unsafeRunSync()}, resultB: ${resultB.unsafeRunSync()}")
 * }
 * ```
 *
 * @see flatMap if you need to act on the output of the original [IO].
 */
fun <A> IOOf<A>.attemptIO(): IO<Either<Throwable, A>> =
  BIO.Bind(fix(), IOFrame.attemptIO())

/**
 * Handle the error by mapping the error to a value of [A].
 *
 * ```kotlin:ank:playground
 * import arrow.fx.IO
 * import arrow.fx.handleError
 *
 * fun main(args: Array<String>) {
 *   //sampleStart
 *   val result = IO.raiseError<Int>(RuntimeException("Boom"))
 *     .handleError { e -> "Goodbye World! after $e" }
 *   //sampleEnd
 *   println(result.unsafeRunSync())
 * }
 * ```
 *
 * @see handleErrorWith for a version that can resolve the error using an effect
 */
fun <A> IOOf<A>.handleError(f: (Throwable) -> A): IO<A> =
  handleErrorWith { e -> BIO.Pure(f(e)) }

/**
 * Handle the error by resolving the error with an effect that results in [A].
 *
 * ```kotlin:ank:playground
 * import arrow.fx.IO
 * import arrow.fx.handleErrorWith
 * import arrow.fx.typeclasses.milliseconds
 *
 * fun main(args: Array<String>) {
 *   fun getMessage(e: Throwable): IO<String> = IO.sleep(250.milliseconds)
 *     .followedBy(IO.effect { "Delayed goodbye World! after $e" })
 *
 *   //sampleStart
 *   val result = IO.raiseError<Int>(RuntimeException("Boom"))
 *     .handleErrorWith { e -> getMessage(e) }
 *   //sampleEnd
 *   println(result.unsafeRunSync())
 * }
 * ```
 *
 * @see handleErrorWith for a version that can resolve the error using an effect
 */
fun <A> IOOf<A>.handleErrorWith(f: (Throwable) -> IOOf<A>): IO<A> =
  BIO.Bind(fix(), IOFrame.Companion.ErrorHandler<Nothing, A, Nothing>(f, ::identity))

internal fun <A, O> (((Either<Throwable, A>) -> Unit) -> O).toBIO(): ((BIOResult<Nothing, A>) -> Unit) -> O = { callback ->
  invoke { either ->
    when (either) {
      is Left -> callback(BIOResult.Error(either.a))
      is Either.Right -> callback(BIOResult.Right(either.b))
    }
  }
}
