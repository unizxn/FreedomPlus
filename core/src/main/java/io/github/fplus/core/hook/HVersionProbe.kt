package io.github.fplus.core.hook

import android.os.Environment
import com.freegang.ktutils.log.KLogCat
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import io.github.fplus.core.base.BaseHook
import io.github.xpler.core.XplerLog
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam
import java.io.File
import java.util.Collections

private const val VP_TAG = "HVersionProbe"

/** 总开关：false 时完全只读。 */
@Volatile
private var vpEnabled: Boolean = true

@Volatile
private var vpFakeCode: String = ""

@Volatile
private var vpFakeName: String = ""

@Volatile
private var vpFakeUpdateCode: String = ""

@Volatile
private var vpLastLoadAt: Long = 0L

private val vpHooked = Collections.synchronizedSet(mutableSetOf<String>())
private val vpLogged = Collections.synchronizedSet(mutableSetOf<String>())

/** 伪装目标文件；不存在 = 只记录不修改。 */
private fun vpSpoofFile(): File? = runCatching {
    File(Environment.getExternalStorageDirectory(), "Download/Freedom/freedom_version.txt")
}.getOrNull()

/** 文件内容：version_code version_name [update_version_code]，空格分隔。3 秒缓存。 */
private fun vpLoadIfNeeded() {
    val now = System.currentTimeMillis()
    if (now - vpLastLoadAt < 3000L) return
    vpLastLoadAt = now
    val file = vpSpoofFile() ?: return
    if (!file.exists()) return
    val text = runCatching { file.readText() }.getOrNull() ?: return
    val parts = text.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
    if (parts.size < 2) return
    val code = parts[0]
    val name = parts[1]
    val updateCode = parts.getOrNull(2) ?: code
    if (code == vpFakeCode && name == vpFakeName && updateCode == vpFakeUpdateCode) return
    vpFakeCode = code
    vpFakeName = name
    vpFakeUpdateCode = updateCode
    KLogCat.tagI(VP_TAG, "伪装目标已加载: code=" + code + " name=" + name + " update=" + updateCode)
}

private fun vpReplacementFor(methodName: String): String {
    vpLoadIfNeeded()
    return when (methodName) {
        "getVersionCode", "getManifestVersionCode" -> vpFakeCode
        "getVersionName" -> vpFakeName
        "getUpdateVersionCode" -> vpFakeUpdateCode
        else -> ""
    }
}

/**
 * 版本号伪装探针（登录 1049 试验）。
 *
 * 静态分析结论（38.6.0）：
 * - `CronetUrlRequestContext` 每次建请求都会从 `ICronetAppProvider` 逐个取
 *   getVersionCode / getVersionName / getManifestVersionCode / getUpdateVersionCode
 *   组装 `TTAppInfoProvider.AppInfo` 交给 native；
 * - `TTNetInit.setCronetDepend(provider)` 是 provider 唯一注入点，`sCronetProvider` 是 public static；
 * - 所以 UA、native 公共参数、签名载荷里的版本号同源于这几个 Java getter，改这里签名仍自洽。
 *
 * 用法：把目标版本写到 /sdcard/Download/Freedom/freedom_version.txt
 *   410100 41.1.0 [410100]
 * 文件不存在 = 只记录不修改；改文件 3 秒内生效。
 *
 * 日志：adb logcat -s HVersionProbe
 */
class HVersionProbe {

    init {
        HCronetDepend()
        HJavaUserAgent()
    }

    private fun log(message: String) {
        if (!vpEnabled) return
        KLogCat.tagI(VP_TAG, message)
    }

    /** 从 TTNetInit 拿到 provider 实例，按运行时类动态 hook 四个版本 getter。 */
    private inner class HCronetDepend : BaseHook() {
        override fun setTargetClass(): Class<*> = findClass("com.bytedance.ttnet.TTNetInit")

        override fun onInit() {
            log("HCronetDepend registered: " + targetClass.name)
            // 兜底：hook 装好之前 TTNet 可能已经初始化过，直接读静态字段。
            runCatching {
                val field = targetClass.getDeclaredField("sCronetProvider")
                field.isAccessible = true
                val provider = field.get(null)
                if (provider != null) hookProvider(provider.javaClass)
            }.onFailure { XplerLog.e(it) }
        }

        @OnAfter("setCronetDepend")
        fun afterSetCronetDepend(params: MethodParam) {
            hookBlockRunning(params) {
                val provider = params.args.firstOrNull() ?: return@hookBlockRunning
                hookProvider(provider.javaClass)
            }.onFailure { XplerLog.e(it) }
        }
    }

    private fun hookProvider(clazz: Class<*>) {
        if (!vpHooked.add(clazz.name)) return
        log("provider 实现类 = " + clazz.name)

        val names = setOf(
            "getVersionCode",
            "getVersionName",
            "getManifestVersionCode",
            "getUpdateVersionCode",
        )

        // 实现类可能只覆盖了部分 getter，沿父类链找。
        var current: Class<*>? = clazz
        var depth = 0
        while (depth < 6) {
            val c = current ?: break
            if (c == Any::class.java) break
            c.declaredMethods
                .filter { it.name in names && it.parameterTypes.isEmpty() && it.returnType == String::class.java }
                .forEach { method -> installGetterHook(method) }
            current = c.superclass
            depth++
        }
    }

    private fun installGetterHook(method: java.lang.reflect.Method) {
        val key = method.declaringClass.name + "#" + method.name
        if (!vpHooked.add(key)) return
        runCatching {
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    val original = param.result as? String ?: return
                    if (vpLogged.add(method.name + "=" + original)) {
                        KLogCat.tagI(VP_TAG, "provider " + method.name + " = " + original)
                    }
                    if (!vpEnabled) return
                    val replacement = vpReplacementFor(method.name)
                    if (replacement.isEmpty() || replacement == original) return
                    param.result = replacement
                    if (vpLogged.add("replace:" + method.name + "=" + replacement)) {
                        KLogCat.tagI(VP_TAG, "provider " + method.name + " " + original + " -> " + replacement)
                    }
                }
            })
        }.onFailure { XplerLog.e(it) }
    }

    /** 顺带记录 Java 侧 UA，确认它是否也带版本号。只读。 */
    private inner class HJavaUserAgent : BaseHook() {
        override fun setTargetClass(): Class<*> = findClass("com.ss.android.common.util.NetworkUtils")

        override fun onInit() {
            log("HJavaUserAgent registered: " + targetClass.name)
        }

        @OnAfter("getUserAgent")
        fun afterGetUserAgent(params: MethodParam) {
            if (!vpEnabled) return
            hookBlockRunning(params) {
                val ua = params.result as? String ?: return@hookBlockRunning
                if (vpLogged.add("ua=" + ua)) {
                    log("java UA = " + ua.take(300))
                }
            }.onFailure { XplerLog.e(it) }
        }
    }
}
