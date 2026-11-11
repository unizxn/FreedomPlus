package io.github.fplus.core.hook

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import com.freegang.extension.forEachChild
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.hookClass
import io.github.xpler.core.lparam

/**
 * 顶部 tab 误触复确认（目前只拦「推荐」）。
 *
 * 顶部 tab 的点击不走普通 OnClickListener：38.6.0 的 `HomeTopTabItemViewNew`
 * 自己实现了 `View.OnTouchListener`（点击逻辑在混淆类里，静态难跟），所以这里不追内部实现，
 * 而是在它调用 `setOnTouchListener` 时把监听包一层：抬手时先弹确认，取消就吞掉这次手势，
 * 确定才把这一下真正转给宿主。
 *
 * 只处理 `HomeTopTabItemViewNew`，底部 tab 由 [HHomeBottomTabServiceImpl] 负责，避免重复弹窗。
 */
class HHomeTopTabTouch : BaseHook() {
    companion object {
        private const val TOP_TAB_ITEM = "com.ss.android.ugc.aweme.homepage.tab.ui.HomeTopTabItemViewNew"
        private const val TARGET_LABEL = "推荐"
    }

    private val config get() = ConfigV1.get()

    /** 防止我们自己的 setOnTouchListener 再次进入 hook */
    private var wrapping = false

    override fun setTargetClass(): Class<*> = NoneHook::class.java

    init {
        wrapTouchListener()
    }

    private fun wrapTouchListener() {
        runCatching {
            lparam.hookClass(View::class.java)
                .method("setOnTouchListener", "android.view.View\$OnTouchListener") {
                    onAfter {
                        hookBlockRunning(this) {
                            val view = thisObject as? View ?: return@hookBlockRunning
                            if (wrapping) return@hookBlockRunning
                            if (view.javaClass.name != TOP_TAB_ITEM) return@hookBlockRunning

                            val original = args[0] as? View.OnTouchListener ?: return@hookBlockRunning

                            wrapping = true
                            try {
                                view.setOnTouchListener { v, event -> handleTouch(v, event, original) }
                            } finally {
                                wrapping = false
                            }
                        }.onFailure { XplerLog.e(it) }
                    }
                }
        }.onFailure { XplerLog.e(it) }
    }

    private fun handleTouch(
        view: View,
        event: MotionEvent,
        original: View.OnTouchListener,
    ): Boolean {
        if (!config.isPreventAccidentalTouch) return original.onTouch(view, event)
        if (event.actionMasked != MotionEvent.ACTION_UP) return original.onTouch(view, event)

        val label = tabLabel(view)
        if (label != TARGET_LABEL) return original.onTouch(view, event)

        XplerLog.d("AccidentalTouch", "confirm: topTab label=$label class=${view.javaClass.name}")
        showMessageDialog(
            context = view.context,
            title = "温馨提示",
            content = "你点击了推荐，是否切换？",
            cancel = "取消",
            confirm = "确定",
            onConfirm = { replayClick(view, original) },
        )
        // 吞掉这次抬手，宿主收不到点击
        return true
    }

    /** 重放一次完整点击，避免宿主只收到 DOWN 没有 UP 而停在按下态 */
    private fun replayClick(view: View, original: View.OnTouchListener) {
        val now = SystemClock.uptimeMillis()
        val x = view.width / 2f
        val y = view.height / 2f
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + 60L, MotionEvent.ACTION_UP, x, y, 0)
        try {
            original.onTouch(view, down)
            original.onTouch(view, up)
        } catch (e: Throwable) {
            XplerLog.e(e)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    /** tab 文案是运行时填的，所以点击那一刻再从子树里取（取第一个短文本） */
    private fun tabLabel(view: View): String {
        var label = ""
        runCatching {
            view.forEachChild { child ->
                if (label.isEmpty() && child is TextView) {
                    val text = child.text?.toString()?.trim().orEmpty()
                    if (text.isNotEmpty() && text.length <= 6) {
                        label = text
                    }
                }
            }
        }.onFailure { XplerLog.e(it) }
        return label
    }
}
