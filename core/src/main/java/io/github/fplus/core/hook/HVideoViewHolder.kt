package io.github.fplus.core.hook

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import com.freegang.extension.asOrNull
import com.freegang.extension.dip2px
import com.freegang.extension.findField
import com.freegang.extension.findFieldGetValue
import com.freegang.extension.findMethodInvoke
import com.freegang.extension.firstParentOrNull
import com.freegang.extension.forEachChild
import com.freegang.extension.getBrotherViewAt
import com.freegang.extension.postRunning
import com.freegang.extension.setLayoutWidth
import com.ss.android.ugc.aweme.feed.model.Aweme
import com.ss.android.ugc.aweme.feed.ui.FeedRightScaleView
import com.ss.android.ugc.aweme.feed.ui.PenetrateTouchRelativeLayout
import io.github.fplus.core.R
import io.github.fplus.core.base.BaseHook
import io.github.fplus.core.config.ConfigV1
import io.github.fplus.core.helper.AutoPlayHelper
import io.github.fplus.core.helper.DexkitBuilder
import io.github.fplus.plugin.injectRes
import io.github.xpler.core.XplerLog
import io.github.xpler.core.entity.NoneHook
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam
import io.github.xpler.loader.hostClassloader

class HVideoViewHolder : BaseHook() {
    companion object {
        /** 用类名比较替代代理类的 `is`/`as`，避免其在目标抖音版本不存在时抛 NoClassDefFoundError */
        private const val FEED_RIGHT_SCALE_VIEW =
            "com.ss.android.ugc.aweme.feed.ui.FeedRightScaleView"

        @get:Synchronized
        @set:Synchronized
        var aweme: Aweme? = null
    }

    private val config get() = ConfigV1.get()

    private var onDrawMaps = mutableMapOf<String, ViewTreeObserver.OnDrawListener?>()

