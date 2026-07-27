package io.mo.xiaoaiplug.config

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** 更新日志里的一行。要么是分组标题（`### 新功能`），要么是一个列表项。 */
data class ReleaseNote(
    val text: String,
    val kind: NoteKind,
    /** 列表项的嵌套深度，0 = 顶层。[NoteKind.SECTION] 恒为 0。 */
    val indent: Int = 0
)

enum class NoteKind {
    /** `#`/`##`/`###` 开头的分组标题。 */
    SECTION,
    /** 普通条目（可能带缩进层级）。 */
    ITEM
}

/** 一个可以升级过去的版本。 */
data class ReleaseInfo(
    /** 归一化版本号，只剩数字和点，如 `1.0.5`。比较大小用的就是它。 */
    val version: String,
    /** 展示用，如 `V1.0.5`。 */
    val displayVersion: String,
    /** 更新日志，已经从 release body 的 markdown 里剥出来，保留分组和缩进。可能为空。 */
    val notes: List<ReleaseNote>,
    /** APK 体积（字节）。0 表示这个 release 没挂 APK 附件。 */
    val sizeBytes: Long,
    /** 点「立即更新」要打开的地址：优先 APK 直链，退到 release 页面。 */
    val downloadUrl: String
) {
    /** `48.2 MB`；没挂附件时为 null，界面上那行直接不显示。 */
    val sizeText: String? get() = if (sizeBytes <= 0L) null else formatSize(sizeBytes)
}

sealed interface UpdateResult {
    data class Available(val release: ReleaseInfo) : UpdateResult
    data object UpToDate : UpdateResult
    /** [reason] 是给人看的短句，直接往界面上贴。 */
    data class Failed(val reason: String) : UpdateResult
}

/**
 * 查 GitHub Releases 有没有新版本。
 *
 * 只读 `/releases/latest` —— 这个端点本身就会跳过 prerelease 和 draft，
 * 所以不需要自己过滤「测试版」。
 *
 * 全部是阻塞调用，**必须在 IO 线程上跑**。
 */
object UpdateChecker {

    private const val OWNER_REPO = "lm060719/XiaoAi-plug"
    private const val LATEST_API = "https://api.github.com/repos/$OWNER_REPO/releases/latest"
    private const val LATEST_PAGE = "https://github.com/$OWNER_REPO/releases/latest"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    /** 弹窗里最多列几行。卡片里的日志区可滚动，这个上限只是防住异常长的 release body。 */
    private const val MAX_NOTES = 40

    /** `**bold**`、`__x__`、行内 `code` 的标记符，剥掉只留字。 */
    private val MARKDOWN_NOISE = Regex("\\*\\*|__|`")

    fun check(context: Context): UpdateResult {
        val current = normalizeVersion(currentVersion(context) ?: "")
        if (current.isEmpty()) return UpdateResult.Failed("读不到当前版本号")

        val json = try {
            fetchLatest()
        } catch (t: Throwable) {
            return UpdateResult.Failed(describe(t))
        }

        val release = parse(json) ?: return UpdateResult.Failed("更新信息格式不对")
        return if (compareVersion(release.version, current) > 0) {
            UpdateResult.Available(release)
        } else {
            UpdateResult.UpToDate
        }
    }

    /** 本机装的版本名。minSdk 33，所以直接走不带弃用警告的 PackageInfoFlags 重载。 */
    fun currentVersion(context: Context): String? = runCatching {
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            .versionName
    }.getOrNull()

