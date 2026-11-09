package io.github.fplus.core.hook

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import com.freegang.extension.forEachChild
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.hookClass
import io.github.xpler.core.lparam
import java.lang.reflect.Method

/**
 * 底 bar（相关搜索 / 上升热点等）背景透明。
 *
 * 38.6.0 的底 bar 是「槽位 + provider」结构：具体 provider 各不相同
 * （HotSpotYellowBarProvider / FeedBottomLongRelatedBarProvider …），
 * 但背景统一由 FeedCommonBottomBarPresenter 刷
 * （view.setBackgroundColor(BottomBarInfo.container.background_color)，
 * 且发生在 provider 绑定之前）。
 *
 * 所以这里挂宿主：凡是带 BottomBarInfo 参数的方法执行完，就把传入的 View 及其子级背景清成透明，
 * 一处覆盖所有 provider，且不依赖 LJIIJJI 这类混淆方法名。
 */
class HBottomBarBackground : BaseHook() {
    companion object {
        private const val PRESENTER = "com.ss.android.ugc.aweme.feed.bottombar.FeedCommonBottomBarPresenter"
        private const val BOTTOM_BAR_INFO = "BottomBarInfo"
        private const val MAX_DEPTH = 16
    }

    private val config get() = ConfigV1.get()

    override fun setTargetClass(): Class<*> {
        return findClass(PRESENTER)
    }

    init {
        runCatching {
            val clazz = targetClass
            if (clazz.name == NoneHook::class.java.name) return@runCatching

            lparam.hookClass(clazz).methodAll {
                onAfter {
                    hookBlockRunning(this) {
                        if (!config.isImmersive || config.isRelatedSearchOpaque) {
                            return@hookBlockRunning
                        }

                        val currentMethod = method as? Method ?: return@hookBlockRunning
                        if (currentMethod.parameterTypes.none { it.name.endsWith(BOTTOM_BAR_INFO) }) {
                            return@hookBlockRunning
                        }

                        args.forEach { arg ->
                            if (arg is View) clearBarBackground(arg, 0)
                        }
                    }.onFailure {
                        XplerLog.e(it)
                    }
                }
            }
        }.onFailure {
            XplerLog.e(it)
        }
    }

    private fun clearBarBackground(view: View, depth: Int) {
        if (depth > MAX_DEPTH) return

        view.background = ColorDrawable(Color.TRANSPARENT)

        if (view !is ViewGroup) return
        view.forEachChild { child ->
            clearBarBackground(child, depth + 1)
        }
    }
}
