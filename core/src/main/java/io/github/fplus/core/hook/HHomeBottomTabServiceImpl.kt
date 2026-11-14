package io.github.fplus.core.hook

import io.github.fplus.core.base.BaseHook
import io.github.xpler.core.XplerLog
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.hookClass
import io.github.xpler.core.lparam

/**
 * 只做一件事：追踪"底部当前在哪个 tab"。
 *
 * `HomeBottomTabServiceImpl.LJI(Context, MainTab, String)` 是底部 tab 切换的必经之路，
 * 第三个参数就是目标 tab 类型（首页为 `HOME`）。这里在方法执行完记录一下，
 * 供 [HHomeTopTabTouch] 判断"这次点首页到底是切换还是重复点击"。
 *
 * 不在这一层弹窗：它同时也会被"从别的 tab 切回首页"触发，在这里拦就会把导航卡死（已实测）。
 */
class HHomeBottomTabServiceImpl : BaseHook() {
    companion object {
        private const val SERVICE_CLASS =
            "com.ss.android.ugc.aweme.homepage.tab.business.bottom.HomeBottomTabServiceImpl"

        /** 当前底部 tab 类型，null 表示还没观察到任何切换 */
        @Volatile
        private var currentTabType: String? = null

        /**
         * 判断底部当前是不是某个 tab。
         *
         * currentTabType 为 null = 冷启动后还没发生过切换（[LJI] 只在切换时调用），
         * 而抖音启动就停在首页，所以按 HOME 处理 —— 否则「打开就点首页」会被当成切换，
         * 直接刷新、不弹确认。
         */
        fun isCurrent(tabType: String): Boolean {
            val type = currentTabType ?: return tabType == "HOME"
            return type == tabType
        }
    }

    override fun setTargetClass(): Class<*> {
        return findClass(SERVICE_CLASS)
    }

    init {
        hookTabSwitch()
    }

    private fun hookTabSwitch() {
        runCatching {
            lparam.hookClass(targetClass)
                .method(
                    "LJI",
                    "android.content.Context",
                    "com.ss.android.ugc.aweme.homepage.ui.view.MainTab",
                    "java.lang.String",
                ) {
                    onAfter {
                        hookBlockRunning(this) {
                            val type = args[2] as? String ?: return@hookBlockRunning
                            if (currentTabType != type) {
                                XplerLog.d("AccidentalTouch", "currentTab=$type")
                            }
                            currentTabType = type
                        }.onFailure { XplerLog.e(it) }
                    }
                }
        }.onFailure { XplerLog.e(it) }
    }
}
