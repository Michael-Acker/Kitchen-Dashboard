package android.graphics

/**
 * Minimal test-only stub of android.graphics.Color for JVM unit tests.
 * Supports #rrggbb and #aarrggbb, matching Android's parseColor semantics
 * for those formats. Never packaged into the app.
 */
object Color {
    @JvmStatic
    fun parseColor(colorString: String): Int {
        var s = colorString
        require(s.startsWith("#")) { "Unknown color: $colorString" }
        s = s.substring(1)
        val a: Long
        val r: Long
        val g: Long
        val b: Long
        when (s.length) {
            6 -> {
                a = 0xFF
                r = s.substring(0, 2).toLong(16)
                g = s.substring(2, 4).toLong(16)
                b = s.substring(4, 6).toLong(16)
            }
            8 -> {
                a = s.substring(0, 2).toLong(16)
                r = s.substring(2, 4).toLong(16)
                g = s.substring(4, 6).toLong(16)
                b = s.substring(6, 8).toLong(16)
            }
            else -> throw IllegalArgumentException("Unknown color: $colorString")
        }
        return ((a shl 24) or (r shl 16) or (g shl 8) or b).toInt()
    }
}
