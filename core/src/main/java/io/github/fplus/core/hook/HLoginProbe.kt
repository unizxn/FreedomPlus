package io.github.fplus.core.hook

import com.freegang.ktutils.log.KLogCat
import io.github.fplus.core.base.BaseHook
import io.github.xpler.core.XplerLog
import io.github.xpler.core.hookBlockRunning
import io.github.xpler.core.proxy.MethodParam
import java.lang.reflect.Modifier
import java.util.Collections

/**
 * 登录链路观测探针（只读，不修改任何行为）。
 *
 * 目的：回答三个问题
 * 1. 38.6.0 的登录请求里 version_code / version_name 到底在哪一层出现；
 * 2. 服务端 1049 的 errorMsg 和弹窗正文是什么；
 * 3. 更新服务拿到的 latest/min version 能否在登录时复用（不额外发请求）。
 *
 * 打开方式：DouYinMain.searchBefore 里注册 HLoginProbe()。
 * 抓日志：adb logcat -s HLoginProbe
 *
 * 注意：本探针只读，不 setResult、不改参数；定位完成后应关闭/移除。
 */
class HLoginProbe {
    companion object {
        const val TAG = "HLoginProbe"

        /** 探针总开关；定位完成后改为 false 或移除注册。 */
        @Volatile
        var enabled: Boolean = true

    }

    /** NetUtil 每次请求都会调用，按调用栈去重，避免刷屏。 */
    private val loggedStacks = Collections.synchronizedSet(mutableSetOf<String>())

    /** 账号请求按 方法+URL+参数 key 去重。 */
    private val loggedRequests = Collections.synchronizedSet(mutableSetOf<String>())

    init {
        HNetUtilParams()
        HAccountSdkRequest()
        HAccountSaasRequest()
        HAccountNetwork()
        HAccountError()
        HForceUpdateDialog()
        HUpdateInfo()
    }

    private fun log(message: String) {
        if (!enabled) return
        KLogCat.tagI(TAG, message)
    }

