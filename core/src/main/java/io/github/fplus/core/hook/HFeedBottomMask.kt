package io.github.fplus.core.hook

import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.xpler.core.XplerLog
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.hookClass
import io.github.xpler.core.lparam

/**
 * 全屏沉浸下不画底部渐变压暗层。
 *
 * `MultiSegmentMaskView`（id `oi`）在 `onDraw` 里按高度画一条纵向 `LinearGradient`，
 * 用来衬亮左下角的白字。它只铺到 tab 行上方（实测 0,1404-1080,2180），
 * 而 tab 行本身在沉浸模式下被清成了透明（0,2205-1080,2340），
 * 于是最黑的那条边正好怼着透明 tab，看起来就是"选项卡透明、上方一块不透明遮罩"的断层。
 *
 * 沉浸模式本来就是要少一层 UI，这里直接把它的绘制掐掉；关掉沉浸则完全恢复。
 */
class HFeedBottomMask : BaseHook() {
    companion object {
        private const val MASK_CLASS = "com.ss.android.ugc.aweme.feed.mask.ui.MultiSegmentMaskView"
    }

    private val config get() = ConfigV1.get()

    override fun setTargetClass(): Class<*> {
        return findClass(MASK_CLASS)
    }

    init {
        hookDraw()
    }

    private fun hookDraw() {
        runCatching {
            lparam.hookClass(targetClass)
                .method("onDraw", "android.graphics.Canvas") {
                    onBefore {
                        hookBlockRunning(this) {
                            if (!config.isImmersive) return@hookBlockRunning
                            setResultVoid()
                        }.onFailure { XplerLog.e(it) }
                    }
                }
        }.onFailure { XplerLog.e(it) }
    }
}
