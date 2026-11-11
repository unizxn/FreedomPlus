package io.github.fplus.core.hook

import android.content.Context
import android.view.View
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.xpler.core.XplerLog
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.hookClass
import io.github.xpler.core.lparam

/**
 * 底部 tab 误触复确认（「首页」的切换路径）。
 *
 * 两个入口并存，缺一不可：
 *  - **切换 tab**：「消息/朋友 → 首页」走 `HomeBottomTabServiceImpl.LJI(Context, MainTab, String)`，
 *    第三个参数首页为 @@HOME@@（38.6.0 反编译确认），本类负责；
 *  - **当前页再点一下刷新**：不走上面那条，由 [HHomeTopTabTouch] 包住 `BottomTab` 的监听负责。
 *
 * 两边用 [HHomeTopTabTouch.consumeBypass] 互斥：包装层放行后，这里不再重复弹窗。
 */
class HHomeBottomTabServiceImpl : BaseHook() {
    companion object {
        private const val SERVICE_CLASS =
            "com.ss.android.ugc.aweme.homepage.tab.business.bottom.HomeBottomTabServiceImpl"
        private const val TAB_HOME = "HOME"
    }

    private val config get() = ConfigV1.get()

    private var clickAgain: Boolean = false

    override fun setTargetClass(): Class<*> {
        return findClass(SERVICE_CLASS)
    }

    init {
        hookTabClick()
    }

    private fun hookTabClick() {
        runCatching {
            lparam.hookClass(targetClass)
                .method(
                    "LJI",
                    "android.content.Context",
                    "com.ss.android.ugc.aweme.homepage.ui.view.MainTab",
                    "java.lang.String",
                ) {
                    onBefore {
                        hookBlockRunning(this) {
                            if (!config.isPreventAccidentalTouch) return@hookBlockRunning
                            if (args[2] as? String != TAB_HOME) return@hookBlockRunning
                            if (clickAgain) return@hookBlockRunning
                            // 包装层刚放行过（同一手势），别弹第二次
                            if (HHomeTopTabTouch.consumeBypass()) return@hookBlockRunning

                            val context = args[0] as? Context ?: return@hookBlockRunning
                            val tabView = args[1] as? View ?: return@hookBlockRunning

                            XplerLog.d(
                                "AccidentalTouch",
                                "confirm: home(switch) class=${tabView.javaClass.name}"
                            )
                            showMessageDialog(
                                context = context,
                                title = "温馨提示",
                                content = "你点击了首页，是否触发刷新？",
                                cancel = "取消",
                                confirm = "确定",
                                onConfirm = {
                                    clickAgain = true
                                    try {
                                        tabView.performClick()
                                    } finally {
                                        clickAgain = false
                                    }
                                },
                            )
                            setResultVoid()
                        }.onFailure { XplerLog.e(it) }
                    }
                }
        }.onFailure { XplerLog.e(it) }
    }
}
