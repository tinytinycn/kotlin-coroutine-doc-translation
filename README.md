# 项目描述

此项目是对kotlin coroutine [官方文档](https://kotlinlang.org/docs/coroutines-guide.html)的中文翻译，仅供个人学习使用。

----

## Coroutines guide 协程指南 

(原文档修改日期: Last modified: 27 June 2022)

Kotlin 作为一种语言，在其标准库中只提供了最小的底层 API，以使其他各种库能够利用 coroutines。与其他许多具有类似功能的语言不同，`async`和`await`不是 Kotlin 的**关键字**，甚至不是其标准库的一部分。
此外，Kotlin `suspending function` 的概念为异步操作提供了比`futures`和`promises`更安全、更不容易出错的抽象概念。

`kotlinx.coroutines`是一个由 JetBrains 开发的丰富的 coroutines 库。它包含了本指南所涉及的一些高级别的 coroutine-enabled (primitives)原语，包括`luanch`、`async`和其他。

这是一本关于`kotlinx.coroutines`的核心功能的指南，其中有一系列的例子，分为不同的主题。

为了使用coroutines以及遵循本指南中的例子，你需要添加对`kotlinx-coroutines-core`模块的依赖，如[项目README](https://github.com/Kotlin/kotlinx.coroutines/blob/master/README.md#using-in-your-projects)中所解释的。

## 目录

- [协程基础](doc/1-协程基础.md)
- [实践:coroutines协程和channels通道](doc/2-实践:coroutines协程和channels通道.md)
- [取消和超时](doc/3-取消和超时.md)
- [组成suspending functions](doc/4-组合挂起函数.md)
- [协程上下文和调度器](doc/5-协程上下文和调度器.md)
- Flow异步流
- Channels通道
- [协程异常处理](doc/8-协程异常处理.md)
- Shared mutable state共享可变状态和并发
- Select expression (experimental实验性)
- 教程:使用IntelliJ IDEA调试协程
- 教程:使用IntelliJ IDEA调试Flow流

## 其他参考资料

- [Coroutines协程的UI编程指南](https://github.com/Kotlin/kotlinx.coroutines/blob/master/ui/coroutines-guide-ui.md)
- [Coroutines协程设计文档(KEEP)](doc/0-Coroutines协程设计文档(KEEP).md)
- [完整的 kotlinx.coroutines API 参考资料](https://kotlinlang.org/api/kotlinx.coroutines/)
- [Android中的协程最佳实践](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)
- [Kotlin coroutines和flow的其他Android资源](https://developer.android.com/kotlin/coroutines/additional-resources)