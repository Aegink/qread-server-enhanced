package com.script.rhino

import kotlinx.coroutines.ThreadContextElement
import org.mozilla.javascript.Context
import kotlin.coroutines.CoroutineContext

/**
 * 把指定的 Rhino [Context] 带到协程恢复后的线程上（Rhino 1.9 起 `VMBridge` 被移除后的替代实现）。
 *
 * 背景：`evalSuspend` 走 Rhino 的 continuation 机制，脚本可能在 `suspendCoroutine` 处挂起、
 * 在**另一个线程**上被 `resumeContinuation` 续跑，而 Rhino 要求当前线程已 enter 同一个 Context。
 * Rhino 1.9.2 提供了公开的 `Context.enter(Context)`，因此不再需要反射改内部 ThreadLocal。
 */
class RhinoContextElement(private val context: Context) : ThreadContextElement<Context?> {

    companion object Key : CoroutineContext.Key<RhinoContextElement>

    override val key: CoroutineContext.Key<*> get() = Key

    override fun updateThreadContext(context: CoroutineContext): Context? {
        val previous = Context.getCurrentContext()
        Context.enter(this.context)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: Context?) {
        Context.exit()
        if (oldState != null && oldState !== this.context) {
            Context.enter(oldState)
        }
    }
}
