package com.ketvoice.app.handoff

import java.net.Inet4Address
import java.net.InetAddress

data class LanBoundary(val address: InetAddress, val prefixLength: Int) {
    init { require(address is Inet4Address && prefixLength in 8..32) }
    fun allows(peer: InetAddress): Boolean {
        if (address.isLoopbackAddress) return peer.isLoopbackAddress
        if (peer !is Inet4Address || !address.isSiteLocalAddress || !peer.isSiteLocalAddress) return false
        val a = address.address; val b = peer.address
        return (0 until prefixLength).all { bit ->
            val mask = 1 shl (7 - bit % 8)
            (a[bit / 8].toInt() and mask) == (b[bit / 8].toInt() and mask)
        }
    }
}
