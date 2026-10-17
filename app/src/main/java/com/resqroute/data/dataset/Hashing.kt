package com.resqroute.data.dataset

import java.security.MessageDigest
import java.util.Locale

/** SHA-256 of a byte array as a lowercase hex string. */
fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val hex = StringBuilder(digest.size * 2)
    for (byte in digest) {
        val value = byte.toInt() and 0xFF
        hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
    }
    return hex.toString().lowercase(Locale.US)
}

private val HEX = "0123456789abcdef".toCharArray()
