package io.wrtpilot.core.domain

import java.io.BufferedReader

/**
 * MAC vendor lookup from the bundled IEEE OUI table ("AABBCC<TAB>Vendor").
 * Prefixes are stored as ints in a sorted array to keep memory small.
 */
class OuiTable private constructor(
    private val prefixes: IntArray,
    private val vendors: Array<String>,
) {
    val size: Int get() = prefixes.size

    /** Vendor for a MAC, or null (unknown, or randomised/private address). */
    fun lookup(mac: String): String? {
        if (isRandomized(mac)) return null
        val p = prefixOf(mac) ?: return null
        val i = prefixes.binarySearch(p)
        return if (i >= 0) vendors[i] else null
    }

    companion object {
        fun parse(reader: BufferedReader): OuiTable {
            val keys = ArrayList<Int>(48_000)
            val names = ArrayList<String>(48_000)
            val intern = HashMap<String, String>()
            reader.forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab == 6) {
                    val key = line.substring(0, 6).toIntOrNull(16)
                    if (key != null) {
                        val name = line.substring(7).trim()
                        keys += key
                        names += intern.getOrPut(name) { name }
                    }
                }
            }
            val order = keys.indices.sortedBy { keys[it] }
            return OuiTable(
                IntArray(order.size) { keys[order[it]] },
                Array(order.size) { names[order[it]] },
            )
        }

        fun empty(): OuiTable = OuiTable(IntArray(0), emptyArray())

        /** Locally administered bit: phones use random MACs per network. */
        fun isRandomized(mac: String): Boolean {
            val first = mac.take(2).toIntOrNull(16) ?: return false
            return first and 0x02 != 0
        }

        private fun prefixOf(mac: String): Int? {
            val hex = mac.filter { it.isLetterOrDigit() }
            if (hex.length < 6) return null
            return hex.substring(0, 6).toIntOrNull(16)
        }
    }
}
