package util

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

val reFalse = """\A(?:0\z|off|f)""".toRegex(RegexOption.IGNORE_CASE)

fun Int?.notZero() = if (this == null || this == 0) null else this

fun <T : Any?> List<T>?.notEmpty() = if (this.isNullOrEmpty()) null else this
fun <T : Any?> Array<T>?.notEmpty() = if (this.isNullOrEmpty()) null else this
fun <T : Any?> Collection<T>?.notEmpty() = if (this.isNullOrEmpty()) null else this
fun <T : CharSequence?> T.notEmpty() = if (this.isNullOrEmpty()) null else this
fun <T : CharSequence?> T.notBlank() = if (this.isNullOrBlank()) null else this

fun CharSequence.eachCodePoint(block: (Int) -> Unit) {
    val end = length
    var i = 0
    while (i < end) {
        val c1 = get(i++)
        if (Character.isHighSurrogate(c1) && i < length) {
            val c2 = get(i)
            if (Character.isLowSurrogate(c2)) {
                i++
                block(Character.toCodePoint(c1, c2))
                continue
            }
        }
        block(c1.code)
    }
}

// split codepoint to UTF-8 bytes
fun codePointToUtf8(cp: Int, block: (Int) -> Unit) {
    // incorrect codepoint
    if (cp < 0 || cp > 0x10FFFF) codePointToUtf8('?'.code, block)

    if (cp >= 128) {
        if (cp >= 2048) {
            if (cp >= 65536) {
                block(0xF0.or(cp.shr(18)))
                block(0x80.or(cp.shr(12).and(0x3f)))
            } else {
                block(0xE0.or(cp.shr(12)))
            }
            block(0x80.or(cp.shr(6).and(0x3f)))
        } else {
            block(0xC0.or(cp.shr(6)))
        }
        block(0x80.or(cp.and(0x3f)))
    } else {
        block(cp)
    }
}

private const val hexString = "0123456789ABCDEF"

private val encodePercentSkipChars by lazy {
    HashSet<Int>().apply {
        ('0'..'9').forEach { add(it.code) }
        ('A'..'Z').forEach { add(it.code) }
        ('a'..'z').forEach { add(it.code) }
        add('-'.code)
        add('_'.code)
        add('.'.code)
    }
}

fun String.encodePercent(): String =
    StringBuilder(length).also { sb ->
        eachCodePoint { cp ->
            when {
                cp == 0x20 ->
                    sb.append('+')

                encodePercentSkipChars.contains(cp) ->
                    sb.append(cp.toChar())

                else -> codePointToUtf8(cp) { b ->
                    sb.append('%')
                        .append(hexString[b shr 4])
                        .append(hexString[b and 15])
                }
            }
        }
    }.toString()

fun List<Pair<String, Any?>>.encodeQuery() =
    joinToString("&") {
        "${it.first.encodePercent()}=${it.second.toString().encodePercent()}"
    }

val zoneTokyo: ZoneId = ZoneId.of("Asia/Tokyo")

private val reFixTimeOffset = """([+\-]\d{2})(\d{2})\z""".toRegex()

private val formatterIso8601OffsetDateTime = DateTimeFormatterBuilder()
    .parseCaseInsensitive()
    .parseCaseInsensitive()
    .append(DateTimeFormatter.ISO_LOCAL_DATE)
    .appendLiteral('T')
    .appendValue(ChronoField.HOUR_OF_DAY, 2)
    .appendLiteral(':')
    .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
    .optionalStart()
    .appendLiteral(':')
    .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
    .optionalStart()
    .appendFraction(ChronoField.NANO_OF_SECOND, 3, 9, true)
    .parseLenient()
    .appendOffsetId()
    .parseStrict()
    .toFormatter()

private val formatterTime2 = DateTimeFormatterBuilder()
    .parseCaseInsensitive()
    .parseCaseInsensitive()
    .append(DateTimeFormatter.ISO_LOCAL_DATE)
    .appendLiteral(' ')
    .appendValue(ChronoField.HOUR_OF_DAY, 2)
    .appendLiteral(':')
    .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
    .toFormatter()

fun ZonedDateTime.atZone(zone: ZoneId) = toInstant().atZone(zone)!!

fun ZonedDateTime.formatTime2(): String =
    format(formatterTime2)

// ISO8601の時差オフセット表記 "+0900" を Java8 Timeが扱える形式 "+09:00" に正規化する
private fun String.fixIso8601TimeOffset() =
    reFixTimeOffset.replace(this) { mr ->
        "${mr.groupValues[1]}:${mr.groupValues[2]}"
    }

// ISO8601の時差オフセットつきの日時を解釈する
fun String.parseIso8601(): ZonedDateTime =
    ZonedDateTime.parse(
        fixIso8601TimeOffset(),
        formatterIso8601OffsetDateTime,
    )

val reTsvSpecial = """([\x0d\x0a]+|[\x00-\x20\x7f"])""".toRegex()
fun List<Any?>.toTsvLine() = joinToString("\t") {
    var hasSpecial = false
    val col = reTsvSpecial.replace(it.toString()) { mr ->
        hasSpecial = true
        val t = mr.groupValues[1][0]
        when (t) {
            '\r', '\n' -> "\n"
            '"' -> "\"\""
            else -> " "
        }
    }
    if (hasSpecial) "\"$col\"" else col
}
