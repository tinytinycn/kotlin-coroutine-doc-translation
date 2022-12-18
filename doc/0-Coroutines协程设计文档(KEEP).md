此文档是对 kotlin 官方 coroutines [设计文档](https://github.com/Kotlin/KEEP/blob/master/proposals/coroutines.md)的翻译。

----
# Kotlin Coroutines

* **Type**: 设计建议(Design proposal)
* **Authors**: Andrey Breslav, Roman Elizarov
* **Contributors**: Vladimir Reshetnikov, Stanislav Erokhin, Ilya Ryzhenkov, Denis Zharkov
* **Status**: 从 Kotlin 1.3 开始处于稳定状态(Revision 3.3), experimental in Kotlin 1.1-1.2

## 抽象Abstract

这些是在 Kotlin 中对 coroutines 协程的描述. 这些概念或多或少包含如下内容

- generators/yield
- async/await
- composable/delimited сontinuations

目标:

- 不依赖于某个 Futures 的特定实现或其他基于此Futures类丰富的库;
- 同样要涵盖 "async/await" 的用例和 "generator blocks";
- 可以利用 Kotlin 协程作为不同于现有异步 API 的wrapper包装器(例如 Java NIO, Futures的不同实现, 等等).

## 目录

* [Use cases](#use-cases)
    * [Asynchronous computations](#asynchronous-computations)
    * [Futures](#futures)
    * [Generators](#generators)
    * [Asynchronous UI](#asynchronous-ui)
    * [More use cases](#more-use-cases)
* [Coroutines overview](#coroutines-overview)
    * [Terminology](#terminology)
    * [Continuation interface](#continuation-interface)
    * [Suspending functions](#suspending-functions)
    * [Coroutine builders](#coroutine-builders)
    * [Coroutine context](#coroutine-context)
    * [Continuation interceptor](#continuation-interceptor)
    * [Restricted suspension](#restricted-suspension)
* [Implementation details](#implementation-details)
    * [Continuation passing style](#continuation-passing-style)
    * [State machines](#state-machines)
    * [Compiling suspending functions](#compiling-suspending-functions)
    * [Coroutine intrinsics](#coroutine-intrinsics)
* [Appendix](#appendix)
    * [Resource management and GC](#resource-management-and-gc)
    * [Concurrency and threads](#concurrency-and-threads)
    * [Asynchronous programming styles](#asynchronous-programming-styles)
    * [Wrapping callbacks](#wrapping-callbacks)
    * [Building futures](#building-futures)
    * [Non-blocking sleep](#non-blocking-sleep)
    * [Cooperative single-thread multitasking](#cooperative-single-thread-multitasking)
    * [Asynchronous sequences](#asynchronous-sequences)
    * [Channels](#channels)
    * [Mutexes](#mutexes)
    * [Migration from experimental coroutines](#migration-from-experimental-coroutines)
    * [References](#references)
    * [Feedback](#feedback)
* [Revision history](#revision-history)
    * [Changes in revision 3.3](#changes-in-revision-33)
    * [Changes in revision 3.2](#changes-in-revision-32)
    * [Changes in revision 3.1](#changes-in-revision-31)
    * [Changes in revision 3](#changes-in-revision-3)
    * [Changes in revision 2](#changes-in-revision-2)

## Use cases

一个 coroutine 协程可以被认为是一个 _suspendable可被挂起的计算_ 实例， 
也就是说，它可以在某些点(some points)被挂起(suspend)，随后可能在另一个线程上被恢复(resume)并执行起来。
Coroutines 协程之间相互调用（并来回传递数据）可以形成协作式(cooperative)多任务的机制。

### Asynchronous computations

第一具有激励性的 Coroutines 协程用例是异步计算（由 C# 和其他语言中的 async/await 处理）。
让我们来看看这种计算是如何通过回调 callbacks 完成的。作为一种启发，我们来看看异步I/O（下面的API是简化的）:

```kotlin
// 异步读入`buf`，完成后执行 lambda
inChannel.read(buf) {
    // 这个 lambda 在读取完成时执行
    bytesRead ->
    // ...
    // ...
    process(buf, bytesRead)
    
    // 从 `buf` 异步写入，完成后运行 lambda
    outChannel.write(buf) {
        // 这个 lambda 在写入完成时执行
        // ...
        // ...
        outFile.close()          
    }
}
```

请注意，我们在这里有一个 callback 回调中的 callback 回调，虽然它为我们省去了很多模板（例如，没有必要明确地将 `buf` 参数传递给回调，它们只是将其视为其闭包的一部分），
但缩进的级别每次都在增加，人们很容易预见到嵌套级别大于1时可能出现的问题（谷歌搜索 "回调地狱"，看看人们在JavaScript中是如何遭受这种困扰的）。

同样的计算可以直接表达为一个 coroutine 协程(只要有一个库可以使I/O API适应 coroutine 的要求):

```kotlin
launch {
    // 异步读取时挂起 suspend
    val bytesRead = inChannel.aRead(buf) 
    // 我们只有在读取完成后才能看到这一行
    // ...
    // ...
    process(buf, bytesRead)
    // 异步写入时挂起 suspend
    outChannel.aWrite(buf)
    // 我们只有在写入完成后才能看到这一行
    // ...
    // ...
    outFile.close()
}
```
`aRead()` 和 `aWrite()` 是特殊的 _suspending functions挂起函数_ —— 它们可以 _挂起suspend_ 代码执行（这并不意味着阻塞它所运行在的线程）并在调用完成后 _恢复resume_ 。
如果我们眯起眼睛，想象一下 `aRead()` 之后的所有代码都被包裹在一个lambda中，并作为回调传递给 `aRead()` ，而 `aWrite()` 也是如此，
我们可以看到这段代码和上面一样的效果，只是后者更容易阅读。

我们的明确目标是以非常通用的方式支持 coroutine ，所以在这个例子中，`launch{}` 、 `.aRead()` 和 `.aWrite()` 只是针对 coroutine 工作的 **library functions库函数**： 
`launch` 是 _coroutine builder协程构建器_ —— 它构建并启动 coroutine，而 `aRead/aWrite` 是隐含接收 _continuations_ 的特殊 _suspending functions挂起函数_（ continuations 只是通用回调）。


> The example code for `launch{}` is shown in [coroutine builders](#coroutine-builders) section, and
the example code for `.aRead()` is shown in [wrapping callbacks](#wrapping-callbacks) section.

注意，在一个循环的中间进行一个异步调用(这个异步调用有显式地传递回调函数)可能很棘手，但在一个 coroutine 中，这是一个完全正常的事情:

```kotlin
launch {
    while (true) {
        // suspend while asynchronously reading
        val bytesRead = inFile.aRead(buf)
        // continue when the reading is done
        if (bytesRead == -1) break
        // ...
        process(buf, bytesRead)
        // suspend while asynchronously writing
        outFile.aWrite(buf) 
        // continue when the writing is done
        // ...
    }
}
```

可以想象，在一个 coroutine 中处理异常也会更方便一些。

### Futures

还有一种表达异步计算的方式：通过 futures（也被称为 promises 或 deferreds ）。我们将在这里使用一个假想的 API，将一个 overlay 应用到一个图像上:

```kotlin
val future = runAfterBoth(
    loadImageAsync("...original..."), // creates a Future 
    loadImageAsync("...overlay...")   // creates a Future
) {
    original, overlay ->
    // ...
    applyOverlay(original, overlay)
}
```

如果有 coroutines, 这可以改写为:

```kotlin
val future = future {
    val original = loadImageAsync("...original...") // creates a Future
    val overlay = loadImageAsync("...overlay...")   // creates a Future
    // ...
    // suspend while awaiting the loading of the images
    // then run `applyOverlay(...)` when they are both loaded
    applyOverlay(original.await(), overlay.await())
}
```

> The example code for `future{}` is shown in [building futures](#building-futures) section, and
the example code for `.await()` is shown in [suspending functions](#suspending-functions) section.

同样，更少的缩进和更自然的组成逻辑（以及异常处理，这里没有显示），没有特殊的关键字（像C#、JS和其他语言中的 `async` 和 `await` ）来支持 futures：`future{}` 和 `.await()` 只是一个库中的函数。

### Generators

Coroutines 的另一个典型用例是懒加载的计算序列（在 C#、Python 和许多其他语言中由 `yield` 处理）。这样的序列可以由看似有顺序的代码生成，但在运行时只计算被要求的元素:

```kotlin
// inferred type is Sequence<Int>
val fibonacci = sequence {
    yield(1) // first Fibonacci number
    var cur = 1
    var next = 1
    while (true) {
        yield(next) // next Fibonacci number
        val tmp = cur + next
        cur = next
        next = tmp
    }
}
```

这段代码创建了一个[斐波那契数](https://en.wikipedia.org/wiki/Fibonacci_number)的惰性 `Sequence序列` ，
它可能是无限的（就像 [Haskell的无限列表](http://www.techrepublic.com/article/infinite-list-tricks-in-haskell/)）。
我们可以通过 `take()` 请求其中的一部分:

```kotlin
println(fibonacci.take(10).joinToString())
```

> This will print `1, 1, 2, 3, 5, 8, 13, 21, 34, 55`
You can try this code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/sequence/fibonacci.kt)

生成器 generators 的优势在于支持任意的控制流，如 `while`（来自上面的例子）、`if`、`try`/`catch`/`finally` 和其他所有的东西:

```kotlin
val seq = sequence {
    yield(firstItem) // suspension point

    for (item in input) {
        if (!item.isValid()) break // don't generate any more items
        val foo = item.toFoo()
        if (!foo.isGood()) continue
        yield(foo) // suspension point        
    }
    
    try {
        yield(lastItem()) // suspension point
    }
    finally {
        // some finalization code
    }
} 
```

> The example code for `sequence{}` and `yield()` is shown in
[restricted suspension](#restricted-suspension) section.

请注意，这种方法也允许将 `yieldAll(sequence)` 表达为一个库函数（如同 `sequence{}` 和 `yield()` 那样），这简化了连接(join)惰性序列的过程，并允许高效的实现。

### Asynchronous UI

一个典型的 UI 应用程序有一个单一的事件调度线程，所有的UI操作都发生在这里。从其他线程修改UI状态通常是不允许的。
所有 UI 库都提供某种原语来将代码执行移回 UI 线程进行。例如，
Swing 有 [`SwingUtilities.invokeLater`](https://docs.oracle.com/javase/8/docs/api/javax/swing/SwingUtilities.html#invokeLater-java.lang.Runnable-)，
JavaFX 有 [`Platform.runLater`](https://docs.oracle.com/javase/8/javafx/api/javafx/application/Platform.html#runLater-java.lang.Runnable-)，
Android 有 [`Activity.runOnUiThread`](https://developer.android.com/reference/android/app/Activity.html#runOnUiThread(java.lang.Runnable))，等等。
下面是一个典型的 Swing 应用程序的代码片段，它做了一些异步操作，然后在 UI 中显示其结果: 

```kotlin
makeAsyncRequest {
    // this lambda is executed when the async request completes
    result, exception ->
    
    if (exception == null) {
        // display result in UI
        SwingUtilities.invokeLater {
            display(result)   
        }
    } else {
       // process exception
    }
}
```

这类似于我们在 [asynchronous computations](#asynchronous-computations) 用例中看到的回调地狱，它也被 coroutines 优雅地解决了:

```kotlin
launch(Swing) {
    try {
        // suspend while asynchronously making request
        val result = makeRequest()
        // display result in UI, here Swing context ensures that we always stay in event dispatch thread
        display(result)
    } catch (exception: Throwable) {
        // process exception
    }
}
```

> The example code for `Swing` context is shown in the [continuation interceptor](#continuation-interceptor) section.

所有的异常处理都是使用自然语言结构进行的。

### More use cases

Coroutines 可以覆盖更多的用例，包括这些:

* Channel-based concurrency (aka goroutines and channels);
* Actor-based concurrency;
* Background processes occasionally requiring user interaction, e.g., show a modal dialog;
* Communication protocols: implement each actor as a sequence rather than a state machine;
* Web application workflows: register a user, validate email, log them in
  (a suspended coroutine may be serialized and stored in a DB).

## Coroutines overview

本节概述了实现编写 coroutines 程序的语言机制和管理 coroutines 其语义的标准库。

### Terminology

* A _coroutine_ —— 是 _suspendable computation可被挂起的计算_ 的一个 _instance实例_ 。它在概念上类似于线程，因为它需要一个 block 代码块来运行，
   并具有类似的生命周期。—— 比如 _created_ 和 _started_ ，但它没有被绑定到任何特定的线程。
   它可以在一个线程中 _suspend挂起_ 它的执行，在另一个线程中 _resume恢复_ 它的执行。
   此外，像 future 或 promise 一样，它可以 _complete完成_ 一些结果（要么是一个 value 值，要么是一个 exception 异常）。

* A _suspending function_ —— 一个被标记了 `suspend` 修饰符的 function 函数。它可以通过调用其他 suspending functions，在不阻塞当前执行线程的情况下，_suspend挂起_ 代码的执行。
   一个 suspending function 挂起函数不能在普通代码中被调用，只能从其他 suspending functions 和 suspending 的 lambdas 中调用（见下文）。
   例如，`.await()` 和 `yield()` ，如[use cases](#use-cases)，它们是被定义在库中的 suspending functions 挂起函数。
   标准库提供了原始的 suspending functions 挂起函数，用于定义所有其他 suspending functions 挂起函数。

* A _suspending lambda_ —— 一个必须在协程中运行的代码块。
   它看起来和普通的 [lambda 表达式](https://kotlinlang.org/docs/reference/lambdas.html)一模一样，但它的函数类型标有 `suspend` 修饰符。
   就像常规 lambda 表达式是匿名本地函数的短句法形式一样，suspending lambda 是匿名 suspending function 的短句法形式。
   它可以通过调用 suspending function _suspend挂起_ 代码的执行而不阻塞当前执行线程。 
   例如，如[use cases](#use-cases)所示，`launch`、`future` 和 `sequence` 函数后的花括号中的代码块是 suspending lambdas。

   > 注意：常规 lambda 可以在其代码的所有位置(来自此 lambda 的 non-local `return`语句)允许调用挂起函数。
  (原文：Regular lambdas may invoke suspending functions in all places of their code where a
   [non-local](https://kotlinlang.org/docs/reference/returns.html) `return` statement
   from this lambda is allowed. ) 也就是说，允许在像 [`apply{}` block](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin/apply.html) 
   这样的 inline lambda 中调用 suspending function，
   但不允许在 `noinline` 和 `crossinline` lambda 表达式中调用 suspending function 。
   _suspension_ 被视为一种特殊的 non-local 控制转移。(原文:A _suspension_ is treated as a special kind of non-local control transfer.)

* A _suspending function type_ —— 是一种可用于 suspending functions 和 lambdas 的函数类型。
   就像常规的 [function type](https://kotlinlang.org/docs/reference/lambdas.html#function-types),
   只是带有 `suspend` 修饰符而已。例如，`suspend () -> Int` 是一个 suspending可被挂起的函数类型，它无参数输入并返回一个 `Int` 类型. 
   被声明成 `suspend fun foo() : Int` suspending function符合上述函数类型。

* A _coroutine builder_ —— 是一个函数，它将一些 _suspending lambda_ 作为参数，创建一个协程，并且可选择以某种形式访问它的 result结果。
   如[use cases](#use-cases)所示的 `launch{}`、 `future{}` 和 `sequence{}` ，都是 coroutine builders 协程构建器。
   标准库提供了用于定义所有其他协程构建器的原始协程构建器(primitive coroutine builders)。

   > 注意: 一些语言对 create 和 start 一个协程的特定方式有硬编码(hard-coded)支持，这种方式定义了它们的执行和结果的呈现方式。
   例如，`generate` 关键字可以定义一个返回某种可迭代对象的协程，而 `async` 关键字可以定义一个返回某种 promise 或 task 的协程。
   Kotlin 没有关键字或修饰符来定义和启动一个协程。 Coroutine builders 协程构建器只是在库中定义的简单函数。
   在其他语言中，协程的定义采用方法体(method body)形式，而在 Kotlin 中，这种形式通常是具有表达式体(expression body)的常规方法，
   包括调用某些库定义的 coroutine builder 协程构建器，其最后一个参数是  suspending lambda :

   ```kotlin
   fun doSomethingAsync() = async { //... }
   ```

* A _suspension point_ —— 是在协程执行期间，一个协程的代码执行 _可能被挂起的_ 点(point，后面统一翻译成：挂起点)。
   从语法上讲，一个挂起点就是对挂起函数的调用，但 _真正的_ 挂起发生在挂起函数调用标准库原语时挂起执行。

* A _continuation_ — 是一个已经被挂起的协程在挂起点的状态。在概念上它代表了挂起点之后的剩余代码执行(原文: the rest of its execution after the suspension point)。例如: 

   ```kotlin
   sequence {
       for (i in 1..10) yield(i * i)
       println("over")
   }  
   ```  

   在这里，每次协程在调用挂起函数 `yield()` 后被挂起，它的剩余代码执行都表示为一个 continuation ，所以我们有 10 个 continuations ：
   first runs the loop with `i = 2` and suspends, second runs the loop with `i = 3` and suspends, etc,
   the last one prints "over" and completes the coroutine. The coroutine that is _created_, but is not
   _started_ yet, is represented by its _initial continuation_ of type `Continuation<Unit>` that consists of
   its whole execution.

如上所述，协程的驱动要求之一是灵活性：我们希望能够支持许多现有的异步 API 和其他用例，并尽量减少硬编码到编译器中的部分。
因此，编译器只负责支持 suspending functions、suspending lambdas 和相应的 suspending 函数类型。
标准库中的原语很少，其余的留给应用程序库来实现。

### Continuation interface

下面是标准库接口的定义 [`Continuation`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-continuation/index.html) 
（在 kotlin.coroutines 包中定义），它代表一个通用回调(generic callback)：

```kotlin
interface Continuation<in T> {
   val context: CoroutineContext
   fun resumeWith(result: Result<T>)
}
```

context 上下文在 [coroutine context](#coroutine-context) 部分中有详细介绍，它表示与协程关联的任意用户定义的上下文。 
`resumeWith` 函数是一个完成时回调函数，用于在协程完成时报告成功（带有一个值）或失败（带有一个异常）。

为了方便起见，在同一个包中还定义了两个扩展函数:

```kotlin
fun <T> Continuation<T>.resume(value: T)
fun <T> Continuation<T>.resumeWithException(exception: Throwable)
```

### Suspending functions

像 `.await()` 这样的典型挂起函数的实现，如下所示：

```kotlin
suspend fun <T> CompletableFuture<T>.await(): T =
    suspendCoroutine<T> { cont: Continuation<T> ->
        whenComplete { result, exception ->
            if (exception == null) // the future has been completed normally
                cont.resume(result)
            else // the future has completed with an exception
                cont.resumeWithException(exception)
        }
    }
``` 

> 您可以在[此处](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/future/await.kt)获取代码。
注意: 如果 future 永远不完成，这个简单的实现将永远挂起协程。在 [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) 中
的实际实现是支持取消的。

`suspend` 修饰符表示这是一个可以挂起协程代码执行的函数。
这个特定的函数被定义为 CompletableFuture<T> 类型的[extension function](https://kotlinlang.org/docs/reference/extensions.html)，
因此它的用法自然地按照与实际执行顺序相对应的从左到右的顺序阅读:

```kotlin
doSomethingAsync(...).await()
```

修饰符 `suspend` 可用于任何函数：顶级函数、扩展函数、成员函数、局部函数或运算符函数。

> 属性的 getters and setters, constructors函数 和某些 operators functions
(即 `getValue`, `setValue`, `provideDelegate`, `get`, `set`, and `equals`) 不能有 `suspend` 修饰符。
将来可能会取消这些限制。

挂起函数可以调用任何常规函数，但要真正的挂起代码执行，它们必须调用其他一些挂起函数。
特别是，这个 `await` 的实现调用的是标准库（在 `kotlin.coroutines` 包中）中定义的挂起函数 [`suspendCoroutine`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/suspend-coroutine.html) 
用来作为顶级挂起函数：

```kotlin
suspend fun <T> suspendCoroutine(block: (Continuation<T>) -> Unit): T
```

当 `suspendCoroutine` 在协程内部被调用时（而且它 _只能_ 在协程内部调用，因为它是一个挂起函数）
它会捕获一个 _continuation_ 实例中协程的执行状态，并将这个 continuation 作为参数传递给指定的 `block` 块。
为了恢复协程的代码执行，该 block 块在稍后的某个时间在此线程或其他线程中调用 `continuation.resumeWith()`
（直接或使用 `continuation.resume()` 或 `continuation.resumeWithException()` 扩展方法）。
当 `suspendCoroutine` block 块被返回而不调用 `resumeWith` 时，协程的 _实际_ 挂起发生了。
如果在 block 块内部返回之前，continuation 被恢复了，则协程不被视为已挂起并继续执行代码。

传递给 `continuation.resumeWith()` 的 result 结果成为 suspendCoroutine 调用的 result 结果，而后者又成为 `.await()` 的结果。

恢复多次相同的 continuation 是不允许的，并产生 `IllegalStateException`。

> 注意：这是 Kotlin 中的协程与函数式语言（如 Scheme 中的 first-class delimited continuations）或 Haskell 中的 continuation monad 之间的主要区别。
Kotlin 选择只支持 resume-once continuations 纯粹是务实的，因为预期的[use cases](#use-cases)都不需要 multi-shot continuations。
但是，通过使用 low-level [coroutine intrinsics](#coroutine-intrinsics) 挂起协程和克隆在 continuation 中捕获的协程状态，
可以将 multi-shot continuations 作为单独的库实现，以便其克隆可以再次被恢复(原文: so that its clone can be resumed again)。

### Coroutine builders

由于挂起函数不能在常规函数中被调用，因此标准库提供了从常规 non-suspending scope 非挂起作用域启动协程的函数。
下面是一个 `launch{}` _coroutine builder协程构建器_ 的简单实现:

```kotlin
fun launch(context: CoroutineContext = EmptyCoroutineContext, block: suspend () -> Unit) =
    block.startCoroutine(Continuation(context) { result ->
        result.onFailure { exception ->
            val currentThread = Thread.currentThread()
            currentThread.uncaughtExceptionHandler.uncaughtException(currentThread, exception)
        }
    })
```

> 你可以在 [这里](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/run/launch.kt) 获取代码。

此实现使用 [`Continuation(context) { ... }`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-continuation.html) 函数（来自 kotlin.coroutines 包），
该函数提供了一种快捷方式来实现 `Continuation` 接口，并为其 `context` 上下文和 `resumeWith` 函数的主体提供给定值。
此 continuation 作为 一个 _completion continuation_ 传递给 [`block.startCoroutine(...)`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/start-coroutine.html) 
扩展函数（来自 kotlin.coroutines 包）。

协程的完成会调用其 completion continuation (原文: The completion of coroutine invokes its completion continuation.)。
在协程完成时不管成功或失败，continuation 的 `resumeWith` 函数都会被调用。
因为 `launch` 执行 “fire-and-forget即发即弃” 的协程，它被定义为挂起具有 `Unit` 返回类型的函数，实际上在它的 `resume` 函数中会忽略了这个结果。
如果协程执行异常完成，则使用当前线程的 uncaught exception handler 未捕获异常处理程序来报告它。

> 注意：这个简单的实现返回 Unit 并且根本不提供对协程状态的访问。
[kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) 中的实际实现更为复杂，
因为它返回一个 Job 接口的实例，代表一个协程并且可以被取消。

[coroutine context](#coroutine-context)部分详细介绍了 context 上下文。
`startCoroutine` 在标准库中定义为无参数和单参数挂起函数类型的扩展：

```kotlin
fun <T> (suspend  () -> T).startCoroutine(completion: Continuation<T>)
fun <R, T> (suspend  R.() -> T).startCoroutine(receiver: R, completion: Continuation<T>)
```

`startCoroutine` 创建协程并立即在当前线程中开始执行（但请参阅下面的注释），直到第一个挂起点，然后返回。
挂起点就是对协程体中某个[suspending function](#suspending-functions)的调用，由相应挂起函数的代码来定义何时以及如何恢复协程执行。

> 注意：[稍后介绍](#continuation-interceptor)的 continuation interceptor（来自 context 上下文）可以将协程的执行（ _包括_ 其初始 continuation ）分派到另一个线程中。

### Coroutine context

协程上下文是一组可持化的用户定义对象(user-defined objects)的集合，可以附加到协程。它可能包括负责协程线程策略、日志记录、协程执行的安全性和事务切面、协程身份和名称等的对象。
下面是一个协程及其上下文的简单心理模型(simple emntal model)的理解。将协程视为轻量级线程。在这种情况下，协程上下文就像 thread-local 线程局部变量的集合。
区别在于线程局部变量是可变的，而协程上下文是不可变的，这对协程来说并不是一个严重的限制，因为它们非常轻量级，
所以当需要改变上下文中任何东西时很容易启动一个新的协程来满足。

标准库不包含上下文元素的任何具体实现，但具有接口和抽象类，因此所有这些方面都可以 _composeable以可组合的_ 方式在库中定义，
这样来自不同库的方面可以作为同一上下文的元素和平共存。

从概念上讲，协程上下文是一组可被索引的元素 set 集合，其中每个元素都有一个唯一的键。它是 set 和 map 的结合。
它的元素都有 key ，就像 map 一样，但它的键直接与元素相关联，更像是在 set 中。
标准库定义了 [`CoroutineContext`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-coroutine-context/index.html) 的最小接口
（在 kotlin.coroutines 包中）:

```kotlin
interface CoroutineContext {
    operator fun <E : Element> get(key: Key<E>): E?
    fun <R> fold(initial: R, operation: (R, Element) -> R): R
    operator fun plus(context: CoroutineContext): CoroutineContext
    fun minusKey(key: Key<*>): CoroutineContext

    interface Element : CoroutineContext {
        val key: Key<*>
    }

    interface Key<E : Element>
}
```

`CoroutineContext` 本身有四个可用的核心操作：

* Operator [`get`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-coroutine-context/get.html)
  provides type-safe access to an element for a given key. It can be used with `[..]` notation
  as explained in [Kotlin operator overloading](https://kotlinlang.org/docs/reference/operator-overloading.html).
* Function [`fold`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-coroutine-context/fold.html)
  works like [`Collection.fold`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/fold.html)
  extension in the standard library and provides means to iterate all elements in the context.
* Operator [`plus`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-coroutine-context/plus.html)
  works like [`Set.plus`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/plus.html)
  extension in the standard library and returns a combination of two contexts with elements on the right-hand side
  of plus replacing elements with the same key on the left-hand side.
* Function [`minusKey`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-coroutine-context/minus-key.html)
  returns a context that does not contain a specified key.

一个协程上下文的 [`Element`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-coroutine-context/-element/index.html) 就是上下文本身。
它是一个仅包含此元素的单例上下文。这允许通过获取协程上下文元素的库定义并使用 `+` 连接它们来创建收合上下文。
例如，如果一个库定义了带有用户授权信息的 `auth` 元素，而其他一些库定义了带有一些执行上下文信息的 `threadPool` 对象，
那么您可以使用 `launch{}` [coroutine builder](#coroutine-builders)和使用 `launch(auth + threadPool) {...}`的调用来组合上下文。

> 注意：[kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) 提供了几个上下文元素，
包括 `Dispatchers.Default` 对象，该对象将协程的执行分派到后台线程的共享池中。

标准库提供了 [`EmptyCoroutineContext`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-empty-coroutine-context/index.html)
—— 没有任何元素（empty）的 CoroutineContext 实例。

所有第三方上下文元素都应扩展标准库（在 `kotlin.coroutines` 包中）
提供的 [`AbstractCoroutineContextElement`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-abstract-coroutine-context-element/index.html) 类。
对于库中定义的上下文元素，建议使用以下样式。下面的示例显示了一个假设的授权上下文元素，它存储当前用户名：

```kotlin
class AuthUser(val name: String) : AbstractCoroutineContextElement(AuthUser) {
    companion object Key : CoroutineContext.Key<AuthUser>
}
```

> 这个例子可以在 [这里](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/context/auth.kt) 找到。

将上下文 `Key` 定义为相应元素类的伴生对象，可以流畅地访问 context 的相应元素。
下面是一个需要检查当前用户名称的挂起函数的假设实现：

```kotlin
suspend fun doSomething() {
    val currentUser = coroutineContext[AuthUser]?.name ?: throw SecurityException("unauthorized")
    // do something user-specific
}
```

它使用一个（来自 kotlin.coroutines 包）在挂起函数中可用的top-level 顶级属性 [`coroutineContext`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/coroutine-context.html) 属性
来检索当前协程的上下文。

### Continuation interceptor

让我们回顾一下[asynchronous UI](#asynchronous-ui) 用例。异步 UI 应用程序必须确保协程体本身始终在 UI 线程中执行，尽管各种挂起函数会在任意线程中恢复协程执行。
这是使用 _continuation interceptor_ 完成的。首先，我们需要充分了解协程的生命周期。考虑一段使用 [`launch{}`](#coroutine-builders) 协程构建器的代码：

```kotlin
launch(Swing) {
    initialCode() // execution of initial code
    f1.await() // suspension point #1
    block1() // execution #1
    f2.await() // suspension point #2
    block2() // execution #2
}
```

协程从执行其 `initialCode` 开始，直到第一个挂起点。在挂起点 _挂起_ 协程，一段时间后，根据相应的挂起函数的定义，它 _恢复_ 执行 `block1`，
然后它再次挂起并恢复执行 `block2`，之后 _完成_ 。

Continuation 拦截器有一个选项可以拦截并包装对应于 `initialCode`、`block1` 和 `block2` 的执行的 continuation ，
从它们的恢复到后续的挂起点。协程的初始代码可以被视为其 _initial continuation_ 的 resumption 恢复过程。
标准库提供了 [`ContinuationInterceptor`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/-continuation-interceptor/index.html) 接口
（在 `kotlin.coroutines` 包中）：

```kotlin
interface ContinuationInterceptor : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ContinuationInterceptor>
    fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T>
    fun releaseInterceptedContinuation(continuation: Continuation<*>)
}
 ```

`interceptContinuation` 函数包装了协程的 continuation 。每当协程被挂起时，协程框架使用以下代码行来包装实际的 `continuaiton` 以供后续恢复：

```kotlin
val intercepted = continuation.context[ContinuationInterceptor]?.interceptContinuation(continuation) ?: continuation
```

协程框架为每个实际的 continuation 实例缓存生成的 intercepted continuation ，并在不再需要时候调用 `releaseInterceptedContinuation(intercepted)`。
有关详细信息，请参阅[implementation details](#implementation-details)部分。

> 请注意，像 `await` 这样的挂起函数实际上可能会也可能不会挂起协程的执行。
例如，当 `future` 已经完成时，正如[suspending functions](#suspending-functions)部分中展示的 `await` 的实现实际上并没有挂起协程
（在这种情况下，它会立即调用 `resume` 并继续执行而不会实际挂起）。
仅当实际挂起发生在协程执行期间时，即当 `suspendCoroutine` block 返回而不调用 `resume` 时，`continuation` 才会被拦截。

让我们看一下 `Swing` 拦截器将执行分派到 Swing UI event dispatch 线程的具体示例代码。
我们从一个 `SwingContinuation` 包装类的定义开始，该类使用 `SwingUtilities.invokeLater` 将延续分派到 Swing event dispatch 线程：

```kotlin
private class SwingContinuation<T>(val cont: Continuation<T>) : Continuation<T> {
    override val context: CoroutineContext = cont.context
    
    override fun resumeWith(result: Result<T>) {
        SwingUtilities.invokeLater { cont.resumeWith(result) }
    }
}
```

然后，定义将作为相应上下文元素的 `Swing` 对象并实现 `ContinuationInterceptor` 接口：
Then define `Swing` object that is going to serve as the corresponding context element and implement
`ContinuationInterceptor` interface:

```kotlin
object Swing : AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
    override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> =
        SwingContinuation(continuation)
}
```

> 你可以在 [这里](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/context/swing.kt)获取此代码。
注意：[kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) 中 `Swing` 对象的实际实现也支持协程调试工具，
这些工具在 "当前运行该协程的线程的名称中" 提供并显示当前正在运行的协程的标识符。

现在，可以使用带有 `Swing` 参数的 `launch{}` [coroutine builder](#coroutine-builders) 来执行一个完全在 Swing 事件调度线程中运行的协程：

 ```kotlin
launch(Swing) {
   // code in here can suspend, but will always resume in Swing EDT
}
```

> [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) 中 `Swing` 上下文的实际实现更为复杂，因为它与库的时间和调试工具集成在一起。

### Restricted suspension

需要一种不同类型的协程构建器和挂起函数，来实现[generators](#generators)用例中的 `sequence{}` 和 `yield()`。
以下是 `sequence{}` 协程构建器的示例代码：

```kotlin
fun <T> sequence(block: suspend SequenceScope<T>.() -> Unit): Sequence<T> = Sequence {
    SequenceCoroutine<T>().apply {
        nextStep = block.createCoroutine(receiver = this, completion = this)
    }
}
```

它使用与标准库不同的原语，称为 [`createCoroutine`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines/create-coroutine.html)，
它类似于 `startCoroutine`（在[coroutine builders](coroutine-builders)部分中有解释）。
但是它创建了一个协程，但没有启动它。相反，它返回其 _initial continuation_ 作为对 `Continuation<Unit>` 的引用：

```kotlin
fun <T> (suspend () -> T).createCoroutine(completion: Continuation<T>): Continuation<Unit>
fun <R, T> (suspend R.() -> T).createCoroutine(receiver: R, completion: Continuation<T>): Continuation<Unit>
```

另一个区别是，此构建器的 _suspending lambda_ `block` 是带有 SequenceScope<T> 接收器
的[_extension lambda_](https://kotlinlang.org/docs/reference/lambdas.html#function-literals-with-receiver)。 
`SequenceScope` 接口为生成器的 block 提供作用域，并在库中定义为：

```kotlin
interface SequenceScope<in T> {
    suspend fun yield(value: T)
}
```

为了避免多个对象的创建，`sequence{}` 的实现它定义了 实现 `SequenceScope<T>` 接口 和 `Continuation<Unit>` 两个接口的 SequenceCoroutine<T> 类，
因此它既可以作为 `createCoroutine` 的接收者参数，也可以作为其 `completion` continuation 的参数。 `SequenceCoroutine<T>` 的简单实现如下所示

```kotlin
private class SequenceCoroutine<T>: AbstractIterator<T>(), SequenceScope<T>, Continuation<Unit> {
    lateinit var nextStep: Continuation<Unit>

    // AbstractIterator implementation
    override fun computeNext() { nextStep.resume(Unit) }

    // Completion continuation implementation
    override val context: CoroutineContext get() = EmptyCoroutineContext

    override fun resumeWith(result: Result<Unit>) {
        result.getOrThrow() // bail out on error
        done()
    }

    // Generator implementation
    override suspend fun yield(value: T) {
        setNext(value)
        return suspendCoroutine { cont -> nextStep = cont }
    }
}
```

> 你可以在 [这里](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/sequence/sequence.kt) 获取代码。
请注意，标准库提供了此[`sequence`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/sequence.html)函数
（在 kotlin.sequences 包中）的开箱即用优化实现，并额外支持 [`yieldAll`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence-scope/yield-all.html) 函数。

> `sequence` 的实际代码使用了实验性的 `BuilderInference` 特性，该特性支持 `fibonacci` 的声明，如[generators](#generators) 部分所示，
而无需显式指定序列类型参数 `T` 。相反，它是从传递给 `yield` 调用的类型中推断出来的。

`yield` 的实现使用 `suspendCoroutine` [suspending function](#suspending-functions)来挂起协程并捕获其 continuation 。
Continuation 存储为 `nextStep`，以便在调用 `computeNext` 时恢复。

但是，如上所示，`sequence{}` 和 `yield()` 尚未准备好让任意挂起函数在其作用域内捕获 continuation 。它们 _同步_ 工作。
他们需要对如何捕获 continuation 、存储在何处以及何时恢复进行绝对控制。它们形成 _restricted suspension scope_ 。
限制挂起能力是由放置在 scope 作用域类或接口上的 `@RestrictsSuspension` 注释提供，
在上面的示例中，此 scope 作用域接口是 `SequenceScope`：

```kotlin
@RestrictsSuspension
interface SequenceScope<in T> {
    suspend fun yield(value: T)
}
```

此注释对可以对在 `sequence{}` 或类似同步协程构建器的作用域内使用的挂起函数实施某些限制。
任何扩展挂起 lambda 或具有 _受限挂起作用域_ 类或接口（标有 `@RestrictsSuspension` ）作为其接收者的函数称为 _restricted suspending function_ 受限挂起函数。
受限挂起函数只能在其受限挂起作用域的同一实例上，调用成员或扩展挂起函数。

特别注意的是，这意味着任何 `SequenceScope` 的 lambda 扩展在其作用域内都不能调用 `suspendContinuation` 或其他通用挂起函数。
要挂起 `sequene` 协程的执行，它们最终必须调用 `SequenceScope.yield` 。 
`yield` 的实现本身是 SequenceScope 实现的一个成员函数，它没有任何限制（只有 _extension_ 挂起 lambdas 和函数受到限制）。

对于像 `sequene` 这样的受限协程构建器支持任意上下文是没有意义的，由于它们的作用域类或接口（本例中的 `SequenceScope`）作为一个上下文，
因此受限协程必须始终使用 `EmptyCoroutineContext` 作为它们的上下文，这个上下文属性在 `SequenceCoroutine` 的 getter 方法中实现返回。
尝试使用非 EmptyCoroutinesContext 的上下文创建一个受限协程，最终会导致 IllegalArgumentException。

## Implementation details

本节简要介绍协程的实现细节。它们隐藏在[coroutines overview](#coroutines-overview)部分中解释的构建块后面，
只要它们不破坏公共 APIs 和 ABIs 的契约，它们的内部类和代码生成策略随时可能发生变化。

### Continuation passing style

挂起函数是通过 Continuation-Passing-Style (CPS) 实现的。
每个挂起函数和 suspending lambda 都有一个附加的 `Continuation` 参数，该参数在调用时隐式传递给它们。
回想一下，[`await` suspending function](#suspending-functions)的声明如下所示：

```kotlin
suspend fun <T> CompletableFuture<T>.await(): T
```

然而，其实际 _实现_ 在 _CPS transformation_ 后具有以下签名：
However, its actual _implementation_ has the following signature after _CPS transformation_:

```kotlin
fun <T> CompletableFuture<T>.await(continuation: Continuation<T>): Any?
```

其 result 类型 `T` 已移至其附加 continuation 参数中类型参数的位置上。 `Any?` 的执行结果类型旨在表示挂起函数的动作。
当挂起函数 _挂起_ 协程时，它返回一个特殊的标记值 `COROUTINE_SUSPENDED`（更多信息参见[coroutine intrinsics](#coroutine-intrinsics)部分）。
当一个挂起函数没有挂起当前协程而是继续协程执行时，它将返回挂起函数的结果或者直接抛出异常。
这样， `await` 函数实现的返回类型 `Any?` 实际上是 `COROUTINE_SUSPENDED` 和 `T` 的联合，这在 Kotlin 的类型系统中是无法表达的。

挂起函数的实际实现不允许直接在其堆栈帧中调用 continuation ，因为这可能导致长时间运行的协程发生堆栈溢出。
标准库中的 `suspendCoroutine` 函数通过跟踪 continuation 的调用向应用程序开发人员隐藏了这种复杂性，
并确保挂起函数与实际实现契约一致，无论如何以及何时调用 continuation 。

### State machines

高效地实现协程至关重要，即创建尽可能少的类和对象。许多语言通过 _状态机_ 实现它们，Kotlin 也是如此。
在 Kotlin 的情况下，这种方式导致编译器只为每个 suspending lambda 创建一个类，它的主体中可能有任意数量的 suspension points挂起点。

主要思想：一个挂起函数被编译成一个状态机，其中状态对应于挂起点。示例：让我们使用带有两个挂起点的 suspending block：

```kotlin
val a = a()
val y = foo(a).await() // suspension point #1
b()
val z = bar(a, y).await() // suspension point #2
c(z)
``` 

此代码块存在三种状态：

* initial (before any suspension point)
* after the first suspension point
* after the second suspension point

每个状态都是当前 block 的一个 continuation 的入口点（ initial continuation 从第一行开始）。
(原文：Every state is an entry point to one of the continuations of this block.)

代码被编译成一个匿名类，它有一个实现状态机的方法，一个保存状态机当前状态的字段，
以及在状态之间共享的协程局部变量的字段（there may also be fields for the closure of
the coroutine, but in this case it is empty）。
下面是上面代码块的伪 Java 代码，它使用 continuation passing style 来调用挂起函数 `await`：

``` java
class <anonymous_for_state_machine> extends SuspendLambda<...> {
    // The current state of the state machine
    int label = 0
    
    // local variables of the coroutine
    A a = null
    Y y = null
    
    void resumeWith(Object result) {
        if (label == 0) goto L0
        if (label == 1) goto L1
        if (label == 2) goto L2
        else throw IllegalStateException()
        
      L0:
        // result is expected to be `null` at this invocation
        a = a()
        label = 1
        result = foo(a).await(this) // 'this' is passed as a continuation 
        if (result == COROUTINE_SUSPENDED) return // return if await had suspended execution
      L1:
        // external code has resumed this coroutine passing the result of .await() 
        y = (Y) result
        b()
        label = 2
        result = bar(a, y).await(this) // 'this' is passed as a continuation
        if (result == COROUTINE_SUSPENDED) return // return if await had suspended execution
      L2:
        // external code has resumed this coroutine passing the result of .await()
        Z z = (Z) result
        c(z)
        label = -1 // No more steps are allowed
        return
    }          
}    
```  

请注意，这里有一个 `goto` 运算符和 labels ，因为该示例描述了字节码中发生的事情，而不是源代码中发生的事情。

现在，当协程启动时，我们调用它的 `resumeWith()` —— `label` 为 `0`，我们跳转到 `L0`，然后我们做一些工作，将 `label` 设置为下一个状态 —— `1`，如果协程的执行被挂起，调用 `.await()` 并返回。
当我们想继续执行时，我们再次调用 `resumeWith()` ，现在它直接进入 `L1`，做一些工作，将状态设置为 `2`，调用 .await() 并如果在挂起的情况下再次返回。
下次它从 `L2`(原文是 L3，应该是 L2 才对) 继续将状态设置为 `-1`，这意味着“结束，没有更多的工作要做”。

循环内的挂起点只生成一个状态，因为循环也是通过（conditional）goto 工作：
A suspension point inside a loop generates only one state,
because loops also work through (conditional) `goto`:

```kotlin
var x = 0
while (x < 10) {
    x += nextNumber().await()
}
```

生成为

``` java
class <anonymous_for_state_machine> extends SuspendLambda<...> {
    // The current state of the state machine
    int label = 0
    
    // local variables of the coroutine
    int x
    
    void resumeWith(Object result) {
        if (label == 0) goto L0
        if (label == 1) goto L1
        else throw IllegalStateException()
        
      L0:
        x = 0
      LOOP:
        if (x >= 10) goto END
        label = 1
        result = nextNumber().await(this) // 'this' is passed as a continuation 
        if (result == COROUTINE_SUSPENDED) return // return if await had suspended execution
      L1:
        // external code has resumed this coroutine passing the result of .await()
        x += ((Integer) result).intValue()
        label = -1
        goto LOOP
      END:
        label = -1 // No more steps are allowed
        return 
    }          
}    
```  

### Compiling suspending functions

挂起函数的编译代码是取决于它如何以及何时调用其他挂起函数的。
在最简单的情况下，一个挂起函数仅在 _尾部位置_ 调用其他挂起函数，从而对它们进行 _尾部调用_ 。
这是挂起实现 low-level 同步原语或 wrap callbacks 的典型案例，如 [suspending functions](#suspending-functions) 和[wrapping callbacks](#wrapping-callbacks)部分所示。
这些函数在尾部位置调用一些其他的挂起函数，如 `suspendCoroutine`。
它们的编译就像常规的非挂起函数一样，唯一的例外是它们从 [CPS transformation](#continuation-passing-style) 中获得的隐式 continuation 参数被传递给尾调用中的下一个挂起函数。

在挂起调用出现在非尾部位置的情况下，编译器会为相应的挂起函数创建一个[state machine](#state-machines)。
调用挂起函数时创建状态机对象的实例，并在其完成时丢弃。

> 注意：在未来的版本中，可能会优化此编译策略以仅在第一个挂起点创建状态机实例。

这个状态机对象反过来充当其他非尾位置调用挂起函数的 _completion continuation_ 。(原文：serves as the _completion continuation_ for the invocation of other
suspending functions in non-tail positions.)
当该函数多次调用其他挂起函数时，该状态机对象实例将被更新和重用。
将此与其他[asynchronous programming styles](#asynchronous-programming-styles)进行比较，
在其他异步编程风格中，异步处理的每个后续步骤通常使用单独的、重新分配的闭包对象来实现。

### Coroutine intrinsics

Kotlin 标准库提供 `kotlin.coroutines.intrinsics` 包，其中包含许多声明，这些声明公开了本节中解释的协程机制的内部实现细节，应谨慎使用。
这些声明不应在一般代码中使用，因此 `kotlin.coroutines.intrinsics` 包在 IDE 中被隐藏以防止 auto-completion 。
为了使用这些声明，您必须手动将相应的导入语句添加到您的源文件中：

```kotlin
import kotlin.coroutines.intrinsics.*
```

标准库中 `suspendCoroutine` 挂起函数的实际实现是用 Kotlin 本身编写的，其源代码作为标准库源代码包的一部分提供。
为了提供协程的安全和无问题的使用，它将 state machine 的 actual continuation 包装到每个被挂起协程的附加对象中。
这对于真正的异步用例（如[asynchronous computations](#asynchronous-computations) and [futures](#futures)）来说非常好，
因为相应异步原语的运行时成本远远超过额外分配对象的成本。
然而，对于[generators](#generators)用例来说，这种额外的成本是令人望而却步的，因此 intrinsics 包为性能敏感的 low-level 提供了原语。

标准库中的 `kotlin.coroutines.intrinsics` package 中包含名为 [`suspendCoroutineUninterceptedOrReturn`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines.intrinsics/suspend-coroutine-unintercepted-or-return.html) 的函数，
其签名如下：

```kotlin
suspend fun <T> suspendCoroutineUninterceptedOrReturn(block: (Continuation<T>) -> Any?): T
```

它提供了对挂起函数的[continuation passing style](#continuation-passing-style)的直接访问，并公开了对 continuation 的 _unintercepted_ 引用。
后者意味着 `Continuation.resumeWith` 的调用不会通过 [ContinuationInterceptor](#continuation-interceptor)。
在编写无法安装 continuation interceptor（因为它们的上下文始终为空）的[restricted suspension](#restricted-suspension)的同步协程时，它是有用的。
或者在"已知当前正在执行的线程在所需的上下文中"时使用它。(原文：when currently executing thread is already known to be in the desired context.)
否则，应使用[`intercepted`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines.intrinsics/intercepted.html)的扩展函数（来自 `kotlin.coroutines.intrinsics` 包）获取一个 intercepted continuation ：

```kotlin
fun <T> Continuation<T>.intercepted(): Continuation<T>
```

并且 `Continuation.resumeWith` 应在生成的 _intercepted_ continuation 上被调用。
and the `Continuation.resumeWith` shall be invoked on the resulting _intercepted_ continuation.

现在，`block` 传递给 `suspendCoroutineUninterceptedOrReturn` 函数 可以返回 [`COROUTINE_SUSPENDED`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines.intrinsics/-c-o-r-o-u-t-i-n-e_-s-u-s-p-e-n-d-e-d.html) 标记，
如果协程确实挂起（在这种情况下，`Continuation.resumeWith` 应在之后恰好调用一次）
或返回结果值 `T` 或抛出异常（在后两种情况下，`Continuation.resumeWith` 应永远不会被调用）。

在使用 `suspendCoroutineUninterceptedOrReturn` 时未能遵循此约定会导致难以跟踪错误，这些错误无法通过测试找到并重现它们。
对于类似 `buildSequence/yield` 的协程，通常很容易遵循此约定，
但 **不鼓励** 尝试在 `suspendCoroutineUninterceptedOrReturn` 之上编写类似等待的异步挂起函数，
因为如果没有 `suspendCoroutine` 的帮助，它们 **很难** 正确实现。

还有一些名为 [`createCoroutineUnintercepted`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines.intrinsics/create-coroutine-unintercepted.html) 的函数
（来自 `kotlin.coroutines.intrinsics` 包），具有以下签名：

```kotlin
fun <T> (suspend () -> T).createCoroutineUnintercepted(completion: Continuation<T>): Continuation<Unit>
fun <R, T> (suspend R.() -> T).createCoroutineUnintercepted(receiver: R, completion: Continuation<T>): Continuation<Unit>
```

它们的工作方式与 `createCoroutine` 类似，但返回对 initial continuation 的未拦截引用。
与 `suspendCoroutineUninterceptedOrReturn` 类似，它可以在同步协程中以获得更好的性能。
例如，通过 `createCoroutineUnintercepted` 对 `sequence{}` builder 的优化版本如下所示：

```kotlin
fun <T> sequence(block: suspend SequenceScope<T>.() -> Unit): Sequence<T> = Sequence {
    SequenceCoroutine<T>().apply {
        nextStep = block.createCoroutineUnintercepted(receiver = this, completion = this)
    }
}
```

通过 `suspendCoroutineUninterceptedOrReturn` 优化的 `yield` 版本如下所示。
请注意，因为 `yield` 总是挂起的，所以相应的 block 总是返回 `COROUTINE_SUSPENDED`。

```kotlin
// Generator implementation
override suspend fun yield(value: T) {
    setNext(value)
    return suspendCoroutineUninterceptedOrReturn { cont ->
        nextStep = cont
        COROUTINE_SUSPENDED
    }
}
```

> 你可以在 [这里](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/sequence/optimized/sequenceOptimized.kt) 获取全部代码

两个额外的 intrinsics 提供了 `startCoroutine` 的 lower-level 版本（请参阅[coroutine builders](#coroutine-builders)部分）
并称为 [`startCoroutineUninterceptedOrReturn`](http://kotlinlang.org/api/latest/jvm/stdlib/kotlin.coroutines.intrinsics/start-coroutine-unintercepted-or-return.html):

```kotlin
fun <T> (suspend () -> T).startCoroutineUninterceptedOrReturn(completion: Continuation<T>): Any?
fun <R, T> (suspend R.() -> T).startCoroutineUninterceptedOrReturn(receiver: R, completion: Continuation<T>): Any?
```

它们在两个方面不同于 `startCoroutine`。
首先，[ContinuationInterceptor](#continuation-interceptor) 不会在启动协程时自动使用，因此调用者必须在需要时确保正确的执行上下文。
第二，如果协程没有被挂起，而是返回一个值或者抛出一个异常，那么调用 `startCoroutineUninterceptedOrReturn` 就返回这个值或者抛出这个异常。
如果协程被挂起，则返回 `COROUTINE_SUSPENDED` 。

`startCoroutineUninterceptedOrReturn` 的主要用例是将其与 `suspendCoroutineUninterceptedOrReturn` 结合使用，
以在相同的上下文中继续运行挂起的协程，但使用不同的代码块：

```kotlin 
suspend fun doSomething() = suspendCoroutineUninterceptedOrReturn { cont ->
    // figure out or create a block of code that needs to be run
    startCoroutineUninterceptedOrReturn(completion = block) // return result to suspendCoroutineUninterceptedOrReturn 
}
```

## Appendix 

这是一个非规范部分，不介绍任何新的语言结构或库函数，但涵盖了一些涉及资源管理、并发和编程风格的附加主题，并为各种用例提供了更多示例。

### Resource management and GC

Coroutines don't use any off-heap storage and do not consume any native resources by themselves, unless the code
that is running inside a coroutine does open a file or some other resource. While files opened in a coroutine must
be closed somehow, the coroutine itself does not need to be closed. When coroutine is suspended its whole state is
available by the reference to its continuation. If you lose the reference to suspended coroutine's continuation,
then it will be ultimately collected by garbage collector.

Coroutines that open some closeable resources deserve a special attention. Consider the following coroutine
that uses the `sequence{}` builder from [restricted suspension](#restricted-suspension) section to produce
a sequence of lines from a file:

```kotlin
fun sequenceOfLines(fileName: String) = sequence<String> {
    BufferedReader(FileReader(fileName)).use {
        while (true) {
            yield(it.readLine() ?: break)
        }
    }
}
```

This function returns a `Sequence<String>` and you can use this function to print all lines from a file
in a natural way:

```kotlin
sequenceOfLines("https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/sequence/sequenceOfLines.kt")
    .forEach(::println)
```

> You can get full code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/sequence/sequenceOfLines.kt)

It works as expected as long as you iterate the sequence returned by the `sequenceOfLines` function
completely. However, if you print just a few first lines from this file like here:

```kotlin
sequenceOfLines("https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/sequence/sequenceOfLines.kt")
        .take(3)
        .forEach(::println)
```

then the coroutine resumes a few times to yield the first three lines and becomes _abandoned_.
It is Ok for the coroutine itself to be abandoned but not for the open file. The
[`use` function](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.io/use.html)
will not have a chance to finish its execution and close the file. The file will be left open
until collected by GC, because Java files have a `finalizer` that closes the file. It is
not a big problem for a small slide-ware or a short-running utility, but it may be a disaster for
a large backend system with multi-gigabyte heap, that can run out of open file handles
faster than it runs out of memory to trigger GC.

This is a similar gotcha to Java's
[`Files.lines`](https://docs.oracle.com/javase/8/docs/api/java/nio/file/Files.html#lines-java.nio.file.Path-)
method that produces a lazy stream of lines. It returns a closeable Java stream, but most stream operations do not
automatically invoke the corresponding
`Stream.close` method and it is up to the user to remember about the need to close the corresponding stream.
One can define closeable sequence generators
in Kotlin, but they will suffer from a similar problem that no automatic mechanism in the language can
ensure that they are closed after use. It is explicitly out of the scope of Kotlin coroutines
to introduce a language mechanism for an automated resource management.

However, usually this problem does not affect asynchronous use-cases of coroutines. An asynchronous coroutine
is never abandoned, but ultimately runs until its completion, so if the code inside a coroutine properly closes
its resources, then they will be ultimately closed.

### Concurrency and threads

Each individual coroutine, just like a thread, is executed sequentially. It means that the following kind
of code is perfectly safe inside a coroutine:

```kotlin
launch { // starts a coroutine
    val m = mutableMapOf<String, String>()
    val v1 = someAsyncTask1() // start some async task
    val v2 = someAsyncTask2() // start some async task
    m["k1"] = v1.await() // map modification waiting on await
    m["k2"] = v2.await() // map modification waiting on await
}
```

You can use all the regular single-threaded mutable structures inside the scope of a particular coroutine.
However, sharing mutable state _between_ coroutines is potentially dangerous. If you use a coroutine builder
that installs a dispatcher to resume all coroutines JS-style in the single event-dispatch thread,
like the `Swing` interceptor shown in [continuation interceptor](#continuation-interceptor) section,
then you can safely work with all shared
objects that are generally modified from this event-dispatch thread.
However, if you work in multi-threaded environment or otherwise share mutable state between
coroutines running in different threads, then you have to use thread-safe (concurrent) data structures.

Coroutines are like threads in this sense, albeit they are more lightweight. You can have millions of coroutines running on
just a few threads. The running coroutine is always executed in some thread. However, a _suspended_ coroutine
does not consume a thread and it is not bound to a thread in any way. The suspending function that resumes this
coroutine decides which thread the coroutine is resumed on by invoking `Continuation.resumeWith` on this thread
and coroutine's interceptor can override this decision and dispatch the coroutine's execution onto a different thread.

## Asynchronous programming styles

There are different styles of asynchronous programming.

Callbacks were discussed in [asynchronous computations](#asynchronous-computations) section and are generally
the least convenient style that coroutines are designed to replace. Any callback-style API can be
wrapped into the corresponding suspending function as shown [here](#wrapping-callbacks).

Let us recap. For example, assume that you start with a hypothetical _blocking_ `sendEmail` function
with the following signature:

```kotlin
fun sendEmail(emailArgs: EmailArgs): EmailResult
```

It blocks execution thread for potentially long time while it operates.

To make it non-blocking you can use, for example, error-first
[node.js callback convention](https://www.tutorialspoint.com/nodejs/nodejs_callbacks_concept.htm)
to represent its non-blocking version in callback-style with the following signature:

```kotlin
fun sendEmail(emailArgs: EmailArgs, callback: (Throwable?, EmailResult?) -> Unit)
```

However, coroutines enable other styles of asynchronous non-blocking programming. One of them
is async/await style that is built into many popular languages.
In Kotlin this style can be replicated by introducing `future{}` and `.await()` library functions
that were shown as a part of [futures](#futures) use-case section.

This style is signified by the convention to return some kind of future object from the function instead
of taking a callback as a parameter. In this async-style the signature of `sendEmail` is going to look like this:

```kotlin
fun sendEmailAsync(emailArgs: EmailArgs): Future<EmailResult>
```

As a matter of style, it is a good practice to add `Async` suffix to such method names, because their
parameters are no different from a blocking version and it is quite easy to make a mistake of forgetting about
asynchronous nature of their operation. The function `sendEmailAsync` starts a _concurrent_ asynchronous operation
and potentially brings with it all the pitfalls of concurrency. However, languages that promote this style of
programming also typically have some kind of `await` primitive to bring the execution back into the sequence as needed.

Kotlin's _native_ programming style is based on suspending functions. In this style, the signature of
`sendEmail` looks naturally, without any mangling to its parameters or return type but with an additional
`suspend` modifier:

```kotlin
suspend fun sendEmail(emailArgs: EmailArgs): EmailResult
```

The async and suspending styles can be easily converted into one another using the primitives that we've
already seen. For example, `sendEmailAsync` can be implemented via suspending `sendEmail` using
[`future` coroutine builder](#building-futures):

```kotlin
fun sendEmailAsync(emailArgs: EmailArgs): Future<EmailResult> = future {
    sendEmail(emailArgs)
}
```

while suspending function `sendEmail` can be implemented via `sendEmailAsync` using
[`.await()` suspending function](#suspending-functions)

```kotlin
suspend fun sendEmail(emailArgs: EmailArgs): EmailResult = 
    sendEmailAsync(emailArgs).await()
```

So, in some sense, these two styles are equivalent and are both definitely superior to callback style in their
convenience. However, let us look deeper at a difference between `sendEmailAsync` and suspending `sendEmail`.

Let us compare how they **compose** first. Suspending functions can be composed just like normal functions:

```kotlin
suspend fun largerBusinessProcess() {
    // a lot of code here, then somewhere inside
    sendEmail(emailArgs)
    // something else goes on after that
}
```

The corresponding async-style functions compose in this way:

```kotlin
fun largerBusinessProcessAsync() = future {
   // a lot of code here, then somewhere inside
   sendEmailAsync(emailArgs).await()
   // something else goes on after that
}
```

Observe, that async-style function composition is more verbose and _error prone_.
If you omit `.await()` invocation in async-style
example,  the code still compiles and works, but it now does email sending process
asynchronously or even _concurrently_ with the rest of a larger business process,
thus potentially modifying some shared state and introducing some very hard to reproduce errors.
On the contrary, suspending functions are _sequential by default_.
With suspending functions, whenever you need any concurrency, you explicitly express it in the source code with
some kind of `future{}` or a similar coroutine builder invocation.

Compare how these styles **scale** for a big project using many libraries. Suspending functions are
a light-weight language concept in Kotlin. All suspending functions are fully usable in any unrestricted Kotlin coroutine.
Async-style functions are framework-dependent. Every promises/futures framework must define its own `async`-like
function that returns its own kind of promise/future class and its own `await`-like function, too.

Compare their **performance**. Suspending functions provide minimal overhead per invocation.
You can checkout [implementation details](#implementation-details) section.
Async-style functions need to keep quite heavy promise/future abstraction in addition to all of that suspending machinery.
Some future-like object instance must be always returned from async-style function invocation and it cannot be optimized away even
if the function is very short and simple. Async-style is not well-suited for very fine-grained decomposition.

Compare their **interoperability** with JVM/JS code. Async-style functions are more interoperable with JVM/JS code that
uses a matching type of future-like abstraction. In Java or JS they are just functions that return a corresponding
future-like object. Suspending functions look strange from any language that does not support
[continuation-passing-style](#continuation-passing-style) natively.
However, you can see in the examples above how easy it is to convert any suspending function into an
async-style function for any given promise/future framework. So, you can write suspending function in Kotlin just once,
and then adapt it for interoperability with any style of promise/future with one line of code using an appropriate
`future{}` coroutine builder function.

### Wrapping callbacks

许多异步 APIs 都有 callback-style 的接口。标准库中的 `suspendCoroutine` 挂起函数（请参阅[suspending functions](#suspending-functions)部分）
提供了一种将任何 callback 包装到 Kotlin 挂起函数中的简单方法。

有一个简单的模式。假设您有一个带有回调的 `someLongComputation` 函数，这个回调又接收一些 "作为函数计算结果" 的 `Value` 。

```kotlin
fun someLongComputation(params: Params, callback: (Value) -> Unit)
```

您可以使用以下简单代码将其转换为挂起函数：

```kotlin
suspend fun someLongComputation(params: Params): Value = suspendCoroutine { cont ->
    someLongComputation(params) { cont.resume(it) }
} 
```

现在这个计算的返回类型是显式的，但它仍然是异步的并且不会阻塞线程。

> 请注意，[kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) 包含了一个协作式取消协程的框架。它提供了 `suspendCancellableCoroutine` 函数(类似于 `suspendCoroutine`)，但支持取消协程功能。
有关更多详细信息，请参阅其指南中有关[section on cancellation](http://kotlinlang.org/docs/reference/coroutines/cancellation-and-timeouts.html)的部分。

对于更复杂的示例，让我们看一下[asynchronous computations](#asynchronous-computations)用例中的 `aRead()` 函数。
可以作为Java NIO [`AsynchronousFileChannel`](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/AsynchronousFileChannel.html)
及其[`CompletionHandler`](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/CompletionHandler.html)
回调接口的 suspending extension function 实现，代码如下：

```kotlin
suspend fun AsynchronousFileChannel.aRead(buf: ByteBuffer): Int =
    suspendCoroutine { cont ->
        read(buf, 0L, Unit, object : CompletionHandler<Int, Unit> {
            override fun completed(bytesRead: Int, attachment: Unit) {
                cont.resume(bytesRead)
            }

            override fun failed(exception: Throwable, attachment: Unit) {
                cont.resumeWithException(exception)
            }
        })
    }
```

> 你可以在 [这里](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/io/io.kt)获取次代码。
注意：[kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) 中的实际实现支持取消以中止长时间运行的 IO 操作。

如果您正在处理许多共享相同类型回调的函数，那么您可以定义一个通用的包装函数，以便轻松地将它们全部转换为挂起函数。
例如，[vert.x](http://vertx.io/) 使用一个特定的约定，它的所有异步函数都接收 `Handler<AsyncResult<T>>` 作为回调。
为了简化协程中任意 vert.x 函数的使用，可以定义以下辅助函数：

```kotlin
inline suspend fun <T> vx(crossinline callback: (Handler<AsyncResult<T>>) -> Unit) = 
    suspendCoroutine<T> { cont ->
        callback(Handler { result: AsyncResult<T> ->
            if (result.succeeded()) {
                cont.resume(result.result())
            } else {
                cont.resumeWithException(result.cause())
            }
        })
    }
```

使用这个辅助函数，一个任意的异步 vert.x 函数 `async.foo(params, handler)` 
可以从一个带有 `vx { async.foo(params, it) }` 的协程中被调用。
(原文：Using this helper function, an arbitrary asynchronous vert.x function `async.foo(params, handler)`
can be invoked from a coroutine with `vx { async.foo(params, it) }`.)

### Building futures

The `future{}` builder from [futures](#futures) use-case can be defined for any future or promise primitive
similarly to the `launch{}` builder as explained in [coroutine builders](#coroutine-builders) section:

```kotlin
fun <T> future(context: CoroutineContext = CommonPool, block: suspend () -> T): CompletableFuture<T> =
        CompletableFutureCoroutine<T>(context).also { block.startCoroutine(completion = it) }
```

The first difference from `launch{}` is that it returns an implementation of
[`CompletableFuture`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletableFuture.html),
and the other difference is that it is defined with a default `CommonPool` context, so that its default
execution behavior is similar to the
[`CompletableFuture.supplyAsync`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/CompletableFuture.html#supplyAsync-java.util.function.Supplier-)
method that by default runs its code in
[`ForkJoinPool.commonPool`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ForkJoinPool.html#commonPool--).
The basic implementation of `CompletableFutureCoroutine` is straightforward:

```kotlin
class CompletableFutureCoroutine<T>(override val context: CoroutineContext) : CompletableFuture<T>(), Continuation<T> {
    override fun resumeWith(result: Result<T>) {
        result
            .onSuccess { complete(it) }
            .onFailure { completeExceptionally(it) }
    }
}
```

> You can get this code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/future/future.kt).
The actual implementation in [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) is more advanced,
because it propagates the cancellation of the resulting future to cancel the coroutine.

The completion of this coroutine invokes the corresponding `complete` methods of the future to record the
result of this coroutine.

### Non-blocking sleep

Coroutines should not use [`Thread.sleep`](https://docs.oracle.com/javase/8/docs/api/java/lang/Thread.html#sleep-long-),
because it blocks a thread. However, it is quite straightforward to implement a suspending non-blocking `delay` function by using
Java's [`ScheduledThreadPoolExecutor`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ScheduledThreadPoolExecutor.html)

```kotlin
private val executor = Executors.newSingleThreadScheduledExecutor {
    Thread(it, "scheduler").apply { isDaemon = true }
}

suspend fun delay(time: Long, unit: TimeUnit = TimeUnit.MILLISECONDS): Unit = suspendCoroutine { cont ->
    executor.schedule({ cont.resume(Unit) }, time, unit)
}
```

> You can get this code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/delay/delay.kt).
Note: [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) also provides `delay` function.

Note, that this kind of `delay` function resumes the coroutines that are using it in its single "scheduler" thread.
The coroutines that are using [interceptor](#continuation-interceptor) like `Swing` will not stay to execute in this thread,
as their interceptor dispatches them into an appropriate thread. Coroutines without interceptor will stay to execute
in this scheduler thread. So this solution is convenient for demo purposes, but it is not the most efficient one. It
is advisable to implement sleep natively in the corresponding interceptors.

For `Swing` interceptor that native implementation of non-blocking sleep shall use
[Swing Timer](https://docs.oracle.com/javase/8/docs/api/javax/swing/Timer.html)
that is specifically designed for this purpose:

```kotlin
suspend fun Swing.delay(millis: Int): Unit = suspendCoroutine { cont ->
    Timer(millis) { cont.resume(Unit) }.apply {
        isRepeats = false
        start()
    }
}
```

> You can get this code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/context/swing-delay.kt).
Note: [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) implementation of `delay` is aware of
interceptor-specific sleep facilities and automatically uses the above approach where appropriate.

### Cooperative single-thread multitasking

It is very convenient to write cooperative single-threaded applications, because you don't have to
deal with concurrency and shared mutable state. JS, Python and many other languages do
not have threads, but have cooperative multitasking primitives.

[Coroutine interceptor](#coroutine-interceptor) provides a straightforward tool to ensure that
all coroutines are confined to a single thread. The example code
[here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/context/threadContext.kt) defines `newSingleThreadContext()` function that
creates a single-threaded execution services and adapts it to the coroutine interceptor
requirements.

We will use it with `future{}` coroutine builder that was defined in [building futures](#building-futures) section
in the following example that works in a single thread, despite the
fact that it has two asynchronous tasks inside that are both active.

```kotlin
fun main(args: Array<String>) {
    log("Starting MyEventThread")
    val context = newSingleThreadContext("MyEventThread")
    val f = future(context) {
        log("Hello, world!")
        val f1 = future(context) {
            log("f1 is sleeping")
            delay(1000) // sleep 1s
            log("f1 returns 1")
            1
        }
        val f2 = future(context) {
            log("f2 is sleeping")
            delay(1000) // sleep 1s
            log("f2 returns 2")
            2
        }
        log("I'll wait for both f1 and f2. It should take just a second!")
        val sum = f1.await() + f2.await()
        log("And the sum is $sum")
    }
    f.get()
    log("Terminated")
}
```

> You can get fully working example [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/context/threadContext-example.kt).
Note: [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) has ready-to-use implementation of
`newSingleThreadContext`.

If your whole application is based on a single-threaded execution, you can define your own helper coroutine
builders with a hard-coded context for your single-threaded execution facilities.

## Asynchronous sequences

The `sequence{}` coroutine builder that is shown in [restricted suspension](#restricted-suspension)
section is an example of a _synchronous_ coroutine. Its producer code in the coroutine is invoked
synchronously in the same thread as soon as its consumer invokes `Iterator.next()`.
The `sequence{}` coroutine block is restricted and it cannot suspend its execution using 3rd-party suspending
functions like asynchronous file IO as shown in [wrapping callbacks](#wrapping-callbacks) section.

An _asynchronous_ sequence builder is allowed to arbitrarily suspend and resume its execution. It means
that its consumer shall be ready to handle the case, when the data is not produced yet. This is
a natural use-case for suspending functions. Let us define `SuspendingIterator` interface that is
similar to a regular
[`Iterator`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.collections/-iterator/)
interface, but its `next()` and `hasNext()` functions are suspending:

```kotlin
interface SuspendingIterator<out T> {
    suspend operator fun hasNext(): Boolean
    suspend operator fun next(): T
}
```

The definition of `SuspendingSequence` is similar to the standard
[`Sequence`](https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.sequences/-sequence/index.html)
but it returns `SuspendingIterator`:

```kotlin
interface SuspendingSequence<out T> {
    operator fun iterator(): SuspendingIterator<T>
}
```

We also define a scope interface for that is similar to a scope of a synchronous sequence,
but it is not restricted in its suspensions:

```kotlin
interface SuspendingSequenceScope<in T> {
    suspend fun yield(value: T)
}
```

The builder function `suspendingSequence{}` is similar to a synchronous `sequence{}`.
Their differences lie in implementation details of `SuspendingIteratorCoroutine` and
in the fact that it makes sense to accept an optional context in this case:

```kotlin
fun <T> suspendingSequence(
    context: CoroutineContext = EmptyCoroutineContext,
    block: suspend SuspendingSequenceScope<T>.() -> Unit
): SuspendingSequence<T> = object : SuspendingSequence<T> {
    override fun iterator(): SuspendingIterator<T> = suspendingIterator(context, block)
}
```

> You can get full code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/suspendingSequence/suspendingSequence.kt).
Note: [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines) has an implementation of
`Channel` primitive with the corresponding `produce{}` coroutine builder that provides more
flexible implementation of the same concept.

Let us take `newSingleThreadContext{}` context from
[cooperative single-thread multitasking](#cooperative-single-thread-multitasking) section
and non-blocking `delay` function from [non-blocking sleep](#non-blocking-sleep) section.
This way we can write an implementation of a non-blocking sequence that yields
integers from 1 to 10, sleeping 500 ms between them:

```kotlin
val seq = suspendingSequence(context) {
    for (i in 1..10) {
        yield(i)
        delay(500L)
    }
}
```

Now the consumer coroutine can consume this sequence at its own pace, while also
suspending with other arbitrary suspending functions. Note, that
Kotlin [for loops](https://kotlinlang.org/docs/reference/control-flow.html#for-loops)
work by convention, so there is no need for a special `await for` loop construct in the language.
The regular `for` loop can be used to iterate over an asynchronous sequence that we've defined
above. It is suspended whenever producer does not have a value:


```kotlin
for (value in seq) { // suspend while waiting for producer
    // do something with value here, may suspend here, too
}
```

> You can find a worked out example with some logging that illustrates the execution
[here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/suspendingSequence/suspendingSequence-example.kt)

### Channels

Go-style type-safe channels can be implemented in Kotlin as a library. We can define an interface for
send channel with suspending function `send`:

```kotlin
interface SendChannel<T> {
    suspend fun send(value: T)
    fun close()
}
```

and receiver channel with suspending function `receive` and an `operator iterator` in a similar style
to [asynchronous sequences](#asynchronous-sequences):

```kotlin
interface ReceiveChannel<T> {
    suspend fun receive(): T
    suspend operator fun iterator(): ReceiveIterator<T>
}
```

The `Channel<T>` class implements both interfaces.
The `send` suspends when the channel buffer is full, while `receive` suspends when the buffer is empty.
It allows us to copy Go-style code into Kotlin almost verbatim.
The `fibonacci` function that sends `n` fibonacci numbers in to a channel from
[the 4th concurrency example of a tour of Go](https://tour.golang.org/concurrency/4)  would look
like this in Kotlin:

```kotlin
suspend fun fibonacci(n: Int, c: SendChannel<Int>) {
    var x = 0
    var y = 1
    for (i in 0..n - 1) {
        c.send(x)
        val next = x + y
        x = y
        y = next
    }
    c.close()
}

```

We can also define Go-style `go {...}` block to start the new coroutine in some kind of
multi-threaded pool that dispatches an arbitrary number of light-weight coroutines onto a fixed number of
actual heavy-weight threads.
The example implementation [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/channel/go.kt) is trivially written on top of
Java's common [`ForkJoinPool`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ForkJoinPool.html).

Using this `go` coroutine builder, the main function from the corresponding Go code would look like this,
where `mainBlocking` is shortcut helper function for `runBlocking` with the same pool as `go{}` uses:

```kotlin
fun main(args: Array<String>) = mainBlocking {
    val c = Channel<Int>(2)
    go { fibonacci(10, c) }
    for (i in c) {
        println(i)
    }
}
```

> You can checkout working code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/channel/channel-example-4.kt)

You can freely play with the buffer size of the channel.
For simplicity, only buffered channels are implemented in the example (with a minimal buffer size of 1),
because unbuffered channels are conceptually similar to [asynchronous sequences](#asynchronous-sequences)
that were covered before.

Go-style `select` control block that suspends until one of the actions becomes available on
one of the channels can be implemented as a Kotlin DSL, so that
[the 5th concurrency example of a tour of Go](https://tour.golang.org/concurrency/5)  would look
like this in Kotlin:

```kotlin
suspend fun fibonacci(c: SendChannel<Int>, quit: ReceiveChannel<Int>) {
    var x = 0
    var y = 1
    whileSelect {
        c.onSend(x) {
            val next = x + y
            x = y
            y = next
            true // continue while loop
        }
        quit.onReceive {
            println("quit")
            false // break while loop
        }
    }
}
```

> You can checkout working code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/channel/channel-example-5.kt)

Example has an implementation of both `select {...}`, that returns the result of one of its cases like a Kotlin
[`when` expression](https://kotlinlang.org/docs/reference/control-flow.html#when-expression),
and a convenience `whileSelect { ... }` that is the same as `while(select<Boolean> { ... })` with fewer braces.

The default selection case from [the 6th concurrency example of a tour of Go](https://tour.golang.org/concurrency/6)
just adds one more case into the `select {...}` DSL:

```kotlin
fun main(args: Array<String>) = mainBlocking {
    val tick = Time.tick(100)
    val boom = Time.after(500)
    whileSelect {
        tick.onReceive {
            println("tick.")
            true // continue loop
        }
        boom.onReceive {
            println("BOOM!")
            false // break loop
        }
        onDefault {
            println("    .")
            delay(50)
            true // continue loop
        }
    }
}
```

> You can checkout working code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/channel/channel-example-6.kt)

The `Time.tick` and `Time.after` are trivially implemented
[here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/channel/time.kt) with non-blocking `delay` function.

Other examples can be found [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/channel/) together with the links to
the corresponding Go code in comments.

Note, that this sample implementation of channels is based on a single
lock to manage its internal wait lists. It makes it easier to understand and reason about.
However, it never runs user code under this lock and thus it is fully concurrent.
This lock only somewhat limits its scalability to a very large number of concurrent threads.

> The actual implementation of channels and `select` in [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines)
is based on lock-free disjoint-access-parallel data structures.

This channel implementation is independent
of the interceptor in the coroutine context. It can be used in UI applications
under an event-thread interceptor as shown in the
corresponding [continuation interceptor](#continuation-interceptor) section, or with any other one, or without
an interceptor at all (in the later case, the execution thread is determined solely by the code
of the other suspending functions used in a coroutine).
The channel implementation just provides thread-safe non-blocking suspending functions.

### Mutexes

Writing scalable asynchronous applications is a discipline that one follows, making sure that ones code
never blocks, but suspends (using suspending functions), without actually blocking a thread.
The Java concurrency primitives like
[`ReentrantLock`](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/locks/ReentrantLock.html)
are thread-blocking and they should not be used in a truly non-blocking code. To control access to shared
resources one can define `Mutex` class that suspends an execution of coroutine instead of blocking it.
The header of the corresponding class would like this:

```kotlin
class Mutex {
    suspend fun lock()
    fun unlock()
}
```

> You can get full implementation [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/mutex/mutex.kt).
The actual implementation in [kotlinx.coroutines](https://github.com/kotlin/kotlinx.coroutines)
has a few additional functions.

Using this implementation of non-blocking mutex
[the 9th concurrency example of a tour of Go](https://tour.golang.org/concurrency/9)
can be translated into Kotlin using Kotlin's
[`try-finally`](https://kotlinlang.org/docs/reference/exceptions.html)
that serves the same purpose as Go's `defer`:

```kotlin
class SafeCounter {
    private val v = mutableMapOf<String, Int>()
    private val mux = Mutex()

    suspend fun inc(key: String) {
        mux.lock()
        try { v[key] = v.getOrDefault(key, 0) + 1 }
        finally { mux.unlock() }
    }

    suspend fun get(key: String): Int? {
        mux.lock()
        return try { v[key] }
        finally { mux.unlock() }
    }
}
```

> You can checkout working code [here](https://github.com/kotlin/kotlin-coroutines-examples/tree/master/examples/channel/channel-example-9.kt)

### Migration from experimental coroutines

Coroutines were an experimental feature in Kotlin 1.1-1.2. The corresponding APIs were exposed
in `kotlin.coroutines.experimental` package. The stable version of coroutines, available since Kotlin 1.3,
uses `kotlin.coroutines` package. The experimental package is still available in the standard library and the
code that was compiled with experimental coroutines still works as before.

Kotlin 1.3 compiler provides support for invoking experimental suspending functions and passing suspending
lambdas to the libraries that were compiled with experimental coroutines. Behind the scenes, the
adapters between the corresponding stable and experimental coroutine interfaces are created.

### References

* Further reading:
    * [Coroutines Reference Guide](http://kotlinlang.org/docs/reference/coroutines/coroutines-guide.html) **READ IT FIRST!**.
* Presentations:
    * [Introduction to Coroutines](https://www.youtube.com/watch?v=_hfBv0a09Jc) (Roman Elizarov at KotlinConf 2017, [slides](https://www.slideshare.net/elizarov/introduction-to-coroutines-kotlinconf-2017))
    * [Deep dive into Coroutines](https://www.youtube.com/watch?v=YrrUCSi72E8) (Roman Elizarov at KotlinConf 2017, [slides](https://www.slideshare.net/elizarov/deep-dive-into-coroutines-on-jvm-kotlinconf-2017))
    * [Kotlin Coroutines in Practice](https://www.youtube.com/watch?v=a3agLJQ6vt8) (Roman Elizarov at KotlinConf 2018, [slides](https://www.slideshare.net/elizarov/kotlin-coroutines-in-practice-kotlinconf-2018))
* Language design overview:
    * Part 1 (prototype design): [Coroutines in Kotlin](https://www.youtube.com/watch?v=4W3ruTWUhpw)
      (Andrey Breslav at JVMLS 2016)
    * Part 2 (current design): [Kotlin Coroutines Reloaded](https://www.youtube.com/watch?v=3xalVUY69Ok&feature=youtu.be)
      (Roman Elizarov at JVMLS 2017, [slides](https://www.slideshare.net/elizarov/kotlin-coroutines-reloaded))

### Feedback

Please, submit feedback to:

* [Kotlin YouTrack](http://kotl.in/issue) on issues with implementation of coroutines in Kotlin compiler and feature requests.
* [`kotlinx.coroutines`](https://github.com/Kotlin/kotlinx.coroutines/issues) on issues in supporting libraries.

## Revision history

This section gives an overview of changes between various revisions of coroutines design.

### Changes in revision 3.3

* Coroutines are no longer experimental and had moved to `kotlin.coroutines` package.
* The whole section on experimental status is removed and migration section is added.
* Some non-normative stylistic changes to reflect evolution of naming style.
* Specifications are updated for new features implemented in Kotlin 1.3:
    * More operators and different types of functions are supports.
    * Changes in the list of intrinsic functions:
    * `suspendCoroutineOrReturn` is removed, `suspendCoroutineUninterceptedOrReturn` is provided instead.
    * `createCoroutineUnchecked` is removed, `createCoroutineUnintercepted` is provided instead.
    * `startCoroutineUninterceptedOrReturn` is provided.
    * `intercepted` extension function is added.
* Moved non-normative sections with advanced topics and more examples to the appendix at end of the document to simplify reading.

### Changes in revision 3.2

* Added description of `createCoroutineUnchecked` intrinsic.

### Changes in revision 3.1

This revision is implemented in Kotlin 1.1.0 release.

* `kotlin.coroutines` package is replaced with `kotlin.coroutines.experimental`.
* `SUSPENDED_MARKER` is renamed to `COROUTINE_SUSPENDED`.
* Clarification on experimental status of coroutines added.

### Changes in revision 3

This revision is implemented in Kotlin 1.1-Beta.

* Suspending functions can invoke other suspending function at arbitrary points.
* Coroutine dispatchers are generalized to coroutine contexts:
    * `CoroutineContext` interface is introduced.
    * `ContinuationDispatcher` interface is replaced with `ContinuationInterceptor`.
    * `createCoroutine`/`startCoroutine` parameter `dispatcher` is removed.
    * `Continuation` interface includes `val context: CoroutineContext`.
* `CoroutineIntrinsics` object is replaced with `kotlin.coroutines.intrinsics` package.

### Changes in revision 2

This revision is implemented in Kotlin 1.1-M04.

* The `coroutine` keyword is replaced by suspending functional type.
* `Continuation` for suspending functions is implicit both on call site and on declaration site.
* `suspendContinuation` is provided to capture continuation is suspending functions when needed.
* Continuation passing style transformation has provision to prevent stack growth on non-suspending invocations.
* `createCoroutine`/`startCoroutine` coroutine builders are introduced.
* The concept of coroutine controller is dropped:
    * Coroutine completion result is delivered via `Continuation` interface.
    * Coroutine scope is optionally available via coroutine `receiver`.
    * Suspending functions can be defined at top-level without receiver.
* `CoroutineIntrinsics` object contains low-level primitives for cases where performance is more important than safety.