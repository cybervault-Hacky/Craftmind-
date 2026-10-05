package com.craftmind.app.data.reference

import com.craftmind.app.domain.reference.PublicVideoReferenceUrlPolicy
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient

/** Video transport is direct HTTPS to one fixed public host, with no proxy app endpoint or redirects. */
object PublicVideoHttpClient {
    fun create(): OkHttpClient = OkHttpClient.Builder()
        .dns(AllowlistedPublicVideoDns())
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    internal fun restrictedClient(client: OkHttpClient): OkHttpClient = client.newBuilder()
        .dns(AllowlistedPublicVideoDns(client.dns))
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}

/** Every new DNS result is checked; mixed public/private answers fail closed. */
internal class AllowlistedPublicVideoDns(
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        if (hostname.lowercase(Locale.ROOT) != PublicVideoReferenceUrlPolicy.ALLOWED_HOST) {
            throw UnknownHostException("Unsupported public video host")
        }
        val addresses = try {
            delegate.lookup(hostname)
        } catch (error: UnsafePublicVideoAddressException) {
            throw error
        }
        if (addresses.isEmpty()) throw UnknownHostException("Public video host did not resolve")
        if (addresses.any { !isPublicUnicastAddress(it) }) throw UnsafePublicVideoAddressException()
        return addresses
    }
}

internal class UnsafePublicVideoAddressException : UnknownHostException("Public video destination is not allowed")

/** Excludes local, private, link-local, carrier-grade NAT, multicast, and reserved address ranges. */
internal fun isPublicUnicastAddress(address: InetAddress): Boolean = when (address) {
    is Inet4Address -> {
        val b = address.address.map { it.toInt() and 0xff }
        val first = b[0]
        val second = b[1]
        val third = b[2]
        when {
            first == 0 || first == 10 || first == 127 || first >= 224 -> false
            first == 100 && second in 64..127 -> false
            first == 169 && second == 254 -> false
            first == 172 && second in 16..31 -> false
            first == 192 && second == 168 -> false
            first == 192 && second == 0 -> false
            first == 192 && second == 88 && third == 99 -> false
            first == 198 && second in 18..19 -> false
            first == 198 && second == 51 && third == 100 -> false
            first == 203 && second == 0 && third == 113 -> false
            else -> true
        }
    }
    is Inet6Address -> {
        val b = address.address.map { it.toInt() and 0xff }
        val globalUnicast = b[0] and 0xe0 == 0x20 // 2000::/3
        val documentation = b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8
        val transitionOrSpecial = b[0] == 0x20 && b[1] == 0x02 ||
            (b[0] == 0x20 && b[1] == 0x01 && b[2] <= 0x01)
        val ipv4Mapped = b.take(10).all { it == 0 } && b[10] == 0xff && b[11] == 0xff
        globalUnicast && !documentation && !transitionOrSpecial && !ipv4Mapped &&
            !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
            !address.isSiteLocalAddress && !address.isMulticastAddress
    }
    else -> false
}
