/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.coroutines

import kotlin.contracts.*
import kotlin.coroutines.intrinsics.*
import kotlin.internal.InlineOnly

/**
 * 它是一个接口，表示了在挂起点(该挂起点返回类型为 `T` 的值)之后的 continuation。
 * Interface representing a continuation after a suspension point that returns a value of type `T`.
 */
@SinceKotlin("1.3")
public interface Continuation<in T> {
    /**
     * 与此 continuation 对应的协程的上下文。
     * The context of the coroutine that corresponds to this continuation.
     */
    public val context: CoroutineContext

    /**
     * 传递一个成功或失败的 [result] 作为最后一个挂起点的返回值，来恢复相应协程的执行。
     * Resumes the execution of the corresponding coroutine passing a successful or failed [result] as the
     * return value of the last suspension point.
     */
    public fun resumeWith(result: Result<T>)
}

/**
 * 标有此注解的类和接口，它们在被用作扩展 `suspend` 函数的 receivers 接收器时，会受到限制。
 * 这些 `suspend` 扩展只能调用此特定接收器上的其他成员或扩展 `suspend` 函数，并且被限制调用任意其他挂起函数。
 * Classes and interfaces marked with this annotation are restricted when used as receivers for extension
 * `suspend` functions. These `suspend` extensions can only invoke other member or extension `suspend` functions on this particular
 * receiver and are restricted from calling arbitrary suspension functions.
 */
@SinceKotlin("1.3")
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class RestrictsSuspension

/**
 * 恢复执行相应协程，传递一个作为最后一个挂起点的返回值 [value] 。
 * Resumes the execution of the corresponding coroutine passing [value] as the return value of the last suspension point.
 */
@SinceKotlin("1.3")
@InlineOnly
public inline fun <T> Continuation<T>.resume(value: T): Unit =
    resumeWith(Result.success(value))

/**
 * 恢复相应协程的执行，以便在最后一个挂起点之后立即重新抛出 [exception]。
 * Resumes the execution of the corresponding coroutine so that the [exception] is re-thrown right after the
 * last suspension point.
 */
@SinceKotlin("1.3")
@InlineOnly
public inline fun <T> Continuation<T>.resumeWithException(exception: Throwable): Unit =
    resumeWith(Result.failure(exception))


/**
 * 创建一个 [Continuation] 实例，这个实例指定了 [context] 和 [resumeWith] 方法的实现。
 * Creates a [Continuation] instance with the given [context] and implementation of [resumeWith] method.
 */
@SinceKotlin("1.3")
@InlineOnly
public inline fun <T> Continuation(
    context: CoroutineContext,
    crossinline resumeWith: (Result<T>) -> Unit
): Continuation<T> =
    object : Continuation<T> {
        override val context: CoroutineContext
            get() = context

        override fun resumeWith(result: Result<T>) =
            resumeWith(result)
    }

/**
 * 创建一个没有 receiver 接收者且结果类型为 [T] 的协程。
 * 每次调用此函数时，都会创建一个新的可被挂起的计算实例。
 * Creates a coroutine without a receiver and with result type [T].
 * This function creates a new, fresh instance of suspendable computation every time it is invoked.
 *
 * 要开始执行这个已创建的协程，请在返回的 [Continuation] 实例上调用 `resume(Unit)` 方法。
 * 当协程完成并产生一个结果或异常时，[completion] continuation 将被调用。
 * 在这个产生的 continuation 上，后续的任何 resume 函数调用，都将产生一个 [IllegalStateException] 异常。
 * To start executing the created coroutine, invoke `resume(Unit)` on the returned [Continuation] instance.
 * The [completion] continuation is invoked when the coroutine completes with a result or an exception.
 * Subsequent invocation of any resume function on the resulting continuation will produce an [IllegalStateException].
 */
@SinceKotlin("1.3")
@Suppress("UNCHECKED_CAST")
public fun <T> (suspend () -> T).createCoroutine(
    completion: Continuation<T>
): Continuation<Unit> =
    SafeContinuation(createCoroutineUnintercepted(completion).intercepted(), COROUTINE_SUSPENDED)

