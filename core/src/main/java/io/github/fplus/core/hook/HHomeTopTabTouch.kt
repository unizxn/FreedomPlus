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

        /** 超过这个按住时长就当作长按，交给宿主自己处理 */
        private const val LONG_PRESS_THRESHOLD_MS = 400L

        /** 确认放行的时间窗：包装后的回调真正执行时，服务层 hook 不应再弹一次 */
        private const val BYPASS_WINDOW_MS = 1500L

        @Volatile
        private var bypassUntil: Long = 0L

        fun markBypass() {
            bypassUntil = SystemClock.uptimeMillis() + BYPASS_WINDOW_MS
        }

        fun consumeBypass(): Boolean {
            if (SystemClock.uptimeMillis() > bypassUntil) return false
            bypassUntil = 0L
            return true
        }
    }

    private val config get() = ConfigV1.get()

    /** 防止我们自己的 setXxxListener 再次进入 hook */
    private var wrapping = false

    /** 弹窗未收起前不重复弹 */
    private var dialogShowing = false

    /** 上一次点击的 tab 文案：用来判断这次是"切换"还是"已经在当前 tab 再点一次" */
    private var lastLabel: String? = null

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

                            // 每个 tab 项一份按下时间：用来区分"轻点"和"长按"
                            val downAt = LongArray(1)
                            wrapping = true
                            try {
                                view.setOnTouchListener { v, event ->
                                    handleTouch(v, event, original, downAt)
                                }
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
                                    val label = tabLabel(v)
                                    val needConfirm = shouldConfirm(v, label)
                                    lastLabel = label
                                    if (!needConfirm) {
                                        original.onClick(v)
                                        return@setOnClickListener
                                    }
                                    if (dialogShowing) return@setOnClickListener
                                    askConfirm(v, label) {
                                        markBypass()
                                        original.onClick(v)
                                    }
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
        downAt: LongArray,
    ): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downAt[0] = event.eventTime
        }
        if (!config.isPreventAccidentalTouch) return original.onTouch(view, event)
        if (event.actionMasked != MotionEvent.ACTION_UP) return original.onTouch(view, event)

        // 按住超过长按阈值：交还给宿主，避免和它自己的长按菜单（如「设置单击首页操作」）打架
        if (downAt[0] > 0 && event.eventTime - downAt[0] > LONG_PRESS_THRESHOLD_MS) {
            return original.onTouch(view, event)
        }

        val label = tabLabel(view)
        val needConfirm = shouldConfirm(view, label)
        lastLabel = label
        if (!needConfirm) return original.onTouch(view, event)
        if (dialogShowing) return true

        // 关键：抬手被我们吞掉后，宿主的"长按"计时器还在跑（它只收到了 DOWN），
        // 弹窗期间会自己冒出「设置单击首页操作」。所以补一个 ACTION_CANCEL 把它的手势状态收干净。
        cancelTouch(view, event, original)

        askConfirm(view, label) {
            markBypass()
            replayTouch(view, original)
        }
        // 吞掉这次抬手，宿主收不到点击
        return true
    }

    private fun askConfirm(view: View, label: String, onConfirmed: () -> Unit) {
        dialogShowing = true
        XplerLog.d("AccidentalTouch", "confirm: label=$label class=${view.javaClass.name}")
        showMessageDialog(
            context = view.context,
            title = "温馨提示",
            // 走到这里说明用户已经在该 tab 上（再点一次），所以文案可以明确写"刷新"
            content = if (label == LABEL_HOME) {
                "你已经在首页，是否触发刷新？"
            } else {
                "你已经在推荐，是否触发刷新？"
            },
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

    /** 把被吞掉的手势收尾：通知原监听取消 + 复位按下态，避免宿主长按计时器在弹窗期间触发 */
    private fun cancelTouch(view: View, event: MotionEvent, original: View.OnTouchListener) {
        runCatching {
            val cancel = MotionEvent.obtain(event)
            cancel.action = MotionEvent.ACTION_CANCEL
            original.onTouch(view, cancel)
            cancel.recycle()
        }.onFailure { XplerLog.e(it) }
        view.isPressed = false
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

    /**
     * 只在"当前就已经在这个 tab 上"时才需要确认（即再点一次刷新）。
     *
     * 判断依据：宿主把这个 tab 标成 selected，或者上一次点击的就是同一个 tab。
     * 切换 tab（从「我」点回「首页」）一律直接放行，绝不拦导航。
     */
    private fun shouldConfirm(view: View, label: String): Boolean {
        if (!config.isPreventAccidentalTouch) return false
        return when {
            // 底部首页：用服务层记录的"当前 tab"判断，切换一律放行
            view.javaClass.name == BOTTOM_TAB_ITEM && label == LABEL_HOME ->
                HHomeBottomTabServiceImpl.isCurrent("HOME")
            // 顶部推荐：所有顶部 tab 都是同一类，用当前选中态/上一次点击判断即可
            view.javaClass.name == TOP_TAB_ITEM && label == LABEL_RECOMMEND ->
                view.isSelected || lastLabel == label
            else -> false
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
