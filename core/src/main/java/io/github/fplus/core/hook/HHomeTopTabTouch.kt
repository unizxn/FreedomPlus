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
 * tab 误触复确认：底部「首页」+ 顶部「推荐」。
 *
 * 两处 tab 都不是普通的点击链路：
 *  - 顶部 `HomeTopTabItemViewNew` 自己实现 `View.OnTouchListener`，点击逻辑在混淆类里；
 *  - 底部 `BottomTab` 把点击委托给内部 delegate，服务层的 `LJI(Context, MainTab, String)`
 *    只在切 tab 时触发，点当前「首页」刷新并不会走它（实测确认：无日志、无弹窗）。
 *
 * 所以统一改成**包监听**：谁给这两类 tab 设触摸/点击监听，就替换成"先问一句再转发"。
 * 取消就吞掉这次操作，确定才把原始回调执行一次。
 */
class HHomeTopTabTouch : BaseHook() {
    companion object {
        private const val TOP_TAB_ITEM = "com.ss.android.ugc.aweme.homepage.tab.ui.HomeTopTabItemViewNew"
        private const val BOTTOM_TAB_ITEM = "com.ss.android.ugc.aweme.homepage.tab.ui.bottom.BottomTab"
        private const val LABEL_HOME = "首页"
        private const val LABEL_RECOMMEND = "推荐"
    }

    private val config get() = ConfigV1.get()

    /** 防止我们自己的 setXxxListener 再次进入 hook */
    private var wrapping = false

    /** 弹窗未收起前不重复弹 */
    private var dialogShowing = false

    override fun setTargetClass(): Class<*> = NoneHook::class.java

    init {
        wrapTouchListener()
        wrapClickListener()
    }

    private fun wrapTouchListener() {
        runCatching {
            lparam.hookClass(View::class.java)
                .method("setOnTouchListener", "android.view.View\$OnTouchListener") {
                    onAfter {
                        hookBlockRunning(this) {
                            val view = thisObject as? View ?: return@hookBlockRunning
                            if (wrapping || !isTargetTab(view)) return@hookBlockRunning

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

    private fun wrapClickListener() {
        runCatching {
            lparam.hookClass(View::class.java)
                .method("setOnClickListener", "android.view.View\$OnClickListener") {
                    onAfter {
                        hookBlockRunning(this) {
                            val view = thisObject as? View ?: return@hookBlockRunning
                            if (wrapping || !isTargetTab(view)) return@hookBlockRunning

                            val original = args[0] as? View.OnClickListener ?: return@hookBlockRunning

                            wrapping = true
                            try {
                                view.setOnClickListener { v ->
                                    val label = confirmLabel(v)
                                    if (label == null || dialogShowing) {
                                        original.onClick(v)
                                        return@setOnClickListener
                                    }
                                    askConfirm(v, label) { original.onClick(v) }
                                }
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

        val label = confirmLabel(view) ?: return original.onTouch(view, event)
        if (dialogShowing) return true

        askConfirm(view, label) { replayTouch(view, original) }
        // 吞掉这次抬手，宿主收不到点击
        return true
    }

    private fun askConfirm(view: View, label: String, onConfirmed: () -> Unit) {
        dialogShowing = true
        XplerLog.d("AccidentalTouch", "confirm: label=$label class=${view.javaClass.name}")
        showMessageDialog(
            context = view.context,
            title = "温馨提示",
            content = if (label == LABEL_HOME) "你点击了首页，是否触发刷新？" else "你点击了推荐，是否切换？",
            cancel = "取消",
            confirm = "确定",
            onConfirm = {
                dialogShowing = false
                onConfirmed()
            },
            onCancel = { dialogShowing = false },
            onDismiss = { dialogShowing = false },
        )
    }

    /** 重放一次完整点击，避免宿主只收到 DOWN 没有 UP 而停在按下态 */
    private fun replayTouch(view: View, original: View.OnTouchListener) {
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

    private fun isTargetTab(view: View): Boolean {
        val name = view.javaClass.name
        return name == TOP_TAB_ITEM || name == BOTTOM_TAB_ITEM
    }

    /** 命中需要确认的 tab 时返回其标签，否则 null */
    private fun confirmLabel(view: View): String? {
        if (!config.isPreventAccidentalTouch) return null
        val name = view.javaClass.name
        val label = tabLabel(view)
        return when {
            name == BOTTOM_TAB_ITEM && label == LABEL_HOME -> LABEL_HOME
            name == TOP_TAB_ITEM && label == LABEL_RECOMMEND -> LABEL_RECOMMEND
            else -> null
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