/**
 * 创建一个带有 receiver 接收者且结果类型为 [T] 的协程。
 * 每次调用此函数时，都会创建一个新的可被挂起的计算实例。
 * Creates a coroutine with receiver type [R] and result type [T].
 * This function creates a new, fresh instance of suspendable computation every time it is invoked.
 *
 * To start executing the created coroutine, invoke `resume(Unit)` on the returned [Continuation] instance.
 * The [completion] continuation is invoked when the coroutine completes with a result or an exception.
 * Subsequent invocation of any resume function on the resulting continuation will produce an [IllegalStateException].
 */
@SinceKotlin("1.3")
@Suppress("UNCHECKED_CAST")
public fun <R, T> (suspend R.() -> T).createCoroutine(
    receiver: R,
    completion: Continuation<T>
): Continuation<Unit> =
    SafeContinuation(createCoroutineUnintercepted(receiver, completion).intercepted(), COROUTINE_SUSPENDED)

/**
 * 启动一个没有 receiver 接收者且结果类型为 [T] 的协程。
 * 每次调用此函数时，它都会创建并启动一个新的可被挂起的计算实例。
 * 当协程完成并产生一个结果或异常时，[completion] continuation 将被调用。
 * Starts a coroutine without a receiver and with result type [T].
 * This function creates and starts a new, fresh instance of suspendable computation every time it is invoked.
 * The [completion] continuation is invoked when the coroutine completes with a result or an exception.
 */
@SinceKotlin("1.3")
@Suppress("UNCHECKED_CAST")
public fun <T> (suspend () -> T).startCoroutine(
    completion: Continuation<T>
) {
    createCoroutineUnintercepted(completion).intercepted().resume(Unit)
}

/**
 * 启动一个带有 receiver 接收者且结果类型为 [T] 的协程。
 * 每次调用此函数时，它都会创建并启动一个新的可被挂起的计算实例。
 * 当协程完成并产生一个结果或异常时，[completion] continuation 将被调用。
 * Starts a coroutine with receiver type [R] and result type [T].
 * This function creates and starts a new, fresh instance of suspendable computation every time it is invoked.
 * The [completion] continuation is invoked when the coroutine completes with a result or an exception.
 */
@SinceKotlin("1.3")
@Suppress("UNCHECKED_CAST")
public fun <R, T> (suspend R.() -> T).startCoroutine(
    receiver: R,
    completion: Continuation<T>
) {
    createCoroutineUnintercepted(receiver, completion).intercepted().resume(Unit)
}

/**
 * 获取当前挂起函数中的 continuation 实例并挂起当前正在运行的协程。
 * Obtains the current continuation instance inside suspend functions and suspends
 * the currently running coroutine.
 *
 * 在此函数中，[Continuation.resume] 和 [Continuation.resumeWithException]
 * 可以在运行挂起函数的同一堆栈帧中同步使用，也可以稍后在同一线程或不同的执行线程中异步使用。
 * 后续任何 resume 函数调用，都将产生一个 [IllegalStateException] 异常。
 * In this function both [Continuation.resume] and [Continuation.resumeWithException] can be used either synchronously in
 * the same stack-frame where the suspension function is run or asynchronously later in the same thread or
 * from a different thread of execution. Subsequent invocation of any resume function will produce an [IllegalStateException].
 */
@SinceKotlin("1.3")
@InlineOnly
public suspend inline fun <T> suspendCoroutine(crossinline block: (Continuation<T>) -> Unit): T {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    return suspendCoroutineUninterceptedOrReturn { c: Continuation<T> ->
        val safe = SafeContinuation(c.intercepted())
        block(safe)
        safe.getOrThrow()
    }
}

/**
 * 返回当前协程的上下文。
 * Returns the context of the current coroutine.
 */
@SinceKotlin("1.3")
@Suppress("WRONG_MODIFIER_TARGET")
@InlineOnly
public suspend inline val coroutineContext: CoroutineContext
    get() {
        throw NotImplementedError("Implemented as intrinsic")
    }