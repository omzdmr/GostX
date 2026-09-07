package cn.liukebin.gostx.lantern

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import lantern.io.libbox.ConnectionOwner
import lantern.io.libbox.InterfaceUpdateListener
import lantern.io.libbox.Libbox
import lantern.io.libbox.LocalDNSTransport
import lantern.io.libbox.NetworkInterfaceIterator
import lantern.io.libbox.Notification
import lantern.io.libbox.StringIterator
import lantern.io.libbox.TunOptions
import lantern.io.libbox.WIFIState
import lantern.io.utils.PlatformInterface
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface

/**
 * Minimal Android platform adapter for Radiance SOCKS mode.
 * No TUN is opened here; GostX exposes a LAN HTTP proxy instead.
 */
class RadiancePlatform(private val context: Context) : PlatformInterface {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = false

    override fun autoDetectInterfaceControl(fd: Int) {
        // SOCKS mode does not install a system VPN route, so no protect(fd) is needed.
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        val owner = ConnectionOwner()
        val uid = runCatching {
            connectivity.getConnectionOwnerUid(
                ipProtocol,
                InetSocketAddress(sourceAddress, sourcePort),
                InetSocketAddress(destinationAddress, destinationPort)
            )
        }.getOrDefault(Process.INVALID_UID)
        owner.userId = if (uid == Process.INVALID_UID) 0 else uid
        owner.userName = ""
        owner.setAndroidPackageNames(EmptyStringIterator())
        return owner
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        // Radiance can query getInterfaces() on demand. No system VPN means the
        // physical default route remains directly visible.
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val result = mutableListOf<lantern.io.libbox.NetworkInterface>()
        val javaIfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
            .getOrDefault(emptyList())

        for (iface in javaIfaces) {
            if (!runCatching { iface.isUp }.getOrDefault(false)) continue
            if (runCatching { iface.isLoopback }.getOrDefault(false)) continue
            val box = lantern.io.libbox.NetworkInterface()
            box.name = iface.name ?: continue
            box.index = iface.index
            box.mtu = runCatching { iface.mtu }.getOrDefault(1500)
            box.type = when {
                box.name.startsWith("wlan") || box.name.startsWith("wifi") -> Libbox.InterfaceTypeWIFI
                box.name.startsWith("rmnet") || box.name.startsWith("ccmni") || box.name.startsWith("pdp") -> Libbox.InterfaceTypeCellular
                box.name.startsWith("eth") -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            box.addresses = StringArray(iface.interfaceAddresses.mapNotNull { it.toPrefixOrNull() }.iterator())
            box.dnsServer = EmptyStringIterator()
            box.flags = 0
            box.metered = false
            result.add(box)
        }
        return InterfaceArray(result.iterator())
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() {
    }

    override fun readWIFIState(): WIFIState? = null

    override fun openTun(tunOptions: TunOptions?): Int {
        throw IllegalStateException("Radiance TUN requested while GostX SOCKS mode is enabled")
    }

    override fun sendNotification(notification: Notification?) {
    }

    override fun systemCertificates(): StringIterator = EmptyStringIterator()

    override fun restartService() {
        // The outer Android service owns lifecycle/retry. Avoid recursive restart
        // callbacks while Radiance is used as an embedded SOCKS backend.
    }

    override fun postServiceClose() {
    }

    private class InterfaceArray(
        private val iterator: Iterator<lantern.io.libbox.NetworkInterface>
    ) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): lantern.io.libbox.NetworkInterface = iterator.next()
    }

    private class StringArray(private val iterator: Iterator<String>) : StringIterator {
        override fun len(): Int = 0
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }

    private class EmptyStringIterator : StringIterator {
        override fun len(): Int = 0
        override fun hasNext(): Boolean = false
        override fun next(): String = ""
    }

    private fun InterfaceAddress.toPrefixOrNull(): String? {
        val a = address ?: return null
        val host = if (a is Inet6Address) {
            runCatching { Inet6Address.getByAddress(a.address).hostAddress }.getOrNull()
        } else a.hostAddress
        return host?.let { "$it/$networkPrefixLength" }
    }
}
