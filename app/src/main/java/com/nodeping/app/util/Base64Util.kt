package com.nodeping.app.util

import android.util.Base64

/**
 * Base64 helpers that tolerate the real-world messiness of proxy subscriptions:
 * both the standard (`+/`) and URL-safe (`-_`) alphabets, and missing `=` padding.
 */
object Base64Util {

    /** Decode a possibly URL-safe, possibly unpadded Base64 string. Returns empty on failure. */
    fun decode(input: String): ByteArray {
        val cleaned = input
            .trim()
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
            .replace('-', '+')
            .replace('_', '/')
        if (cleaned.isEmpty()) return ByteArray(0)

        val padded = when (cleaned.length % 4) {
            2 -> "$cleaned=="
            3 -> "$cleaned="
            1 -> return ByteArray(0) // not a valid Base64 length
            else -> cleaned
        }
        return try {
            Base64.decode(padded, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            ByteArray(0)
        }
    }

    /** Decode to a UTF-8 string. Returns empty string on failure. */
    fun decodeToString(input: String): String = String(decode(input), Charsets.UTF_8)

    /** True if the whole [text] looks like a single Base64 blob (a base64-wrapped subscription). */
    fun looksLikeBase64(text: String): Boolean {
        val t = text.trim()
        if (t.length < 8) return false
        // A base64 subscription has no scheme markers and only base64 chars/whitespace.
        if (t.contains("://")) return false
        return t.all { c ->
            c.isLetterOrDigit() || c == '+' || c == '/' || c == '-' || c == '_' ||
                c == '=' || c == '\n' || c == '\r' || c == ' '
        }
    }
}