    private fun fetchLatest(): JSONObject {
        val conn = URL(LATEST_API).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            // GitHub 对不带 User-Agent 的请求一律回 403，这条不是可选的。
            conn.setRequestProperty("User-Agent", "XiaoAiPlug-UpdateChecker")
            conn.setRequestProperty("Accept", "application/vnd.github+json")

            val code = conn.responseCode
            if (code !in 200..299) {
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw ApiError(code, body)
            }
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            // 一次性请求，没有下一发要复用连接，收干净就行
            conn.disconnect()
        }
    }

    private fun parse(json: JSONObject): ReleaseInfo? {
        val tag = json.optStringOrEmpty("tag_name").ifBlank { json.optStringOrEmpty("name") }
        val version = normalizeVersion(tag)
        if (version.isEmpty()) return null

        var apkSize = 0L
        var apkUrl = ""
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                if (!asset.optStringOrEmpty("name").endsWith(".apk", ignoreCase = true)) continue
                apkSize = asset.optLong("size")
                apkUrl = asset.optStringOrEmpty("browser_download_url")
                break
            }
        }

        return ReleaseInfo(
            version = version,
            displayVersion = "V$version",
            notes = parseNotes(json.optStringOrEmpty("body")),
            sizeBytes = apkSize,
            downloadUrl = apkUrl
                .ifBlank { json.optStringOrEmpty("html_url") }
                .ifBlank { LATEST_PAGE }
        )
    }

    /**
     * `optString` 在字段是 JSON null 时返回**字符串 "null"**，不是空串。
     * release 的 `body` 经常就是 null（发版时没写说明），照着 optString 用会在
     * 弹窗里印出一行「null」。所有可空字段一律走这个。
     */
    private fun JSONObject.optStringOrEmpty(key: String): String =
        if (isNull(key)) "" else optString(key)

    private fun describe(t: Throwable): String = when {
        t is ApiError && t.code == 404 -> "仓库还没有发布过版本"
        t is ApiError && t.code == 403 -> "GitHub 接口限流了，过会儿再试"
        t is ApiError -> "GitHub 返回 ${t.code}"
        t is IOException -> "连不上 GitHub"
        else -> "检查失败"
    }

    private class ApiError(val code: Int, val body: String) : IOException("HTTP $code: $body")

    // ---- 下面几个是纯逻辑，internal 是为了 src/test 里能直接测 ----

    /**
     * `v1.0.5` / `V1.0.5-beta.2` / `1.0.5+build7` → `1.0.5`。
     *
     * 预发布后缀直接砍掉而不是参与比较：`/releases/latest` 根本不会返回 prerelease，
     * 留着后缀只会让 `1.0.5-rc1` 被算成比 `1.0.5` 大。
     */
    internal fun normalizeVersion(raw: String): String =
        raw.trim()
            .removePrefix("v").removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
            .filter { it.isDigit() || it == '.' }
            .trim('.')

    /** 逐段比数字。段数不等时缺的段按 0 算，于是 `1.1` == `1.1.0`。 */
    internal fun compareVersion(a: String, b: String): Int {
        val left = a.split('.')
        val right = b.split('.')
        for (i in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrNull(i)?.toIntOrNull() ?: 0
            val y = right.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /**
     * release body（markdown）→ 更新日志的行。
     *
     * 保留**分组标题**（`#`/`##`/`###` → [NoteKind.SECTION]）和**列表缩进**（子项 [ReleaseNote.indent] 更大），
     * 这两样是 changelog 的骨架，拍平了会把「新增 N 个工具」和它下面的子项混成同一层。
     * 引用、分割线丢掉；代码块**连同围栏里的内容**整段跳过。每行剥掉列表符号和加粗/行内代码标记。
     */
    internal fun parseNotes(body: String): List<ReleaseNote> {
        val notes = mutableListOf<ReleaseNote>()
        var inFence = false
        for (raw in body.lineSequence()) {
            // 只 trimEnd：行首空格是缩进信号，得留着算层级
            val line = raw.trimEnd()
            val content = line.trimStart()

            if (content.startsWith("```")) {
                // 围栏本身不产出，只翻转「是否在代码块内」——里面的行随之整段跳过
                inFence = !inFence
                continue
            }
            if (inFence) continue
            if (content.isEmpty() || content.startsWith(">") ||
                content.startsWith("---") || content.startsWith("***")
            ) continue

            if (content.startsWith("#")) {
                val text = content.trimStart('#', ' ').replace(MARKDOWN_NOISE, "").trim()
                if (text.isNotEmpty()) notes += ReleaseNote(text, NoteKind.SECTION)
            } else {
                // 前导空白宽度定层级：markdown 惯例 2 空格一级，tab 记 4
                val leading = line.takeWhile { it == ' ' || it == '\t' }
                    .sumOf { if (it == '\t') 4 else 1 }
                val text = content.trimStart('-', '*', '+', '•', ' ')
                    .replace(MARKDOWN_NOISE, "")
                    .trim()
                if (text.isNotEmpty()) {
                    notes += ReleaseNote(text, NoteKind.ITEM, (leading / 2).coerceIn(0, 2))
                }
            }
            if (notes.size >= MAX_NOTES) break
        }
        return notes
    }
}

/** `48.2 MB` / `48 MB`（上了两位数就不给小数了，多出来的精度只是噪音）/ `512 KB`。 */
internal fun formatSize(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024.0
    return when {
        mb >= 10 -> String.format(Locale.US, "%.0f MB", mb)
        mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }
}
