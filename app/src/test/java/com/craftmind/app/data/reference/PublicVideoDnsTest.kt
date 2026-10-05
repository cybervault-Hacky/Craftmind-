package com.craftmind.app.data.reference

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicVideoDnsTest {
    @Test
    fun onlyLooksUpTheExactAllowlistedHostname() {
        val dns = AllowlistedPublicVideoDns(staticDns("8.8.8.8"))

        assertEquals(listOf(InetAddress.getByName("8.8.8.8")), dns.lookup("raw.githubusercontent.com"))
        assertEquals(
            true,
            runCatching { dns.lookup("attacker.example") }.exceptionOrNull() is UnknownHostException,
        )
    }

    @Test
    fun rejectsAnyPrivateLoopbackOrMixedDnsAnswer() {
        val mixed = AllowlistedPublicVideoDns(staticDns("8.8.8.8", "127.0.0.1"))
        val loopback = AllowlistedPublicVideoDns(staticDns("127.0.0.1"))
        val privateRange = AllowlistedPublicVideoDns(staticDns("10.1.2.3"))
        val linkLocal = AllowlistedPublicVideoDns(staticDns("169.254.1.1"))

        listOf(mixed, loopback, privateRange, linkLocal).forEach { dns ->
            assertTrue(runCatching { dns.lookup("raw.githubusercontent.com") }.exceptionOrNull() is UnsafePublicVideoAddressException)
        }
    }

    @Test
    fun blocksReservedAndNonGlobalIpv6AddressesButAcceptsPublicUnicast() {
        assertFalse(isPublicUnicastAddress(InetAddress.getByName("0.0.0.0")))
        assertFalse(isPublicUnicastAddress(InetAddress.getByName("192.0.2.10")))
        assertFalse(isPublicUnicastAddress(InetAddress.getByName("224.0.0.1")))
        assertFalse(isPublicUnicastAddress(InetAddress.getByName("::1")))
        assertFalse(isPublicUnicastAddress(InetAddress.getByName("fe80::1")))
        assertFalse(isPublicUnicastAddress(InetAddress.getByName("2001:db8::1")))
        assertTrue(isPublicUnicastAddress(InetAddress.getByName("8.8.8.8")))
        assertTrue(isPublicUnicastAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    private fun staticDns(vararg addresses: String) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = addresses.map { InetAddress.getByName(it) }
    }
}
