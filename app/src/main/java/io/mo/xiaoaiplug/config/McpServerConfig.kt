package io.mo.xiaoaiplug.config

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * MCP (Model Context Protocol) 远程服务配置项。
 */
data class McpServerConfig(
    val id: String = UUID.randomUUID().toString(),
    /** 显示名称 (如: Github MCP) */
    val name: String,
    /** 传输类型: TRANSPORT_HTTP ("streamable_http") 或 TRANSPORT_SSE ("sse") */
    val transportType: String = TRANSPORT_HTTP,
    /** 端点 URL */
    val url: String,
    /** 自定义请求头 (每行 Key: Value 或 JSON 格式) */
    val headers: String = "",
    /** 是否启用该 MCP 服务 */
    val enabled: Boolean = true
) {
    companion object {
        const val TRANSPORT_HTTP = "streamable_http"
        const val TRANSPORT_SSE = "sse"

        val TRANSPORT_LABELS = listOf("streamable HTTP", "SSE")
        val TRANSPORT_KEYS = listOf(TRANSPORT_HTTP, TRANSPORT_SSE)

        fun transportLabel(key: String): String = when (key) {
            TRANSPORT_SSE -> "SSE"
            else -> "streamable HTTP"
        }

        fun fromJson(obj: JSONObject): McpServerConfig {
            return McpServerConfig(
                id = obj.optString("id", UUID.randomUUID().toString()),
                name = obj.optString("name", ""),
                transportType = obj.optString("transportType", TRANSPORT_HTTP),
                url = obj.optString("url", ""),
                headers = obj.optString("headers", ""),
                enabled = obj.optBoolean("enabled", true)
            )
        }

        fun parseList(jsonStr: String): List<McpServerConfig> {
            if (jsonStr.isBlank()) return emptyList()
            return try {
                val array = JSONArray(jsonStr)
                val list = mutableListOf<McpServerConfig>()
                for (i in 0 until array.length()) {
                    list.add(fromJson(array.getJSONObject(i)))
                }
                list
            } catch (t: Throwable) {
                emptyList()
            }
        }

        fun toJsonArray(list: List<McpServerConfig>): String {
            val array = JSONArray()
            for (item in list) {
                array.put(JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("transportType", item.transportType)
                    put("url", item.url)
                    put("headers", item.headers)
                    put("enabled", item.enabled)
                })
            }
            return array.toString()
        }
    }
}
