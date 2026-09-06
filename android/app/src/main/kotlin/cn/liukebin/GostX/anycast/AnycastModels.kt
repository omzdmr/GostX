package cn.liukebin.gostx.anycast

data class AnycastSession(
    val apiBase: String,
    val accessToken: String,
    val accountUid: String? = null,
    val accountType: String? = null,
)

data class AnycastNode(
    val idName: String,
    val name: String,
    val country: String,
    val region: String,
    val nodeType: String? = null,
)

data class AnycastConnection(
    val nodeId: String,
    val host: String,
    val port: Int,
    val protocol: String,
    val transport: String,
    val path: String,
    val username: String,
    val proxyToken: String,
)
