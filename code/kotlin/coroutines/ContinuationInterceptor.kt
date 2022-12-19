/*
 * Copyright 2010-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package kotlin.coroutines

/**
 * 标记协程上下文元素，此元素拦截了协程 continuations 。
 * 协程框架使用 [ContinuationInterceptor.Key] 来检索拦截器并
 * 使用 [interceptContinuation] 调用拦截所有协程 continuation 。
 * Marks coroutine context element that intercepts coroutine continuations.
 * The coroutines framework uses [ContinuationInterceptor.Key] to retrieve the interceptor and
 * intercepts all coroutine continuations with [interceptContinuation] invocations.
 *
 * [ContinuationInterceptor] 行为类似于一个 [polymorphic element][AbstractCoroutineContextKey]，这意味着
 * 它的实现将 [get][CoroutineContext.Element.get] 和 [minusKey][CoroutineContext.Element.minusKey] 分别委托给 [getPolymorphicElement] 和 [minusPolymorphicKey]。
 * [ContinuationInterceptor] 子类型可以使用 [ContinuationInterceptor.Key] 从协程上下文中提取 或者 如果它扩展了 [AbstractCoroutineContextKey] 从子类型 key 中提取。
 * [ContinuationInterceptor] behaves like a [polymorphic element][AbstractCoroutineContextKey], meaning that
 * its implementation delegates [get][CoroutineContext.Element.get] and [minusKey][CoroutineContext.Element.minusKey]
 * to [getPolymorphicElement] and [minusPolymorphicKey] respectively.
 * [ContinuationInterceptor] subtypes can be extracted from the coroutine context using either [ContinuationInterceptor.Key]
 * or subtype key if it extends [AbstractCoroutineContextKey].
 */
@SinceKotlin("1.3")
public interface ContinuationInterceptor : CoroutineContext.Element {
    /**
     * 定义上下文拦截器的 key 。
     * The key that defines *the* context interceptor.
     */
    companion object Key : CoroutineContext.Key<ContinuationInterceptor>

    /**
     * 返回一个包装了原始 [continuation] 的 continuation，从而拦截所有恢复。
     * 此函数在需要时由协程框架调用，并且生成的 continuations 都是每个原始 [continuation] 实例在内部缓存。
     * Returns continuation that wraps the original [continuation], thus intercepting all resumptions.
     * This function is invoked by coroutines framework when needed and the resulting continuations are
     * cached internally per each instance of the original [continuation].
     *
     * 如果这个函数不想拦截这个特定的 contiuation ，它可以简单地返回原始的 [continuation]。
     * This function may simply return original [continuation] if it does not want to intercept this particular continuation.
     *
     * 当原始 [continuation] 完成时，协程框架调用会 [releaseInterceptedContinuation]，并生成一个 continuation (如果它被拦截)。
     * 也就是说，如果 `interceptContinuation` 之前返回了一个不同的 continuation 实例。
     * When the original [continuation] completes, coroutine framework invokes [releaseInterceptedContinuation]
     * with the resulting continuation if it was intercepted, that is if `interceptContinuation` had previously
     * returned a different continuation instance.
     */
    public fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T>

    /**
     * 当原始 continuation 完成并且不再使用时，为 [interceptContinuation] 返回的 continuation 实例 而被调用。
     * 仅当 [interceptContinuation] 返回了与调用它的实例不同的 continuation 实例时，才会调用此函数。
     * Invoked for the continuation instance returned by [interceptContinuation] when the original
     * continuation completes and will not be used anymore. This function is invoked only if [interceptContinuation]
     * had returned a different continuation instance from the one it was invoked with.
     *
     * 默认实现什么也不做。
     * Default implementation does nothing.
     *
     * @param continuation 此拦截器的 [interceptContinuation] 调用并返回的 continuation 实例。
     * @param continuation Continuation instance returned by this interceptor's [interceptContinuation] invocation.
     */
    public fun releaseInterceptedContinuation(continuation: Continuation<*>) {
        /* do nothing by default */
    }

    public override operator fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? {
        // getPolymorphicKey specialized for ContinuationInterceptor key
        @OptIn(ExperimentalStdlibApi::class)
        if (key is AbstractCoroutineContextKey<*, *>) {
            @Suppress("UNCHECKED_CAST")
            return if (key.isSubKey(this.key)) key.tryCast(this) as? E else null
        }
        @Suppress("UNCHECKED_CAST")
        return if (ContinuationInterceptor === key) this as E else null
    }


    public override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext {
        // minusPolymorphicKey specialized for ContinuationInterceptor key
        @OptIn(ExperimentalStdlibApi::class)
        if (key is AbstractCoroutineContextKey<*, *>) {
            return if (key.isSubKey(this.key) && key.tryCast(this) != null) EmptyCoroutineContext else this
        }
        return if (ContinuationInterceptor === key) EmptyCoroutineContext else this
    }
}