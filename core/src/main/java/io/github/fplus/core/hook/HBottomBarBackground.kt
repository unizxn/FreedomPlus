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
 * 两个时序/层级坑（真机日志确认）：
 * 1. presenter 刷背景 → provider 再绑内容，所以只清一次会被覆盖 → 立即清 + 两拍延迟补清；
 * 2. 「相关搜索」那条的底纹画在 bar 的**祖先**上（container / daf 一层，高度 110 = 一条底纹），
 *    只清 bar 子树清不掉 → 再向上清 4 层祖先（带高度上限，避免误伤整屏）。
 */
class HBottomBarBackground : BaseHook() {
    companion object {
        private const val TAG = "HBottomBarBg"
        private const val PRESENTER = "com.ss.android.ugc.aweme.feed.bottombar.FeedCommonBottomBarPresenter"
        private const val BOTTOM_BAR_INFO = "BottomBarInfo"
        private const val MAX_DEPTH = 16

        /** 向上清理的祖先层数 */
        private const val MAX_ANCESTOR_LEVELS = 4

        /** 祖先高度上限：超过这个高度说明已经到容器层，停止清理 */
        private const val MAX_ANCESTOR_HEIGHT_PX = 480

        /** 延迟补清的两拍（毫秒） */
        private val RETRY_DELAYS = longArrayOf(120L, 600L)

        @Volatile
        private var hitLogged = 0

        /** 底部 scrim 扫描次数（前几次命中各扫一遍，覆盖异步 inflate 的情况） */
        @Volatile
        private var scrimDumpCount = 0
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

                        applyTransparent(barView)

                        RETRY_DELAYS.forEach { delayMs ->
                            barView.postDelayed({
                                runCatching {
                                    if (config.isImmersive && !config.isRelatedSearchOpaque) {
                                        applyTransparent(barView)
                                    }
                                    if (scrimDumpCount < 3 && delayMs == RETRY_DELAYS.last()) {
                                        scrimDumpCount++
                                        dumpAndClearBottomScrim(barView)
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

    /** 清 bar 自身 + 子树 + 向上若干层祖先的背景 */
    private fun applyTransparent(barView: View) {
        clearBarBackground(barView, 0)
        clearAncestorBackgrounds(barView)
    }

    private fun clearBarBackground(view: View, depth: Int) {
        if (depth > MAX_DEPTH) return

        view.background = ColorDrawable(Color.TRANSPARENT)

        if (view !is ViewGroup) return
        view.forEachChild { child ->
            clearBarBackground(child, depth + 1)
        }
    }

    /** 「相关搜索」的底纹画在 bar 的祖先上（container/daf，高度=bar 高度），这里向上清几层 */
    private fun clearAncestorBackgrounds(barView: View) {
        var parent = barView.parent
        var level = 0

        while (parent is ViewGroup && level < MAX_ANCESTOR_LEVELS) {
            val height = parent.height
            if (height in 1..MAX_ANCESTOR_HEIGHT_PX) {
                parent.background = ColorDrawable(Color.TRANSPARENT)
            }
            parent = parent.parent
            level++
        }
    }

    /**
     * 底部区域（屏幕下方约 22%）的半透明 scrim 定位 + 清理。
     *
     * 像素采样证明：视频铺到了 y≈2050，2051 往下同一片画面被压到 ~25% 亮度，
     * 即有一层半透明黑盖在视频上。这里从 barView.rootView 扫一遍底部宽条 View，
     * 打印各自的 background/foreground 类型，并把非空的直接清掉。
     */
    private fun dumpAndClearBottomScrim(barView: View) {
        val root = barView.rootView ?: return
        val dm = root.resources.displayMetrics
        val minBottom = (dm.heightPixels * 0.78f).toInt()

        walkBottom(root, 0, IntArray(2), minBottom, intArrayOf(0))
    }

    private fun walkBottom(
        view: View,
        depth: Int,
        loc: IntArray,
        minBottom: Int,
        counter: IntArray,
    ) {
        if (depth > 32 || counter[0] > 30) return

        // 不再限定高度/宽度：全屏渐变 scrim 是 height=屏幕高 的大 View，之前的过滤把它排除了
        if (view.visibility == View.VISIBLE && (view.background != null || view.foreground != null)) {
            view.getLocationOnScreen(loc)
            val top = loc[1]
            val bottom = top + view.height

            if (bottom > minBottom) {
                counter[0]++
                KLogCat.tagI(
                    TAG,
                    "BOTTOM " + depth + " " + view.javaClass.name +
                        " id=" + idName(view) +
                        " bg=" + describeDrawable(view.background) +
                        " fg=" + describeDrawable(view.foreground) +
                        " a=" + view.alpha +
                        " vis=" + view.visibility +
                        " y=" + top + ".." + bottom +
                        " " + view.width + "x" + view.height,
                )

                if (isScrim(view.background)) view.background = ColorDrawable(Color.TRANSPARENT)
                if (view.foreground != null) view.foreground = null
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walkBottom(view.getChildAt(i), depth + 1, loc, minBottom, counter)
            }
        }
    }

    /** 打印 drawable 的具体信息：ColorDrawable 带 color/alpha，便于识别半透明黑 */
    private fun describeDrawable(drawable: android.graphics.drawable.Drawable?): String {
        if (drawable == null) return "null"

        val name = drawable.javaClass.simpleName
        if (drawable is ColorDrawable) {
            return name + "(#" + Integer.toHexString(drawable.color) + ")"
        }
        return name + "(alpha=" + drawable.alpha + ")"
    }

    /** 只清「半透明或渐变」这类 scrim，避免把不透明底板一起打掉 */
    private fun isScrim(drawable: android.graphics.drawable.Drawable?): Boolean {
        if (drawable == null) return false
        if (drawable is ColorDrawable) {
            val alpha = Color.alpha(drawable.color)
            return alpha in 1..254
        }
        return drawable.javaClass.simpleName.contains("Gradient")
    }

    private fun idName(view: View): String {
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("-")
    }
}
