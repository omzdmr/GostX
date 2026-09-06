package cn.liukebin.gostx.anycast

object AnycastConfigBuilder {
    const val PROFILE_ID = "anycast-auto"
    const val PROFILE_NAME = "Anycast Auto"

    fun build(connection: AnycastConnection, lanPort: Int = 8080): String {
        val transport = connection.transport.ifBlank { "ws" }
        val path = connection.path.ifBlank { "/gost" }
        return """
services:
  - name: vpn
    handler:
      type: tungo
      chain: anycast

  - name: lan-http-proxy
    addr: "0.0.0.0:$lanPort"
    handler:
      type: http
      chain: anycast
    listener:
      type: tcp

chains:
  - name: anycast
    hops:
      - name: anycast-hop
        nodes:
          - name: anycast-server
            addr: "${connection.host}:${connection.port}"
            connector:
              type: socks5
              auth:
                username: "${yaml(connection.username)}"
                password: "${yaml(connection.proxyToken)}"
              metadata:
                notls: true
            dialer:
              type: $transport
              metadata:
                path: "${yaml(path)}"
                host: "${yaml(connection.host)}"
""".trimIndent()
    }

    private fun yaml(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}