    /**
     * 精确关键字（`,` / `，` 拆分），供「视频项」级扫描使用。
     *
     * 与 [videoOptionBarFilterKeywords] 的正则分开：右侧栏内部走 contains 匹配，
     * 这里只认**文本完全相等**或 contentDescription 包含，避免把简介正文里
     * 偶然出现的同一个词一起埋掉。
     */
    private val videoOptionBarFilterKeywordList by lazy {
        config.videoOptionBarFilterKeywords
            .split(',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private val videoOptionBarFilterKeywords by lazy {
        config.videoOptionBarFilterKeywords
            .replace("，", ",")
            .replace("\\s".toRegex(), "")
            .removePrefix(",").removeSuffix(",")
            .replace(",".toRegex(), "|")
            .replace("\\|+".toRegex(), "|")
            .toRegex()
    }

    override fun setTargetClass(): Class<*> {
        return DexkitBuilder.videoViewHolderClazz ?: NoneHook::class.java
    }

    private fun addOnDraw(view: View?) {
        if (view == null) {
            XplerLog.d("addOnDraw", "view == null")
            return
        }

        val key = Integer.toHexString(System.identityHashCode(view))

        onDrawMaps.putIfAbsent(key, ViewTreeObserver.OnDrawListener {
            if (config.isTranslucent) {
                val alpha = config.translucentValue[1] / 100f
                if (view.alpha > alpha) {
                    view.alpha = alpha
                }
            }

            if (config.isNeatMode) {
                if (config.neatModeState) {
                    view.isVisible = !HPlayerController.isPlaying
                    HMainActivity.toggleView(view.isVisible)
                }
            }
        })

        view.viewTreeObserver.addOnDrawListener(onDrawMaps[key])
    }

    private fun removeOnDraw(view: View?) {
        if (view == null) {
            XplerLog.d("removeOnDraw", "view == null")
            return
        }

        val key = Integer.toHexString(System.identityHashCode(view))
        view.viewTreeObserver.removeOnDrawListener(onDrawMaps[key])
    }

    private fun testOnDraw(tag: String) {
        val array = onDrawMaps.map { "${it.key} = ${it.value}" }.toTypedArray()
        XplerLog.d(tag, *array)
    }

    private fun testAllOnDraw(view: View?) {
        if (view == null) {
            XplerLog.d("removeOnDraw", "view == null")
            return
        }

        val first = view.viewTreeObserver.findFieldGetValue<List<*>> { name("mOnDrawListeners") } ?: return
        XplerLog.d("监听集合", *first.map { "$it" }.toTypedArray())
    }

    private fun openAutoPlay(context: Context) {
        if (!config.isAutoPlay)
            return

        AutoPlayHelper.openAutoPlay(context)
    }

    private fun getContext(params: MethodParam): Context? {
        return params.thisObject?.findMethodInvoke<Context> { name("getContext") }
    }

    private fun getAllView(params: MethodParam): List<View?> {
        val views = params.thisObject?.findFieldGetValue<List<View?>> { type(View::class.java) }
        return views ?: emptyList()
    }

    private fun getWidgetContainer(params: MethodParam): PenetrateTouchRelativeLayout? {
        return params.thisObject?.findFieldGetValue<PenetrateTouchRelativeLayout> {
            type(PenetrateTouchRelativeLayout::class.java)
        }
    }

    private fun getFeedRightScaleView(params: MethodParam): ViewGroup? {
        val views = params.thisObject?.findField {
            type(View::class.java, true)
        }?.getValues(params.thisObject) ?: return null
        // 用类名比较替代 `it is FeedRightScaleView`：aweme 的代理类是 compileOnly，
        // 不打进 APK，运行时由抖音提供；若该类在目标版本不存在，`is`/`as` 会抛
        // NoClassDefFoundError，而这里跑在 post 回调中无人捕获，会直接打崩抖音。
        return views.firstOrNull { (it as? View)?.javaClass?.name == FEED_RIGHT_SCALE_VIEW } as? ViewGroup
    }

    private fun getFragment(params: MethodParam): Any? {
        return params.thisObject?.findFieldGetValue<Any> {
            type(hostClassloader!!.loadClass("androidx.fragment.app.Fragment"))
        }
    }

    private fun getFragmentType(params: MethodParam): String {
        val fragment = getFragment(params)
        val arguments = fragment?.findMethodInvoke<Bundle> { name("getArguments") }
        return arguments?.getString("com.ss.android.ugc.aweme.intent.extra.EVENT_TYPE") ?: ""
    }

    private fun changeFeedRightScaleView(params: MethodParam) {
        if (!config.isVideoOptionBarFilter) {
            return
        }

        val view = getFeedRightScaleView(params)
        view?.postRunning {
            val isAvatarImageWithLive = videoOptionBarFilterKeywords.pattern.contains("头像")
            view.forEachChild { child ->
                if (isAvatarImageWithLive && child.javaClass.name.contains("AvatarImageWithLive")) {
                    val parentView = child.firstParentOrNull(RelativeLayout::class.java)
                    parentView?.isVisible = false
                }

                if ("${child.contentDescription}".contains(videoOptionBarFilterKeywords)) {
                    val parentView = child.firstParentOrNull(FrameLayout::class.java)
                    parentView?.isVisible = false
                }

                if (child is TextView && "${child.text}".contains(videoOptionBarFilterKeywords)) {
                    val parentView = child.firstParentOrNull(FrameLayout::class.java)
                    parentView?.isVisible = false
                }
            }
            val isMusicContainer = videoOptionBarFilterKeywords.pattern.contains("音乐")
            view.getBrotherViewAt(1)?.isVisible = !isMusicContainer
        }
    }

    /**
     * 右侧栏之外的两侧控件（如右下角「拍同款」、左下角标签行里的「点击推荐」）。
     *
     * 它们与 `FeedRightScaleView` 是**兄弟节点**（同在视频项根 View 下），不在栏内，
     * 所以 [changeFeedRightScaleView] 扫不到。38.6.0 实测结构：
     *  - 「拍同款」：`FrameLayout #ack` > `RelativeLayout #ge` > `ViewGroup #z8` > `TextView #z=`
     *  - 「点击推荐」：简介块 > `FrameLayout #ace` > `LinearLayout #dcs` > `TagLayout #r6i` > `LinearLayout #fbg`
     *
     * 命中后隐藏其**父容器**（且尺寸要够小），既能整颗消失，又不会把音乐光盘、
     * 简介正文这类大块带走。
     */
    private fun hideKeywordBlocks(params: MethodParam) {
        if (!config.isVideoOptionBarFilter) {
            return
        }

        val keywords = videoOptionBarFilterKeywordList
        if (keywords.isEmpty()) {
            return
        }

        val rail = getFeedRightScaleView(params) ?: return
        val root = getItemRoot(rail) ?: return
        if (root.width <= 0 || root.height <= 0) {
            return
        }

        root.postRunning {
            val maxWidth = root.width * 0.45f
            val maxHeight = root.height * 0.2f

            root.forEachChild { child ->
                if (child === root || child === rail) return@forEachChild

                val text = (child as? TextView)?.text?.toString()?.trim().orEmpty()
                val description = child.contentDescription?.toString().orEmpty()
                val matched = (text.isNotEmpty() && keywords.any { it == text }) ||
                        (description.isNotEmpty() && keywords.any { description.contains(it) })
                if (!matched) return@forEachChild

                val parent = child.parent as? ViewGroup
                val target = when {
                    parent != null && parent.isSmallBlock(maxWidth, maxHeight) -> parent
                    child.isSmallBlock(maxWidth, maxHeight) -> child
                    // 命中在简介正文这类大块文本里：不动，避免把整段文案隐藏掉
                    else -> null
                }
                target?.isVisible = false
            }
        }
    }

    private fun View.isSmallBlock(maxWidth: Float, maxHeight: Float): Boolean {
        return width in 1..maxWidth.toInt() && height in 1..maxHeight.toInt()
    }

    /**
     * 从右侧栏往上找到视频项根 View（feed RecyclerView 的直接子 View），不依赖 aweme 类。
     *
     * 加层数上限：万一宿主没有用 RecyclerView 承载 feed，不能一路爬到 decor 去扫整窗。
     */
    private fun getItemRoot(view: View): View {
        var current: View = view
        var level = 0
        while (level < 8) {
            val parent = current.parent as? View ?: return current
            if (parent.javaClass.name.contains("RecyclerView")) return current
            current = parent
            level++
        }
        return current
    }

    private fun changeFeedRightScaleViewAlpha(params: MethodParam) {
        if (!config.isTranslucent) {
            return
        }

        val view = getFeedRightScaleView(params)
        view?.alpha = config.translucentValue[2] / 100f
        view?.getBrotherViewAt(1)?.alpha = config.translucentValue[2] / 100f // 音乐
    }

    private fun adjustMusicContainer(params: MethodParam) {
        val view = getFeedRightScaleView(params)
        val isMusicContainer = view?.getBrotherViewAt(1)?.asOrNull<FrameLayout>()
        isMusicContainer?.setLayoutWidth(52f.dip2px())
    }

    @SuppressLint("SetTextI18n")
    private fun addAutoPlayButtonView(params: MethodParam) {
        if (!config.isAutoPlay)
            return

        if (!config.addAutoPlayButton)
            return

        if (!getFragmentType(params).startsWith("homepage_hot"))
            return

        val view = getFeedRightScaleView(params) ?: return

        val isAdded = view.children.firstOrNull()?.tag == "AutoPlay"
        if (isAdded)
            return

        injectRes(view.context.resources)

        val autoPlayContainer = LinearLayout(view.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.END
                updatePaddingRelative(end = 8f.dip2px())
            }
            tag = "AutoPlay"
        }

        val autoPlayImage = ImageView(view.context).apply {
            setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_play))
            layoutParams = LinearLayout.LayoutParams(
                42f.dip2px(),
                42f.dip2px()
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        val autoPlayText = TextView(view.context).apply {
            text = "连播"
            textSize = 14f
            setTextColor(Color.parseColor("#E6FFFFFF"))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        autoPlayContainer.addView(autoPlayImage)
        autoPlayContainer.addView(autoPlayText)
        autoPlayContainer.setOnClickListener { openAutoPlay(it.context) }

        view.addView(autoPlayContainer, 0)
    }

    @OnBefore("isCleanMode")
    fun isCleanModeBefore(params: MethodParam, view: View?, bool: Boolean) {
        hookBlockRunning(params) {
            if (!config.isNeatMode)
                return

            if (!config.neatModeState)
                return

            setResultVoid()
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnBefore("openCleanMode")
    fun openCleanModeBefore(params: MethodParam, bool: Boolean) {
        hookBlockRunning(params) {
            if (!config.isNeatMode)
                return

            if (!config.neatModeState)
                return

            setResultVoid()
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnAfter("getAweme")
    fun getAwemeAfter(params: MethodParam) {
        hookBlockRunning(params) {
            HVideoViewHolder.aweme = result?.asOrNull()
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnAfter
    fun startStayTime(params: MethodParam, long: Long?) {
        hookBlockRunning(params) {
            // XplerLog.d("long: $long")
            changeFeedRightScaleView(params)
            hideKeywordBlocks(params)
            changeFeedRightScaleViewAlpha(params)
            adjustMusicContainer(params)
            addAutoPlayButtonView(params)
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnAfter("onViewHolderSelected")
    fun onViewHolderSelectedAfter(params: MethodParam, index: Int) {
        hookBlockRunning(params) {
            // XplerLog.d("onViewHolderSelected")
            val container = getWidgetContainer(params)
            addOnDraw(container)
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnAfter("onViewHolderUnSelected")
    fun onViewHolderUnSelectedAfter(params: MethodParam) {
        hookBlockRunning(params) {
            // XplerLog.d("onViewHolderUnSelected")
            val container = getWidgetContainer(params)
            removeOnDraw(container)
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnBefore("onPause")
    fun onPauseBefore(params: MethodParam) {
        hookBlockRunning(params) {
            val container = getWidgetContainer(params)
            removeOnDraw(container)
            onDrawMaps.clear()
        }.onFailure {
            XplerLog.e(it)
        }
    }

    @OnAfter("onResume")
    fun onResumeAfter(params: MethodParam) {
        hookBlockRunning(params) {
            val container = getWidgetContainer(params)
            addOnDraw(container)
            changeFeedRightScaleView(params)
            hideKeywordBlocks(params)
            addAutoPlayButtonView(params)
        }.onFailure {
            XplerLog.e(it)
        }
    }
}