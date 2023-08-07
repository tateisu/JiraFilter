package util

import java.text.SimpleDateFormat
import java.util.*

class LogTag(private val tag: String) {
    companion object {
        private val timeFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN)
            .apply { timeZone = TimeZone.getTimeZone("Asia/Tokyo") }

        private val nowStr get() = timeFormat.format(Date())

        var verbose = false
    }

    fun e(ex: Throwable, s: String) {
        println("$nowStr E/$tag $s")
        ex.printStackTrace()
    }

    fun w(ex: Throwable, s: String) {
        println("$nowStr W/$tag $s")
        ex.printStackTrace()
    }

    fun e(s: String) {
        println("$nowStr E/$tag $s")
    }

    fun w(s: String) {
        println("$nowStr W/$tag $s")
    }

    fun i(s: String) {
        println("$nowStr I/$tag $s")
    }

    fun v(s: String) {
        if(verbose) println("$nowStr V/$tag $s")
    }

    inline fun v(stringMaker: () -> String) {
        if (verbose) v(stringMaker())
    }
}