    private fun shortStack(): String {
        return Thread.currentThread().stackTrace
            .drop(3)
            .take(12)
            .joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName }
    }

    private fun sanitizeUrl(url: String?): String {
        if (url.isNullOrEmpty()) return "null"
        val path = url.substringBefore('?')
        return if (path.length > 180) path.take(180) + "..." else path
    }

    private fun versionOf(map: Map<*, *>?): String {
        if (map == null) return "null"
        val keys = listOf(
            "version_code",
            "version_name",
            "manifest_version_code",
            "update_version_code",
            "app_version",
            "app_version_code",
        )
        val values = keys.mapNotNull { key ->
            map.entries.firstOrNull { it.key == key }?.value?.let { key + "=" + it }
        }
        return if (values.isEmpty()) "(no version keys)" else values.joinToString(", ")
    }

    private fun mapKeys(map: Map<*, *>?): String {
        if (map == null) return "null"
        return map.keys.take(40).joinToString(",") { it.toString() }
    }

    private fun versionTokens(text: String): String {
        return text.split(' ', ',', ';', '|', '=', '(', ')', '[', ']', '{', '}')
            .filter { token -> token.count { it == '.' } == 2 && token.all { it.isDigit() || it == '.' } }
            .distinct()
            .take(10)
            .joinToString(",")
    }

    private fun dumpFields(obj: Any?, maxLength: Int = 800): String {
        if (obj == null) return "null"
        val sb = StringBuilder()
        var cls: Class<*>? = obj.javaClass
        var depth = 0
        while (cls != null && cls != Any::class.java && depth < 4) {
            for (field in cls.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                runCatching {
                    field.isAccessible = true
                    val value = field.get(obj)
                    val text = when (value) {
                        null -> "null"
                        is String -> {
                            val trimmed = value.trim()
                            when {
                                trimmed.startsWith("http", ignoreCase = true) -> sanitizeUrl(trimmed)
                                trimmed.count { it == '.' } == 2 &&
                                    trimmed.all { it.isDigit() || it == '.' } -> trimmed
                                else -> "(str:" + value.length + ")"
                            }
                        }
                        else -> value.toString().take(100)
                    }
                    sb.append(field.name).append('=').append(text).append("; ")
                }
            }
            if (sb.length > maxLength) break
            cls = cls.superclass
            depth++
        }
        return sb.toString().take(maxLength)
    }

    /** 观测 App 公共参数（version_code 等）是否被写入，以及调用栈来自哪里。 */
    inner class HNetUtilParams : BaseHook() {
        override fun setTargetClass(): Class<*> = findClass("com.ss.android.common.applog.NetUtil")

        override fun onInit() {
            log("HNetUtilParams registered: " + targetClass.name)
        }

        @OnAfter("putCommonParamsWithLevel")
        fun putCommonParamsAfter(params: MethodParam) {
            if (!enabled) return
            hookBlockRunning(params) {
                val map = params.args.getOrNull(0) as? Map<*, *> ?: return@hookBlockRunning
                val version = versionOf(map)
                if (version == "(no version keys)") return@hookBlockRunning

                val stack = shortStack()
                if (loggedStacks.add(stack)) {
                    log("NetUtil version: " + version + " | caller: " + stack)
                }
            }.onFailure { XplerLog.e(it) }
        }
    }

    /** 观测账号 SDK 的请求入口：URL、业务参数 key，以及是否已经带 version。 */
    inner class HAccountSdkRequest : BaseHook() {
        override fun setTargetClass(): Class<*> = findClass("X.C0F2K")

        override fun onInit() {
            log("HAccountSdkRequest registered: " + targetClass.name)
        }

        @OnAfter("LIZIZ", "LIZLLL", "LJ", "LJFF", "LJI", "LJII")
        fun requestAfter(params: MethodParam) {
            if (!enabled) return
            hookBlockRunning(params) {
                val url = params.args.filter { it is String }.map { it as String }
                    .firstOrNull { it.contains("/passport/") || it.startsWith("http") }
                val map = params.args.filter { it is Map<*, *> }.map { it as Map<*, *> }.firstOrNull()
                if (url == null && map == null) return@hookBlockRunning

                val tag = params.method.name + "|" + sanitizeUrl(url) + "|" + mapKeys(map)
                if (!loggedRequests.add(tag)) return@hookBlockRunning

                log("sdk " + params.method.name + " url=" + sanitizeUrl(url) + " keys=" + mapKeys(map) + " version=" + versionOf(map))
            }.onFailure { XplerLog.e(it) }
        }
    }

    /** 观测 SaaS 账号 SDK 的请求入口（38.6.0 登录实际走 IESAccountSaasKit）。 */
    inner class HAccountSaasRequest : BaseHook() {
        override fun setTargetClass(): Class<*> = findClass("X.C23880EwH")

        override fun onInit() {
            log("HAccountSaasRequest registered: " + targetClass.name)
        }

        @OnAfter("LJJLIIIJJI", "LJJJJLL", "LJIIJJI", "LJIIJ", "LJJJJJ")
        fun requestAfter(params: MethodParam) {
            if (!enabled) return
            hookBlockRunning(params) {
                val url = params.args.filter { it is String }.map { it as String }
                    .firstOrNull { it.contains("/passport/") || it.startsWith("http") }
                val map = params.args.filter { it is Map<*, *> }.map { it as Map<*, *> }.firstOrNull()
                if (url == null && map == null) return@hookBlockRunning

                val tag = params.method.name + "|" + sanitizeUrl(url) + "|" + mapKeys(map)
                if (!loggedRequests.add(tag)) return@hookBlockRunning

                log("saas " + params.method.name + " url=" + sanitizeUrl(url) + " keys=" + mapKeys(map) + " version=" + versionOf(map))
            }.onFailure { XplerLog.e(it) }
        }
    }

    /** 观测账号网络层：URL、参数 key、version 字段。 */
    inner class HAccountNetwork : BaseHook() {
        override fun setTargetClass(): Class<*> =
            findClass("com.ss.android.ugc.aweme.account.network.NetworkProxyAccount")

        override fun onInit() {
            log("HAccountNetwork registered: " + targetClass.name)
        }

        @OnAfter("LJIIIIZZ", "LJFF", "LJ", "LJII", "LIZLLL", "LJI", "LJIIJ")
        fun requestAfter(params: MethodParam) {
            if (!enabled) return
            hookBlockRunning(params) {
                val url = params.args.filter { it is String }.map { it as String }
                    .firstOrNull { it.contains("/passport/") || it.startsWith("http") }
                val map = params.args.filter { it is Map<*, *> }.map { it as Map<*, *> }.firstOrNull()
                if (url == null && map == null) return@hookBlockRunning

                val tag = params.method.name + "|" + sanitizeUrl(url) + "|" + mapKeys(map)
                if (!loggedRequests.add(tag)) return@hookBlockRunning

                log("net " + params.method.name + " url=" + sanitizeUrl(url) + " keys=" + mapKeys(map) + " version=" + versionOf(map))
            }.onFailure { XplerLog.e(it) }
        }
    }

    /** 观测服务端错误码/文案（1049 就在这层）。 */
    inner class HAccountError : BaseHook() {
        override fun setTargetClass(): Class<*> = findClass("X.C19870Epo")

        override fun onInit() {
            log("HAccountError registered: " + targetClass.name)
        }

        @OnAfter("LIZLLL")
        fun handleErrorAfter(params: MethodParam) {
            if (!enabled) return
            hookBlockRunning(params) {
                if (params.args.size < 3 || params.args[1] !is Int) return@hookBlockRunning
                val code = params.args[1] as Int
                val message = params.args[2] as? String ?: ""
                log("AccountNetworkResult errorCode=" + code + " errorMsg=" + message.take(300))
            }.onFailure { XplerLog.e(it) }
        }
    }

    /** 观测「应用版本过低」弹窗的正文。 */
    inner class HForceUpdateDialog : BaseHook() {
        override fun setTargetClass(): Class<*> =
            findClass("com.ss.android.ugc.aweme.account.service.flavor.DouyinOSAccountFlavorService")

        override fun onInit() {
            log("HForceUpdateDialog registered: " + targetClass.name)
        }

        @OnAfter("LJIIIZ")
        fun dialogAfter(params: MethodParam) {
            if (!enabled) return
            hookBlockRunning(params) {
                val message = params.args.getOrNull(2) as? String ?: ""
                log("force_update message=" + message.take(300))
            }.onFailure { XplerLog.e(it) }
        }
    }

    /**
     * 观测更新服务：看它是否已经拿到 latest/min version。
     * 只读，不触发任何额外网络请求；用户点「立即升级」或 App 自己检查更新时才有日志。
     */
    inner class HUpdateInfo : BaseHook() {
        override fun setTargetClass(): Class<*> =
            findClass("com.ss.android.ugc.aweme.main.service.AppUpdateService")

        override fun onInit() {
            log("HUpdateInfo registered: " + targetClass.name)
        }

        @OnAfter("LJIILIIL")
        fun updateInfoAfter(params: MethodParam) {
            if (!enabled) return
            hookBlockRunning(params) {
                val arg = params.args.firstOrNull() ?: return@hookBlockRunning
                log("update LJIILIIL arg=" + arg.javaClass.name + " fields=" + dumpFields(arg))
            }.onFailure { XplerLog.e(it) }
        }

        @OnAfter("LJIIL")
        fun updateResultAfter(params: MethodParam) {
            if (!enabled) return
            hookBlockRunning(params) {
                val result = params.result ?: return@hookBlockRunning
                log("update LJIIL result=" + result.javaClass.name + " fields=" + dumpFields(result))
            }.onFailure { XplerLog.e(it) }
        }

        @OnAfter("checkForUpdateNew")
        fun checkAfter(params: MethodParam) {
            if (!enabled) return
            hookBlockRunning(params) {
                val text = params.args.joinToString(" | ") { (it?.javaClass?.name ?: "null") + "=" + it }
                val versions = versionTokens(text)
                if (versions.isNotEmpty()) {
                    log("update check versions=" + versions)
                }
            }.onFailure { XplerLog.e(it) }
        }
    }
}
