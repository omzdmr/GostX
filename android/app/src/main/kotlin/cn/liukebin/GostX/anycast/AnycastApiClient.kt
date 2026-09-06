package cn.liukebin.gostx.anycast

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class AnycastApiClient(
    private val deviceUid: String,
    private val deviceName: String = Build.MODEL.ifBlank { "Android" },
) {
    companion object {
        private const val LIST_URL = "https://list-cn-1304018649.cos.accelerate.myqcloud.com/list.txt"
        private const val LIST_KEY = "ac59075b964b0715"
        private val FALLBACK_APIS = listOf("https://api.emuvz.com", "https://api.8a5da52.com")
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> =
                AnycastDns.resolve(hostname)
        })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val baseHeaders = mapOf(
        "AppPlatform" to "windows",
        "AppVersion" to "1.0",
        "AppBuild" to "47",
        "AppLocale" to "en_US",
        "Accept" to "application/json",
    )

    suspend fun login(email: String, password: String): AnycastSession = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (base in resolveApiBases()) {
            val bodies = listOf(
                JSONObject().apply {
                    put("platform", "windows")
                    put("deviceUid", deviceUid)
                    put("email", email)
                    put("password", password)
                    put("code", "")
                },
                JSONObject().apply {
                    put("platform", "windows")
                    put("deviceUid", deviceUid)
                    put("email", email)
                    put("password", password)
                    put("code", JSONObject.NULL)
                },
                JSONObject().apply {
                    put("platform", "windows-gui")
                    put("deviceUid", deviceUid)
                    put("email", email)
                    put("password", password)
                    put("code", "")
                },
                JSONObject().apply { put("email", email); put("password", password) },
                JSONObject().apply { put("username", email); put("password", password) },
            )
            for (body in bodies) {
                try {
                    val json = post("$base/login", body, baseHeaders) as? JSONObject ?: continue
                    val token = AnycastJson.str(AnycastJson.find(json, "access_token", "accessToken", "token"))
                    if (!token.isNullOrBlank()) {
                        return@withContext AnycastSession(
                            apiBase = base,
                            accessToken = token,
                            accountUid = AnycastJson.str(AnycastJson.find(json, "uid")),
                            accountType = AnycastJson.str(AnycastJson.find(json, "account_type", "accountType")),
                        )
                    }
                    val code = AnycastJson.str(AnycastJson.find(json, "code")).orEmpty()
                    val message = AnycastJson.str(AnycastJson.find(json, "message", "msg", "error")).orEmpty()
                    lastError = IllegalStateException(
                        listOf("No access token", code, message)
                            .filter { it.isNotBlank() }
                            .joinToString(": ")
                    )
                } catch (t: Throwable) { lastError = t }
            }
        }
        val detail = lastError?.message?.take(500).orEmpty()
        throw IllegalStateException(
            if (detail.isBlank()) "Login failed" else "Login failed: $detail",
            lastError
        )
    }

    suspend fun listNodes(session: AnycastSession): List<AnycastNode> = withContext(Dispatchers.IO) {
        val auth = baseHeaders + ("Authorization" to "Bearer ${session.accessToken}")
        val response = try {
            get("${session.apiBase}/nodes/list_anycast", auth)
        } catch (_: Throwable) {
            post(
                "${session.apiBase}/nodes/list_anycast",
                JSONObject().put("accessToken", session.accessToken),
                baseHeaders,
            )
        }
        val array = when (response) {
            is JSONArray -> response
            is JSONObject -> AnycastJson.asArray(AnycastJson.find(response, "nodes", "nodeList", "list"))
            else -> null
        } ?: error("Anycast node list not found")

        buildList {
            for (i in 0 until array.length()) {
                val n = array.optJSONObject(i) ?: continue
                val id = AnycastJson.str(AnycastJson.direct(n, "id_name", "idName", "IDName", "nodeIDName")) ?: continue
                add(
                    AnycastNode(
                        idName = id,
                        name = AnycastJson.str(AnycastJson.direct(n, "name", "Name")) ?: id,
                        country = AnycastJson.str(AnycastJson.direct(n, "country", "Country")) ?: "",
                        region = AnycastJson.str(AnycastJson.direct(n, "region", "Region")) ?: "",
                        nodeType = AnycastJson.str(AnycastJson.direct(n, "node_type", "nodeType", "NodeType")),
                    )
                )
            }
        }
    }

    suspend fun connect(session: AnycastSession, node: AnycastNode): AnycastConnection = withContext(Dispatchers.IO) {
        val auth = baseHeaders + ("Authorization" to "Bearer ${session.accessToken}")
        var last: JSONObject? = null
        for (useHn in listOf(false, true)) {
            val body = JSONObject().apply {
                put("device_uid", deviceUid)
                put("device_name", deviceName)
                put("node_id_name", node.idName)
                put("use_hn_host", useHn)
            }
            val response = post("${session.apiBase}/account/connect", body, auth) as? JSONObject ?: continue
            last = response
            val server = AnycastJson.asObject(AnycastJson.find(response, "server_node", "serverNode", "ServerNode"))
            val proxyToken = AnycastJson.str(AnycastJson.find(response, "proxy_token", "proxyToken", "ProxyToken"))
            if (server != null && !proxyToken.isNullOrBlank()) {
                val host = AnycastJson.str(AnycastJson.direct(server, "host", "Host")) ?: error("Anycast server host missing")
                val port = AnycastJson.int(AnycastJson.direct(server, "port", "Port")) ?: error("Anycast server port missing")
                val transport = AnycastJson.str(AnycastJson.direct(server, "transport", "Transport")) ?: "ws"
                val protocol = AnycastJson.str(AnycastJson.direct(server, "protocol", "Protocol")) ?: "socks5"
                val path = AnycastJson.str(AnycastJson.direct(server, "path", "Path")) ?: "/gost"
                var username = AnycastJson.str(AnycastJson.find(server, "username", "user_name", "userName", "node_username", "nodeUsername", "proxy_username", "proxyUsername"))
                    ?: AnycastJson.str(AnycastJson.find(response, "username", "user_name", "userName", "node_username", "nodeUsername", "proxy_username", "proxyUsername"))
                username = normalizePaidUsername(username, response, session)
                return@withContext AnycastConnection(
                    nodeId = node.idName,
                    host = host,
                    port = port,
                    protocol = protocol,
                    transport = transport.lowercase(),
                    path = path,
                    username = username,
                    proxyToken = proxyToken,
                )
            }
        }
        throw IllegalStateException("Anycast connect failed: ${last?.optString("code").orEmpty()} ${last?.optString("message").orEmpty()}".trim())
    }

    private fun normalizePaidUsername(raw: String?, response: JSONObject, session: AnycastSession): String {
        val candidate = raw?.trim().orEmpty()
        if (candidate.matches(Regex("^\\d+$"))) return "pro:u$candidate"
        if (candidate.matches(Regex("^u\\d+$"))) return "pro:$candidate"
        if (candidate.startsWith("pro:") || candidate.startsWith("trial:")) return candidate
        var uid = AnycastJson.str(AnycastJson.find(response, "uid")) ?: session.accountUid.orEmpty()
        if (uid.matches(Regex("^\\d+$"))) uid = "u$uid"
        if (uid.isNotBlank()) return "pro:$uid"
        error("Anycast proxy username missing")
    }

    private fun resolveApiBases(): List<String> {
        val out = linkedSetOf<String>()
        runCatching {
            val decoded = decryptList(requestText(LIST_URL, "GET", null, emptyMap()))
            Regex("https?://[A-Za-z0-9.\\-_:]+(?:/[A-Za-z0-9.\\-_/]*)?")
                .findAll(decoded).forEach { out += it.value.trimEnd('/') }
            if (out.isEmpty()) {
                Regex("(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}(?::\\d+)?")
                    .findAll(decoded).forEach { out += "https://${it.value.trimEnd('/')}" }
            }
        }
        out += FALLBACK_APIS
        return out.toList()
    }

    private fun decryptList(input: String): String {
        val trimmed = input.trim()
        if (trimmed.contains("http://") || trimmed.contains("https://")) return trimmed
        val cipherText = Base64.getDecoder().decode(trimmed)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(LIST_KEY.toByteArray(StandardCharsets.UTF_8), "AES"))
        return String(cipher.doFinal(cipherText), StandardCharsets.UTF_8).trim('\u0000').trim()
    }

    private fun get(url: String, headers: Map<String, String>): Any = parseJson(requestText(url, "GET", null, headers))
    private fun post(url: String, body: JSONObject, headers: Map<String, String>): Any =
        parseJson(requestText(url, "POST", body.toString(), headers + ("Content-Type" to "application/json")))

    private fun parseJson(text: String): Any {
        val t = text.trim()
        return if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
    }

    private fun requestText(url: String, method: String, body: String?, headers: Map<String, String>): String {
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        if (method == "POST") rb.post((body ?: "{}").toRequestBody(JSON)) else rb.get()
        client.newCall(rb.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}: $text")
            return text
        }
    }
}
