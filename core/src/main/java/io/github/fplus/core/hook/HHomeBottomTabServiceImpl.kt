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
 * 底部 tab 误触复确认（目前只拦「首页」）。
 *
 * 38.6.0 的入口是 `HomeBottomTabServiceImpl.LJI(Context, MainTab, String)`（反编译确认），
 * 第三个参数是 tab 类型，首页为 @@HOME@@。旧实现按 `(Context, String, View)` 匹配，
 * 在这个版本上一直报 NoSuchMethodException，等于没生效，现改为按真实签名显式 hook。
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

                            val context = args[0] as? Context ?: return@hookBlockRunning
                            val tabView = args[1] as? View ?: return@hookBlockRunning

                            XplerLog.d(
                                "AccidentalTouch",
                                "confirm: home class=${tabView.javaClass.name}"
                            )
                            showMessageDialog(
                                context = context,
                                title = "温馨提示",
                                content = "你点击了首页，是否触发刷新？",
                                cancel = "取消",
                                confirm = "确定",
                                onConfirm = {
                                    // performClick 会同步再进一次 LJI，用标记放行这一次，出来后复位
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
