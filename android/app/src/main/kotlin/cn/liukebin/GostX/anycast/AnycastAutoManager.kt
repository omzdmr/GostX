package cn.liukebin.gostx.anycast

import android.content.Context
import cn.liukebin.gostx.data.ConfigRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AnycastAutoManager(context: Context) {
    private val appContext = context.applicationContext
    private val store = AnycastCredentialStore(appContext)
    private val lock = Mutex()
    private var session: AnycastSession? = null
    private var nodes: List<AnycastNode> = emptyList()

    fun hasCredentials(): Boolean = store.load() != null
    fun savedDeviceUid(): String? = store.savedDeviceUid()
    fun saveDeviceUid(value: String) = store.saveDeviceUid(value)
    fun saveCredentials(email: String, password: String) = store.save(email, password)
    fun clearCredentials() = store.clear()
    fun selectedNodeId(): String? = store.selectedNode()
    fun selectNode(idName: String) = store.saveSelectedNode(idName)

    suspend fun refreshNodes(forceLogin: Boolean = false): List<AnycastNode> = lock.withLock {
        val credentials = store.load() ?: error("Anycast credentials are not configured")
        val client = AnycastApiClient(store.deviceUid())
        if (forceLogin || session == null) session = client.login(credentials.email, credentials.password)
        val current = session ?: error("Anycast session missing")
        nodes = try {
            client.listNodes(current)
        } catch (_: Throwable) {
            session = client.login(credentials.email, credentials.password)
            client.listNodes(session!!)
        }
        nodes
    }

    suspend fun freshYaml(nodeId: String? = store.selectedNode(), lanPort: Int = 8080): Pair<AnycastNode, String> = lock.withLock {
        val credentials = store.load() ?: error("Anycast credentials are not configured")
        val client = AnycastApiClient(store.deviceUid())
        if (session == null) session = client.login(credentials.email, credentials.password)
        if (nodes.isEmpty()) nodes = client.listNodes(session!!)
        val node = nodes.firstOrNull { it.idName == nodeId } ?: nodes.firstOrNull() ?: error("Anycast returned no nodes")
        store.saveSelectedNode(node.idName)
        val connection = try {
            client.connect(session!!, node)
        } catch (_: Throwable) {
            session = client.login(credentials.email, credentials.password)
            nodes = client.listNodes(session!!)
            client.connect(session!!, nodes.firstOrNull { it.idName == node.idName } ?: node)
        }
        node to AnycastConfigBuilder.build(connection, lanPort)
    }

    suspend fun refreshActiveProfile(repo: ConfigRepository, lanPort: Int = 8080): AnycastNode {
        val (node, yaml) = freshYaml(lanPort = lanPort)
        repo.upsertProfile(AnycastConfigBuilder.PROFILE_ID, AnycastConfigBuilder.PROFILE_NAME, yaml)
        repo.setActiveProfile(AnycastConfigBuilder.PROFILE_ID)
        return node
    }
}
