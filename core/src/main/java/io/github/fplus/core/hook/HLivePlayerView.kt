package io.github.fplus.core.hook

import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.xpler.core.XplerLog
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.hookClass
import io.github.xpler.core.lparam

/**
 * 直播页显示时，把底部选项卡背景还原成不透明。
 *
 * 沉浸模式会把底部 tab 行清成透明。feed 页没问题（视频铺满），但直播页自己还有一条
 * 「讲解中 / 已售 N 万+」底栏，位置正好和 tab 行重叠；tab 一透明它就透出来，
 * 看起来像"讲解中跑进了选项卡那一行"。
 *
 * 处理方式：直播播放器出现就还原 tab 行原本的背景（把它盖住），离开再清回透明。
 */
class HLivePlayerView : BaseHook() {
    companion object {
        private const val LIVE_PLAYER = "com.bytedance.android.livesdkapi.roomplayer.LivePlayerView"
    }

    private val config get() = ConfigV1.get()

    override fun setTargetClass(): Class<*> {
        return findClass(LIVE_PLAYER)
    }

    init {
        hookVisibilityChange()
    }

    private fun hookVisibilityChange() {
        runCatching {
            lparam.hookClass(targetClass)
                .method("onAttachedToWindow") {
                    onAfter {
                        hookBlockRunning(this) {
                            if (!config.isImmersive) return@hookBlockRunning
                            XplerLog.d("Immersive", "live player attached, restore tab bar background")
                            HMainActivity.restoreTabBarBackground()
                        }.onFailure { XplerLog.e(it) }
                    }
                }
                .method("onDetachedFromWindow") {
                    onAfter {
                        hookBlockRunning(this) {
                            if (!config.isImmersive) return@hookBlockRunning
                            XplerLog.d("Immersive", "live player detached, apply tab bar immersive")
                            HMainActivity.applyTabBarImmersive()
                        }.onFailure { XplerLog.e(it) }
                    }
                }
        }.onFailure { XplerLog.e(it) }
    }
}
