package io.github.fplus.core.hook

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import com.freegang.extension.forEachChild
import com.freegang.ktutils.log.KLogCat
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
 * （view.setBackgroundColor(BottomBarInfo.container.background_color)）。
 *
 * 时序是难点：presenter 先刷背景 → provider 再绑内容（可能又刷一层），
 * 所以只在该方法 onAfter 清一次会被后续覆盖。这里改成「立即清 + 两拍延迟补清」。
 */
class HBottomBarBackground : BaseHook() {
    companion object {
        private const val TAG = "HBottomBarBg"
        private const val PRESENTER = "com.ss.android.ugc.aweme.feed.bottombar.FeedCommonBottomBarPresenter"
        private const val BOTTOM_BAR_INFO = "BottomBarInfo"
        private const val MAX_DEPTH = 16

        /** 延迟补清的两拍（毫秒），覆盖 provider 绑内容后才刷背景的情况 */
        private val RETRY_DELAYS = longArrayOf(120L, 600L)

        @Volatile
        private var hitLogged = 0

        @Volatile
        private var lateDumped = false
    }

    private val config get() = ConfigV1.get()

    override fun setTargetClass(): Class<*> {
        return findClass(PRESENTER)
    }

    init {
        runCatching {
            KLogCat.tagI(TAG, "init target=" + targetClass.name)

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

                        val barView = args.firstOrNull { it is ViewGroup } as? ViewGroup
                            ?: args.firstOrNull { it is View } as? View
                            ?: return@hookBlockRunning

                        if (hitLogged < 10) {
                            hitLogged++
                            KLogCat.tagI(
                                TAG,
                                "hit " + currentMethod.name + " bar=" + barView.javaClass.name +
                                    " id=" + idName(barView),
                            )
                        }

                        clearBarBackground(barView, 0)

                        // provider 绑内容后可能再刷一层背景，补两拍
                        RETRY_DELAYS.forEach { delayMs ->
                            barView.postDelayed({
                                runCatching {
                                    if (config.isImmersive && !config.isRelatedSearchOpaque) {
                                        clearBarBackground(barView, 0)
                                    }
                                    if (!lateDumped && delayMs == RETRY_DELAYS.last()) {
                                        lateDumped = true
                                        KLogCat.tagI(TAG, "late dump:")
                                        dumpTree(barView, 0, intArrayOf(0))
                                    }
                                }.onFailure { XplerLog.e(it) }
                            }, delayMs)
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

    /** 诊断用：打印底 bar 子树的类名/id/背景类型/尺寸 */
    private fun dumpTree(view: View, depth: Int, counter: IntArray, maxDepth: Int = 6) {
        if (depth > maxDepth || counter[0] > 40) return
        counter[0]++

        KLogCat.tagI(
            TAG,
            "  ".repeat(depth) + view.javaClass.name +
                " id=" + idName(view) +
                " bg=" + (view.background?.javaClass?.simpleName ?: "null") +
                " a=" + view.alpha +
                " vis=" + view.visibility +
                " " + view.width + "x" + view.height,
        )

        if (view is ViewGroup) {
            view.forEachChild { child -> dumpTree(child, depth + 1, counter, maxDepth) }
        }
    }

    private fun idName(view: View): String {
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("-")
    }
}
