package com.Johnny.wcx.utils

import kotlin.random.Random

class UuidV4 private constructor(private val bytes: ByteArray) : Comparable<UuidV4> {

    companion object {
        private const val HEX_CHARS = "0123456789abcdef"

        /**
         * Generates a cryptographically secure-ready pseudo-random UUID (Version 4)
         * conforming to RFC 4122 specifications.
         */
        fun random(): UuidV4 {
            // Generate 16 random bytes (128 bits)
            val bytes = Random.nextBytes(16)

            // Set version to 4: The 4 most significant bits of the 7th byte (index 6) must be 0100
            bytes[6] = (bytes[6].toInt() and 0x0F or 0x40).toByte()

            // Set variant to IETF (RFC 4122): The 2 most significant bits of the 9th byte (index 8) must be 10
            bytes[8] = (bytes[8].toInt() and 0x3F or 0x80).toByte()

            return UuidV4(bytes)
        }

        /**
         * Parses a UUID from its canonical 36-character string representation.
         * Accepts both hyphenated and non-hyphenated strings (case-insensitive).
         */
        fun fromString(uuidString: String): UuidV4 {
            val cleanString = uuidString.replace("-", "").lowercase()
            require(cleanString.length == 32) { "Invalid UUID string length: $uuidString" }

            val bytes = ByteArray(16)
            for (i in 0 until 16) {
                val highChar = cleanString[i * 2]
                val lowChar = cleanString[i * 2 + 1]

                val highNibble = HEX_CHARS.indexOf(highChar)
                val lowNibble = HEX_CHARS.indexOf(lowChar)

                require(highNibble != -1 && lowNibble != -1) {
                    "Invalid hexadecimal character in UUID: $uuidString"
                }

                bytes[i] = ((highNibble shl 4) or lowNibble).toByte()
            }
            return UuidV4(bytes)
        }
    }

    /**
     * Converts the UUID to its canonical 36-character hex-and-dash string representation:
     * xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
     */
    override fun toString(): String {
        val chars = CharArray(36)
        var charIndex = 0
        for (byteIndex in 0 until 16) {
            // Insert hyphens at the correct layout boundaries
            if (byteIndex == 4 || byteIndex == 6 || byteIndex == 8 || byteIndex == 10) {
                chars[charIndex++] = '-'
            }
            val b = bytes[byteIndex].toInt() and 0xFF
            chars[charIndex++] = HEX_CHARS[b ushr 4]
            chars[charIndex++] = HEX_CHARS[b and 0x0F]
        }
        return String(chars)
    }

    /**
     * Compares this UUID with another lexicographically.
     */
    override fun compareTo(other: UuidV4): Int {
        for (i in 0 until 16) {
            val b1 = bytes[i].toInt() and 0xFF
            val b2 = other.bytes[i].toInt() and 0xFF
            if (b1 != b2) {
                return b1.compareTo(b2)
            }
        }
        return 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UuidV4) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        return bytes.contentHashCode()
    }
}
