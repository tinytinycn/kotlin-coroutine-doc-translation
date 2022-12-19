/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

@file:kotlin.jvm.JvmName("IntrinsicsKt")
@file:kotlin.jvm.JvmMultifileClass

package kotlin.coroutines.intrinsics

import kotlin.contracts.*
import kotlin.coroutines.*
import kotlin.internal.InlineOnly

/**
 * 获取当前挂起函数内的 continuation 实例并挂起当前正在运行的协程
 * 或者立即返回结果而不挂起协程。
 * Obtains the current continuation instance inside suspend functions and either suspends
 * currently running coroutine or returns result immediately without suspension.
 *
 * 如果 [block] 返回特殊的 [COROUTINE_SUSPENDED] 值，则意味着挂起函数确实挂起执行并且不会立即返回任何结果。
 * 在这种情况下，提供给 [block] 的 [Continuation] 应通过在将来某个时刻(即当结果可用于恢复计算时)调用 [Continuation.resumeWith] 来恢复。
 * If the [block] returns the special [COROUTINE_SUSPENDED] value, it means that suspend function did suspend the execution and will
 * not return any result immediately. In this case, the [Continuation] provided to the [block] shall be
 * resumed by invoking [Continuation.resumeWith] at some moment in the
 * future when the result becomes available to resume the computation.
 *
 * 否则，[block] 的返回值必须具有可分配给 [T] 的类型，并表示此挂起函数的结果。
 * 这意味着执行没有被暂停，并且不应该调用提供给 [block] 的 [Continuation]。
 * 由于 [block] 的结果类型被声明为 `Any?` 并且无法进行正确的类型检查，因此其正确的返回类型仍然取决于挂起函数的作者意愿。
 * Otherwise, the return value of the [block] must have a type assignable to [T] and represents the result of this suspend function.
 * It means that the execution was not suspended and the [Continuation] provided to the [block] shall not be invoked.
 * As the result type of the [block] is declared as `Any?` and cannot be correctly type-checked,
 * its proper return type remains on the conscience of the suspend function's author.
 *
 * [Continuation.resumeWith] 的调用直接在调用者的线程中恢复协程，而无需经过协程的 [CoroutineContext] 中可能存在的 [ContinuationInterceptor]。
 * 确保建立正确的调用上下文是调用者的责任。 [Continuation.intercepted] 可用于获取截获的延续。
 * Invocation of [Continuation.resumeWith] resumes coroutine directly in the invoker's thread without going through the
 * [ContinuationInterceptor] that might be present in the coroutine's [CoroutineContext].
 * It is the invoker's responsibility to ensure that a proper invocation context is established.
 * [Continuation.intercepted] can be used to acquire the intercepted continuation.
 *
 * 请注意，不建议在运行挂起函数的同一堆栈帧中同步调用 [Continuation.resume] 和 [Continuation.resumeWithException] 函数。
 * 使用 [suspendCoroutine] 作为获取当前 continuation 实例的更安全方式。
 * Note that it is not recommended to call either [Continuation.resume] nor [Continuation.resumeWithException] functions synchronously
 * in the same stackframe where suspension function is run. Use [suspendCoroutine] as a safer way to obtain current
 * continuation instance.
 */
@SinceKotlin("1.3")
@InlineOnly
@Suppress("UNUSED_PARAMETER", "RedundantSuspendModifier")
public suspend inline fun <T> suspendCoroutineUninterceptedOrReturn(crossinline block: (Continuation<T>) -> Any?): T {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    throw NotImplementedError("Implementation of suspendCoroutineUninterceptedOrReturn is intrinsic")
}

/**
 * 该值用作 [suspendCoroutineUninterceptedOrReturn] `block` 参数的返回值，以声明执行已暂停并且不会立即返回任何结果。
 * This value is used as a return value of [suspendCoroutineUninterceptedOrReturn] `block` argument to state that
 * the execution was suspended and will not return any result immediately.
 *
 * **注意：这个值不应该用在常规代码中。**
 * 在 `suspendCoroutineUninterceptedOrReturn` 函数返回值的上下文之外使用它（包括但不限于将此值存储在其他属性中，从其他函数返回它等）可能会导致代码出现未指定的行为。
 * **Note: this value should not be used in general code.** Using it outside of the context of
 * `suspendCoroutineUninterceptedOrReturn` function return value  (including, but not limited to,
 * storing this value in other properties, returning it from other functions, etc)
 * can lead to unspecified behavior of the code.
 */
// 它作为带有 getter 的属性来实现，以避免 ProGuard <clinit> 与多文件 IntrinsicsKt 类的问题
// It is implemented as property with getter to avoid ProGuard <clinit> problem with multifile IntrinsicsKt class
@SinceKotlin("1.3")
public val COROUTINE_SUSPENDED: Any get() = CoroutineSingletons.COROUTINE_SUSPENDED

// 此处使用枚举可确保两个重要属性：
// 1. 它使 SafeContinuation 可与各种序列化框架一起序列化（因为 all of them 原生支持枚举）
// 2. 它改善了调试体验，因为您可以清楚地看到这些对象的 toString() 值以及它们来自哪个包
// Using enum here ensures two important properties:
//  1. It makes SafeContinuation serializable with all kinds of serialization frameworks (since all of them natively support enums)
//  2. It improves debugging experience, since you clearly see toString() value of those objects and what package they come from
@SinceKotlin("1.3")
@PublishedApi // This class is Published API via serialized representation of SafeContinuation, don't rename/move
internal enum class CoroutineSingletons { COROUTINE_SUSPENDED, UNDECIDED, RESUMED }