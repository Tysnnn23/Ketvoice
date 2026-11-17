package com.ketvoice.app.together

/**
 * A small QR code encoder (ISO/IEC 18004, byte mode, error correction M, versions 1 to 10) for the link of a
 * Ketogether session, so that friends can scan the host's screen with their camera. Written for Ketvoice after
 * the public description of the standard (the layout follows Project Nayuki's well-known reference); no library
 * is added for it. Scanning inside the app is not needed: the phone's camera opens the link.
 */
class QrCode private constructor(val size: Int, private val dark: Array<BooleanArray>) {
    operator fun get(x: Int, y: Int): Boolean = dark[y][x]

    companion object {
        const val MAX_VERSION = 10
        // Error correction M, versions 1..10.
        private val eccPerBlock = intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val blocks = intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
        private const val FORMAT_M = 0

        /** Null when [text] is too long for version 10. [mask] forces a mask (tests); otherwise the best is chosen. */
        fun encode(text: String, mask: Int = -1): QrCode? {
            val data = text.toByteArray(Charsets.UTF_8)
            val version = (1..MAX_VERSION).firstOrNull { v -> 4 + countBits(v) + data.size * 8 <= dataCodewords(v) * 8 } ?: return null
            val capacity = dataCodewords(version) * 8
            val bits = ArrayList<Boolean>(capacity)
            fun put(value: Int, length: Int) { for (i in length - 1 downTo 0) bits += (value ushr i) and 1 != 0 }
            put(0b0100, 4); put(data.size, countBits(version))
            data.forEach { put(it.toInt() and 0xFF, 8) }
            put(0, minOf(4, capacity - bits.size))
            put(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity) { put(pad, 8); pad = pad xor 0xEC xor 0x11 }
            val codewords = ByteArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, b -> (acc shl 1) or (if (bits[i * 8 + b]) 1 else 0) }.toByte() }
            return build(version, interleave(version, codewords), mask)
        }

        private fun countBits(version: Int) = if (version <= 9) 8 else 16

        private fun rawModules(version: Int): Int {
            var result = (16 * version + 128) * version + 64
            if (version >= 2) {
                val align = version / 7 + 2
                result -= (25 * align - 10) * align - 55
                if (version >= 7) result -= 36
            }
            return result
        }

        private fun dataCodewords(version: Int) = rawModules(version) / 8 - eccPerBlock[version - 1] * blocks[version - 1]

        private fun interleave(version: Int, data: ByteArray): ByteArray {
            val numBlocks = blocks[version - 1]
            val eccLen = eccPerBlock[version - 1]
            val raw = rawModules(version) / 8
            val short = numBlocks - raw % numBlocks
            val shortLen = raw / numBlocks
            val divisor = rsDivisor(eccLen)
            val all = ArrayList<ByteArray>()
            var k = 0
            for (i in 0 until numBlocks) {
                val len = shortLen - eccLen + if (i < short) 0 else 1
                val dat = data.copyOfRange(k, k + len)
                k += len
                val ecc = rsRemainder(dat, divisor)
                val block = ByteArray(shortLen + 1)
                dat.copyInto(block, 0)
                // Short blocks keep a hole where the long ones have one more data byte.
                ecc.copyInto(block, block.size - eccLen)
                all += block
            }
            val out = ByteArray(raw)
            var n = 0
            for (i in 0..shortLen) for (j in 0 until numBlocks) {
                if (i != shortLen - eccLen || j >= short) out[n++] = all[j][i]
            }
            return out
        }

        private fun multiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z and 0xFF
        }

        private fun rsDivisor(degree: Int): IntArray {
            val result = IntArray(degree).also { it[degree - 1] = 1 }
            var root = 1
            repeat(degree) {
                for (j in result.indices) {
                    result[j] = multiply(result[j], root)
                    if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
                }
                root = multiply(root, 0x02)
            }
            return result
        }

        private fun rsRemainder(data: ByteArray, divisor: IntArray): ByteArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() and 0xFF) xor result[0]
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor multiply(divisor[i], factor)
            }
            return ByteArray(result.size) { result[it].toByte() }
        }

        private fun build(version: Int, codewords: ByteArray, forcedMask: Int): QrCode {
            val size = version * 4 + 17
            val modules = Array(size) { BooleanArray(size) }
            val function = Array(size) { BooleanArray(size) }
            fun set(x: Int, y: Int, value: Boolean) { modules[y][x] = value; function[y][x] = true }
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            for ((cx, cy) in listOf(3 to 3, size - 4 to 3, 3 to size - 4)) {
                for (dy in -4..4) for (dx in -4..4) {
                    val x = cx + dx; val y = cy + dy
                    if (x in 0 until size && y in 0 until size) {
                        val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                        set(x, y, d != 2 && d != 4)
                    }
                }
            }
            val align = alignment(version, size)
            for (i in align.indices) for (j in align.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == align.lastIndex) || (i == align.lastIndex && j == 0)) continue
                for (dy in -2..2) for (dx in -2..2) set(align[i] + dx, align[j] + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
            }
            format(size, 0, ::set)
            if (version >= 7) {
                var rem = version
                repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
                val bits = (version shl 12) or rem
                for (i in 0 until 18) {
                    val bit = (bits ushr i) and 1 != 0
                    val a = size - 11 + i % 3; val b = i / 3
                    set(a, b, bit); set(b, a, bit)
                }
            }
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!function[y][x] && i < codewords.size * 8) {
                        modules[y][x] = ((codewords[i ushr 3].toInt() ushr (7 - (i and 7))) and 1) != 0
                        i++
                    }
                }
                right -= 2
            }
            fun masked(mask: Int): Array<BooleanArray> {
                val out = Array(size) { y -> modules[y].copyOf() }
                for (y in 0 until size) for (x in 0 until size) {
                    if (!function[y][x] && maskBit(mask, x, y)) out[y][x] = !out[y][x]
                }
                format(size, mask) { x, y, v -> out[y][x] = v }
                return out
            }
            val chosen = if (forcedMask in 0..7) masked(forcedMask) else (0..7).map { masked(it) }.minBy(::penalty)
            return QrCode(size, chosen)
        }

        private fun alignment(version: Int, size: Int): IntArray {
            if (version == 1) return IntArray(0)
            val num = version / 7 + 2
            val step = (version * 4 + num * 2 + 1) / (num * 2 - 2) * 2
            val result = IntArray(num)
            result[0] = 6
            var pos = size - 7
            for (i in num - 1 downTo 1) { result[i] = pos; pos -= step }
            return result
        }

        private fun format(size: Int, mask: Int, set: (Int, Int, Boolean) -> Unit) {
            val data = (FORMAT_M shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
            for (i in 9 until 15) set(14 - i, 8, bit(i))
            for (i in 0 until 8) set(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true)
        }

        private fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> (x / 3 + y / 2) % 2 == 0
            5 -> x * y % 2 + x * y % 3 == 0
            6 -> (x * y % 2 + x * y % 3) % 2 == 0
            else -> ((x + y) % 2 + x * y % 3) % 2 == 0
        }

        /** The standard's penalty: long runs, 2×2 blocks, finder look-alikes and the balance of dark modules. */
        private fun penalty(m: Array<BooleanArray>): Int {
            val size = m.size
            var score = 0
            fun line(get: (Int) -> Boolean) {
                var run = 1
                for (i in 1 until size) {
                    if (get(i) == get(i - 1)) run++ else { if (run >= 5) score += run - 2; run = 1 }
                }
                if (run >= 5) score += run - 2
                val text = (0 until size).joinToString("") { if (get(it)) "1" else "0" }
                score += 40 * (Regex("(?=10111010000)").findAll(text).count() + Regex("(?=00001011101)").findAll(text).count())
            }
            for (y in 0 until size) line { m[y][it] }
            for (x in 0 until size) line { m[it][x] }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = m[y][x]
                if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) score += 3
            }
            val darkCount = m.sumOf { row -> row.count { it } }
            val total = size * size
            score += (kotlin.math.abs(darkCount * 20 - total * 10) + total - 1) / total * 10 - 10
            return score
        }
    }
}
