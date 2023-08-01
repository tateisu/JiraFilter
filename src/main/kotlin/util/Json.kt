@file:Suppress("MemberVisibilityCanBePrivate")

package util

import java.io.BufferedReader
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter
import java.io.Writer
import java.math.BigDecimal
import java.math.BigInteger

class JsonException : RuntimeException {
    constructor(message: String?) : super(message)
    constructor(message: String?, cause: Throwable?) : super(message, cause)
    constructor(cause: Throwable) : super(cause.message, cause)
}

private const val char0 = '\u0000'

// Tests if the value should be tried as a decimal.
// It makes no test if there are actual digits.
// return true if the string is "-0" or if it contains '.', 'e', or 'E', false otherwise.
private fun String.isDecimalNotation(): Boolean =
    indexOf('.') > -1 ||
            indexOf('e') > -1 ||
            indexOf('E') > -1 ||
            this == "-0"

private fun String.stringToNumber(): Number {
    val initial = this.firstOrNull()
    if (initial != null && (initial in '0'..'9' || initial == '-')) {
        val length = this.length
        when {
            isDecimalNotation() -> return if (length > 14) {
                BigDecimal(this)
            } else {
                val d = this.toDouble()
                if (d.isInfinite() || d.isNaN()) {
                    // if we can't parse it as a double, go up to BigDecimal
                    // this is probably due to underflow like 4.32e-678
                    // or overflow like 4.65e5324. The size of the string is small
                    // but can't be held in a Double.
                    BigDecimal(this)
                } else {
                    d
                }
            }

            length <= 9 -> return this.toInt()

            length <= 18 -> return this.toLong()

            else -> {
                // BigInteger version: We use a similar bitLength compare as
                // BigInteger#intValueExact uses. Increases GC, but objects hold
                // only what they need. i.e. Less runtime overhead if the value is
                // long lived. Which is the better tradeoff? This is closer to what's
                // in stringToValue.
                val bi = BigInteger(this)
                return when {
                    bi.bitLength() <= 31 -> bi.toInt()
                    bi.bitLength() <= 63 -> bi.toLong()
                    else -> bi
                }
            }
        }
    }
    throw NumberFormatException("val [$this] is not a valid number.")
}

private fun Any?.asNumber(defaultValue: Number): Number =
    when (this) {
        null -> defaultValue
        is Number -> this
        else -> try {
            toString().stringToNumber()
        } catch (e: Exception) {
            defaultValue
        }
    }

@Suppress("unused")
class JsonArray : ArrayList<Any?> {

    constructor(capacity: Int = 10) : super(capacity)
    constructor(collection: Collection<*>) : super(collection)
    constructor(array: Array<*>) : super(array.toList())

    fun toString(indentFactor: Int): String {
        val sw = StringWriter()
        synchronized(sw.buffer) {
            return sw.writeJsonValue(indentFactor, 0, this).toString()
        }
    }

    override fun toString(): String = toString(0)

    fun objectList() = mapNotNull { it as? JsonObject }

    fun stringList() = mapNotNull { it?.toString() }

    fun stringArrayList() = ArrayList<String>(stringList())

    fun floatArrayList() = ArrayList<Float>(this.size).apply {
        addAll(this@JsonArray.mapNotNull { this.asNumber(0f).toFloat() })
    }

    fun string(key: Int): String? = this[key]?.toString()
    fun boolean(key: Int): Boolean? = JsonObject.castBoolean(this[key])
    fun int(key: Int): Int? = JsonObject.castInt(this[key])
    fun long(key: Int): Long? = JsonObject.castLong(this[key])
    fun float(key: Int): Float? = JsonObject.castFloat(this[key])

    @Suppress("MemberVisibilityCanBePrivate")
    fun double(key: Int): Double? = JsonObject.castDouble(this[key])

    fun jsonObject(key: Int) = this[key] as? JsonObject
    fun jsonArray(key: Int) = this[key] as? JsonArray

    fun optString(key: Int, defVal: String = "") = string(key) ?: defVal
    fun optBoolean(key: Int, defVal: Boolean = false) = boolean(key) ?: defVal

    @Suppress("unused")
    fun optInt(key: Int, defVal: Int = 0) = int(key) ?: defVal

    @Suppress("unused")
    fun optLong(key: Int, defVal: Long = 0L) = long(key) ?: defVal

    @Suppress("unused")
    fun optFloat(key: Int, defVal: Float = 0f) = float(key) ?: defVal

    @Suppress("unused")
    fun optDouble(key: Int, defVal: Double = 0.0) = double(key) ?: defVal

    @Suppress("unused")
    fun notEmptyOrThrow(key: Int) = notEmptyOrThrow(key.toString(), string(key))

    @Suppress("unused")
    fun isNull(key: Int) = this[key] == null
}

// https://stackoverflow.com/questions/5525795/does-javascript-guarantee-object-property-order/38218582#38218582
// ブラウザはES2015によりオブジェクト列挙順序に挿入順序が影響する
// JSONにそんな規定はないが、MisskeyのAPIはこれに依存した挙動をする
// https://github.com/syuilo/misskey/issues/5684

@Suppress("unused")
class JsonObject : LinkedHashMap<String, Any?>() {

    companion object {

        fun castBoolean(o: Any?): Boolean? =
            when (o) {
                null -> null
                is Boolean -> o
                is Int -> o != 0
                is Long -> o != 0L
                is Float -> !(o.isFinite() && o == 0f)
                is Double -> !(o.isFinite() && o == 0.0)

                is String -> when (o) {
                    "", "0", "false", "False" -> false
                    else -> true
                }

                is JsonArray -> o.isNotEmpty()
                is JsonObject -> o.isNotEmpty()

                else -> true
            }

        fun castLong(o: Any?): Long? =
            when (o) {
                is Long -> o
                is Number -> o.toLong()

                is String -> try {
                    o.stringToNumber().toLong()
                } catch (_: NumberFormatException) {
                    null
                }

                else -> null // may null or JsonObject.NULL or object,array,boolean
            }

        fun castInt(o: Any?): Int? =
            when (o) {

                is Int -> o

                is Number -> try {
                    o.toInt()
                } catch (_: NumberFormatException) {
                    null
                }

                is String -> try {
                    o.stringToNumber().toInt()
                } catch (_: NumberFormatException) {
                    null
                }

                else -> null // may null or JsonObject.NULL or object,array,boolean
            }

        fun castDouble(o: Any?): Double? =
            when (o) {

                is Double -> o

                is Number -> try {
                    o.toDouble()
                } catch (_: NumberFormatException) {
                    null
                }

                is String -> try {
                    o.stringToNumber().toDouble()
                } catch (_: NumberFormatException) {
                    null
                }

                else -> null // may null or JsonObject.NULL or object,array,boolean
            }

        fun castFloat(o: Any?): Float? =
            when (o) {

                is Float -> o

                is Number -> try {
                    o.toFloat()
                } catch (_: NumberFormatException) {
                    null
                }

                is String -> try {
                    o.stringToNumber().toFloat()
                } catch (_: NumberFormatException) {
                    null
                }

                else -> null // may null or JsonObject.NULL or object,array,boolean
            }
    }

    fun toString(indentFactor: Int): String {
        val sw = StringWriter()
        synchronized(sw.buffer) {
            return sw.writeJsonValue(indentFactor, 0, this).toString()
        }
    }

    override fun toString(): String = toString(0)

    fun string(key: String): String? = this[key]?.toString()
    fun boolean(key: String): Boolean? = castBoolean(this[key])
    fun int(key: String): Int? = castInt(this[key])
    fun long(key: String): Long? = castLong(this[key])
    fun float(key: String): Float? = castFloat(this[key])
    fun double(key: String): Double? = castDouble(this[key])
    fun jsonObject(name: String) = this[name] as? JsonObject
    fun jsonArray(name: String) = this[name] as? JsonArray

    fun stringArrayList(name: String): ArrayList<String>? =
        jsonArray(name)?.stringArrayList()?.takeIf { it.isNotEmpty() }

    fun floatArrayList(name: String): ArrayList<Float>? =
        jsonArray(name)?.floatArrayList()?.takeIf { it.isNotEmpty() }

    fun optString(name: String, defVal: String = "") = string(name) ?: defVal
    fun optBoolean(name: String, defVal: Boolean = false) = boolean(name) ?: defVal
    fun optInt(name: String, defVal: Int = 0) = int(name) ?: defVal
    fun optLong(name: String, defVal: Long = 0L) = long(name) ?: defVal
    fun optFloat(name: String, defVal: Float = 0f) = float(name) ?: defVal

    @Suppress("unused")
    fun optDouble(name: String, defVal: Double = 0.0) = double(name) ?: defVal

    fun stringOrThrow(name: String) = notEmptyOrThrow(name, string(name))

    // fun isNull(name : String) = this[name] == null
    fun putNotNull(name: String, value: Any?) {
        if (value != null) put(name, value)
    }
}

class JsonTokenizer(reader: Reader) {

    companion object {

        private fun String.toStringOrNumber(): Any {
            /*
             * If it might be a number, try converting it. If a number cannot be
             * produced, then the value will just be a string.
             */
            val initial = this.firstOrNull()
            if (initial != null && (initial in '0'..'9' || initial == '-')) {
                try { // if we want full Big Number support the contents of this
                    // `try` block can be replaced with:
                    // return stringToNumber(string);
                    if (isDecimalNotation()) {
                        val d = toDouble()
                        if (!d.isInfinite() && !d.isNaN()) {
                            return d
                        }
                    } else {
                        val longValue = toLong()
                        if (longValue.toString() == this) {
                            try {
                                val intValue = longValue.toInt()
                                if (intValue.toLong() == longValue) return intValue
                            } catch (_: Throwable) {
                                // ignored
                            }
                            return longValue
                        }
                    }
                } catch (ignore: Exception) {
                }
            }
            return this
        }
    }

    // constructor(inputStream : InputStream) : this(InputStreamReader(inputStream))
    constructor(s: String) : this(StringReader(s))

    /** current read character position on the current line.  */
    private var character = 1L

    /** flag to indicate if the end of the input has been found.  */
    private var eof = false

    /** current read index of the input.  */
    private var index = 0L

    /** current line of the input.  */
    private var line = 1L

    /** previous character read from the input.  */
    private var previous = char0

    /** Reader for the input.  */
    private val reader = if (reader.markSupported()) reader else BufferedReader(reader)

    /** flag to indicate that a previous character was requested.  */
    private var usePrevious = false

    /** the number of characters read in the previous line.  */
    private var characterPreviousLine = 0L

    /**
     * Back up one character. This provides a sort of lookahead capability,
     * so that you can test for a digit or letter before attempting to parse
     * the next number or identifier.
     * @throws JsonException Thrown if trying to step back more than 1 step
     * or if already at the start of the string
     */
    private fun back() {
        if (usePrevious || index <= 0) {
            throw JsonException("Stepping back two steps is not supported")
        }
        decrementIndexes()
        usePrevious = true
        eof = false
    }

    /**
     * Decrements the indexes for the [.back] method based on the previous character read.
     */
    private fun decrementIndexes() {
        index--
        if (previous == '\r' || previous == '\n') {
            line--
            character = characterPreviousLine
        } else if (character > 0) {
            character--
        }
    }

    /**
     * Checks if the end of the input has been reached.
     *
     * @return true if at the end of the file and we didn't step back
     */
    private fun end(): Boolean {
        return eof && !usePrevious
    }

    //	/**
    //	 * Determine if the source string still contains characters that next()
    //	 * can consume.
    //	 * @return true if not yet at the end of the source.
    //	 * @throws JsonException thrown if there is an error stepping forward
    //	 * or backward while checking for more data.
    //	 */
    //	private fun more() : Boolean {
    //		if(usePrevious) {
    //			return true
    //		}
    //		try {
    //			reader.mark(1)
    //		} catch(e : IOException) {
    //			throw JsonException("Unable to preserve stream position", e)
    //		}
    //		try { // -1 is EOF, but next() can not consume the null character '\0'
    //			if(reader.read() <= 0) {
    //				eof = true
    //				return false
    //			}
    //			reader.reset()
    //		} catch(e : IOException) {
    //			throw JsonException("Unable to read the next character from the stream", e)
    //		}
    //		return true
    //	}

    /**
     * Get the next character in the source string.
     *
     * @return The next character, or 0 if past the end of the source string.
     * @throws JsonException Thrown if there is an error reading the source string.
     */
    private operator fun next(): Char {
        val c: Char
        if (usePrevious) {
            usePrevious = false
            c = previous
        } else {
            val i = try {
                reader.read()
            } catch (exception: IOException) {
                throw JsonException(exception)
            }
            if (i <= 0) { // End of stream
                eof = true
                return char0
            }
            c = i.toChar()
        }
        incrementIndexes(c)
        previous = c
        return c
    }

    /**
     * Increments the internal indexes according to the previous character
     * read and the character passed as the current character.
     * @param c the current character read.
     */
    private fun incrementIndexes(c: Char) {
        if (c == char0) return
        index++
        when (c) {
            '\r' -> {
                line++
                characterPreviousLine = character
                character = 0
            }

            '\n' -> {
                if (previous != '\r') {
                    line++
                    characterPreviousLine = character
                }
                character = 0
            }

            else -> {
                character++
            }
        }
    }

    //	/**
    //	 * Consume the next character, and check that it matches a specified
    //	 * character.
    //	 * @param c The character to match.
    //	 * @return The character.
    //	 * @throws JsonException if the character does not match.
    //	 */
    //	private fun next(c : Char) : Char {
    //		val n = this.next()
    //		if(n != c) {
    //			if(n.toInt() > 0) {
    //				throw this.syntaxError(
    //					"Expected '" + c + "' and instead saw '" +
    //						n + "'"
    //				)
    //			}
    //			throw this.syntaxError("Expected '$c' and instead saw ''")
    //		}
    //		return n
    //	}

    /**
     * Get the next n characters.
     *
     * @param n     The number of characters to take.
     * @return      A string of n characters.
     * @throws JsonException
     * Substring bounds error if there are not
     * n characters remaining in the source string.
     */
    private fun next(@Suppress("SameParameterValue") n: Int): String {
        if (n == 0) {
            return ""
        }
        val chars = CharArray(n)
        var pos = 0
        while (pos < n) {
            chars[pos] = this.next()
            if (end()) {
                throw this.syntaxError("Substring bounds error")
            }
            pos += 1
        }
        return String(chars)
    }

    /**
     * Get the next char in the string, skipping whitespace.
     * @throws JsonException Thrown if there is an error reading the source string.
     * @return  A character, or 0 if there are no more characters.
     */
    private fun nextClean(): Char {
        while (true) {
            val c = this.next()
            if (c == char0 || c > ' ') {
                return c
            }
        }
    }

    /**
     * Return the characters up to the next close quote character.
     * Backslash processing is done. The formal JSON format does not
     * allow strings in single quotes, but an implementation is allowed to
     * accept them.
     * @param quote The quoting character, either
     * `"`&nbsp;<small>(double quote)</small> or
     * `'`&nbsp;<small>(single quote)</small>.
     * @return      A String.
     * @throws JsonException Unterminated string.
     */
    private fun nextString(quote: Char): String {
        val sb = StringBuilder()
        while (true) {
            var c: Char = this.next()
            when (c) {
                char0, '\n', '\r' ->
                    throw this.syntaxError("Unterminated string")

                quote ->
                    return sb.toString()

                '\\' -> {
                    c = this.next()
                    when (c) {
                        'b' -> sb.append('\b')
                        't' -> sb.append('\t')
                        'n' -> sb.append('\n')
                        'f' -> sb.append('\u000c')
                        'r' -> sb.append('\r')
                        'u' -> try {
                            sb.append(this.next(4).toInt(16).toChar())
                        } catch (e: NumberFormatException) {
                            throw syntaxError("Illegal escape.", e)
                        }

                        '"', '\'', '\\', '/' -> sb.append(c)
                        else -> throw syntaxError("Illegal escape.")
                    }
                }

                else -> sb.append(c)
            }
        }
    }

    //	/**
    //	 * Get the text up but not including the specified character or the
    //	 * end of line, whichever comes first.
    //	 * @param  delimiter A delimiter character.
    //	 * @return   A string.
    //	 * @throws JsonException Thrown if there is an error while searching
    //	 * for the delimiter
    //	 */
    //	fun nextTo(delimiter : Char) : String {
    //		val sb = StringBuilder()
    //		while(true) {
    //			val c = this.next()
    //			if(c == delimiter || c == char0 || c == '\n' || c == '\r') {
    //				if(c != char0) {
    //					back()
    //				}
    //				return sb.toString().trim()
    //			}
    //			sb.append(c)
    //		}
    //	}

    //	/**
    //	 * Get the text up but not including one of the specified delimiter
    //	 * characters or the end of line, whichever comes first.
    //	 * @param delimiters A set of delimiter characters.
    //	 * @return A string, trimmed.
    //	 * @throws JsonException Thrown if there is an error while searching
    //	 * for the delimiter
    //	 */
    //	fun nextTo(delimiters : String) : String {
    //		val sb = StringBuilder()
    //		while(true) {
    //			val c = this.next()
    //			if(delimiters.indexOf(c) >= 0 || c == char0 || c == '\n' || c == '\r') {
    //				if(c != char0) {
    //					back()
    //				}
    //				return sb.toString().trim { it <= ' ' }
    //			}
    //			sb.append(c)
    //		}
    //	}

    /**
     * Get the next value. The value can be a Boolean, Double, Integer,
     * JsonArray, JsonObject, Long, or String, or the JsonObject.NULL object.
     * @throws JsonException If syntax error.
     *
     * @return An object.
     */
    fun nextValue(): Any? {
        var c = nextClean()
        val string: String
        when (c) {
            '"', '\'' -> return nextString(c)

            '{' -> {
                back()
                return parseInto(JsonObject())
            }

            '[' -> {
                back()
                return parseInto(JsonArray())
            }
        }
        /*
         * Handle unquoted text. This could be the values true, false, or
         * null, or it can be a number. An implementation (such as this one)
         * is allowed to also accept non-standard forms.
         *
         * Accumulate characters until we reach the end of the text or a
         * formatting character.
         */
        val sb = StringBuilder()
        while (c >= ' ' && ",:]}/\\\"[{;=#".indexOf(c) < 0) {
            sb.append(c)
            c = this.next()
        }
        if (!eof) {
            back()
        }
        string = sb.toString().trim { it <= ' ' }
        if ("" == string) {
            throw syntaxError("Missing value")
        }
        return with(string) {
            when {
                isEmpty() -> ""
                equals("true", ignoreCase = true) -> true
                equals("false", ignoreCase = true) -> false
                equals("null", ignoreCase = true) -> null
                else -> toStringOrNumber()
            }
        }
    }

    //	/**
    //	 * Skip characters until the next character is the requested character.
    //	 * If the requested character is not found, no characters are skipped.
    //	 * @param to A character to skip to.
    //	 * @return The requested character, or zero if the requested character
    //	 * is not found.
    //	 * @throws JsonException Thrown if there is an error while searching
    //	 * for the to character
    //	 */
    //	@Throws(JsonException::class)
    //	fun skipTo(to : Char) : Char {
    //		var c : Char
    //		try {
    //			val startIndex = index
    //			val startCharacter = character
    //			val startLine = line
    //			reader.mark(1000000)
    //			do {
    //				c = this.next()
    //				if(c.toInt() == 0) { // in some readers, reset() may throw an exception if
    //					// the remaining portion of the input is greater than
    //					// the mark size (1,000,000 above).
    //					reader.reset()
    //					index = startIndex
    //					character = startCharacter
    //					line = startLine
    //					return char0
    //				}
    //			} while(c != to)
    //			reader.mark(1)
    //		} catch(exception : IOException) {
    //			throw JsonException(exception)
    //		}
    //		back()
    //		return c
    //	}

    /**
     * Make a JsonException to signal a syntax error.
     *
     * @param message The error message.
     * @return  A JsonException object, suitable for throwing
     */
    private fun syntaxError(message: String): JsonException {
        return JsonException(message + this.toString())
    }

    /**
     * Make a JsonException to signal a syntax error.
     *
     * @param message The error message.
     * @param causedBy The throwable that caused the error.
     * @return  A JsonException object, suitable for throwing
     */
    private fun syntaxError(
        @Suppress("SameParameterValue") message: String,
        causedBy: Throwable?,
    ) = JsonException(message + toString(), causedBy)

    /**
     * Make a printable string of this JSONTokener.
     *
     * @return " at {index} [character {character} line {line}]"
     */
    override fun toString(): String =
        " at $index [character $character line $line]"

    private fun parseInto(dst: JsonObject): JsonObject {

        if (nextClean() != '{')
            throw syntaxError("A JsonObject text must begin with '{'")

        while (true) {
            var c: Char = nextClean()
            val key: String = when (c) {
                char0 ->
                    throw syntaxError("A JsonObject text must end with '}'")

                '}' ->
                    return dst

                else -> {
                    back()
                    nextValue().toString()
                }
            }
            // The key is followed by ':'.
            c = nextClean()
            if (c != ':')
                throw syntaxError("Expected a ':' after a key")

            // Use syntaxError(..) to include error location
            // Check if key exists

            // key already exists
            if (dst.contains(key))
                throw syntaxError("Duplicate key \"$key\"")

            // Only add value if non-null
            dst[key] = nextValue()
            when (nextClean()) {
                ';', ',' -> {
                    if (nextClean() == '}') {
                        return dst
                    }
                    back()
                }

                '}' -> return dst
                else -> throw syntaxError("Expected a ',' or '}'")
            }
        }
    }

    private fun parseInto(dst: JsonArray): JsonArray {
        if (nextClean() != '[')
            throw syntaxError("A JsonArray text must start with '['")

        when (nextClean()) {
            // array is unclosed. No ']' found, instead EOF
            char0 -> throw syntaxError("Expected a ',' or ']'")
            // empty array
            ']' -> return dst

            else -> {
                back()
                while (true) {
                    if (nextClean() == ',') {
                        back()
                        dst.add(null)
                    } else {
                        back()
                        dst.add(nextValue())
                    }
                    when (nextClean()) {
                        char0 -> throw syntaxError("Expected a ',' or ']'")
                        ']' -> return dst
                        ',' -> when (nextClean()) {
                            // array is unclosed. No ']' found, instead EOF
                            char0 -> throw syntaxError("Expected a ',' or ']'")
                            ']' -> return dst
                            else -> back()
                        }

                        else -> throw syntaxError("Expected a ',' or ']'")
                    }
                }
            }
        }
    }
}

private val reNumber = """-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?""".toRegex()

private fun Writer.writeQuote(string: String): Writer {
    if (string.isEmpty()) {
        write("\"\"")
    } else {
        append('"')
        var previousChar: Char = char0
        for (c in string) {
            when (c) {
                '\\', '"' -> {
                    append('\\')
                    append(c)
                }

                '/' -> {
                    if (previousChar == '<') append('\\')
                    append(c)
                }

                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000c' -> append("\\f")
                '\r' -> append("\\r")

                in char0 until ' ',
                in '\u0080' until '\u00a0',
                in '\u2000' until '\u2100',
                -> {
                    write("\\u")
                    val hexCode: String = Integer.toHexString(c.code)
                    write("0000", 0, 4 - hexCode.length)
                    write(hexCode)
                }

                else -> append(c)
            }
            previousChar = c
        }
        append('"')
    }
    return this
}

private fun Number.toJsonString(): String {

    when (this) {
        is Double -> if (isInfinite() || isNaN())
            throw JsonException("JSON does not allow non-finite numbers.")

        is Float -> if (isInfinite() || isNaN())
            throw JsonException("JSON does not allow non-finite numbers.")
    }

    // Shave off trailing zeros and decimal point, if possible.
    var string = toString()
    if (string.indexOf('.') > 0 &&
        string.indexOf('e') < 0 &&
        string.indexOf('E') < 0
    ) {
        while (string.endsWith("0")) {
            string = string.substring(0, string.length - 1)
        }
        if (string.endsWith(".")) {
            string = string.substring(0, string.length - 1)
        }
    }
    return string
}

private fun Writer.indent(indentFactor: Int, indent: Int): Writer {
    if (indentFactor > 0) {
        append('\n')
        for (i in 0 until indent) append(' ')
    }
    return this
}

private fun Writer.writeCollection(indentFactor: Int, indent: Int, src: Collection<*>): Writer =
    try {
        append('[')
        when (src.size) {
            0 -> {
            }

            1 -> try {
                writeJsonValue(indentFactor, indent, src.iterator().next())
            } catch (e: Exception) {
                throw JsonException("Unable to write JsonArray value at index: 0", e)
            }

            else -> {
                val newIndent = indent + indentFactor
                for ((index, value) in src.withIndex()) {
                    if (index > 0) append(',')
                    indent(indentFactor, newIndent)
                    try {
                        writeJsonValue(indentFactor, newIndent, value)
                    } catch (ex: Exception) {
                        throw JsonException("Unable to write JsonArray value at index: $index", ex)
                    }
                }
                indent(indentFactor, indent)
            }
        }
        append(']')
        this
    } catch (e: IOException) {
        throw JsonException(e)
    }

private fun Writer.writeArray(indentFactor: Int, indent: Int, src: Any): Writer =
    try {
        append('[')
        when (val size = java.lang.reflect.Array.getLength(src)) {
            0 -> {
            }

            1 -> try {
                val value = java.lang.reflect.Array.get(src, 0)
                writeJsonValue(indentFactor, indent, value)
            } catch (e: Exception) {
                throw JsonException("Unable to write JsonArray value at index: 0", e)
            }

            else -> {
                val newIndent = indent + indentFactor
                for (index in 0 until size) {
                    if (index > 0) append(',')
                    indent(indentFactor, newIndent)
                    try {
                        val value = java.lang.reflect.Array.get(src, index)
                        writeJsonValue(indentFactor, newIndent, value)
                    } catch (ex: Exception) {
                        throw JsonException("Unable to write JsonArray value at index: $index", ex)
                    }
                }
                indent(indentFactor, indent)
            }
        }
        append(']')
        this
    } catch (e: IOException) {
        throw JsonException(e)
    }

private fun Writer.writeMap(indentFactor: Int, indent: Int, src: Map<*, *>): Writer =
    try {
        append('{')
        when (src.size) {
            0 -> {
            }

            1 -> {
                val entry = src.entries.first()
                writeJsonValue(indentFactor, indent, entry.key)
                append(':')
                if (indentFactor > 0) append(' ')
                try {
                    writeJsonValue(indentFactor, indent, entry.value)
                } catch (ex: Throwable) {
                    throw JsonException(
                        "Unable to write JsonObject value for key: ${entry.key}",
                        ex
                    )
                }
            }

            else -> {
                val newIndent = indent + indentFactor
                var needsComma = false
                for (entry in src.entries) {
                    if (needsComma) append(',')
                    indent(indentFactor, newIndent)
                    writeJsonValue(indentFactor, newIndent, entry.key)
                    append(':')
                    if (indentFactor > 0) append(' ')
                    try {
                        writeJsonValue(indentFactor, newIndent, entry.value)
                    } catch (ex: Exception) {
                        throw JsonException(
                            "Unable to write JsonObject value for key: ${entry.key}",
                            ex
                        )
                    }
                    needsComma = true
                }
                indent(indentFactor, indent)
            }
        }
        append('}')
        this
    } catch (e: IOException) {
        throw JsonException(e)
    }

fun Writer.writeJsonValue(
    indentFactor: Int,
    indent: Int,
    value: Any?,
): Writer {
    when {
        value == null -> write("null")

        value is Boolean -> write(value.toString())

        value is Number -> {
            val sv = value.toJsonString()
            if (reNumber.matches(sv)) {
                write(sv)
            } else {
                // not all Numbers may match actual JSON Numbers. i.e. fractions or Imaginary
                // The Number value is not a valid JSON number.
                // Instead we will quote it as a string
                writeQuote(sv)
            }
        }

        value is Char -> writeJsonValue(indentFactor, indent, value.code)

        value is String -> writeQuote(value)
        value is Enum<*> -> writeQuote(value.name)
        value is JsonObject -> writeMap(indentFactor, indent, value)
        value is Map<*, *> -> writeMap(indentFactor, indent, value)
        value is Collection<*> -> writeCollection(indentFactor, indent, value)
        value.javaClass.isArray -> writeArray(indentFactor, indent, value)
        else -> writeQuote(value.toString())
    }
    return this
}

/////////////////////////////////////////////////////////////////////////////

fun notEmptyOrThrow(name: String, value: String?) =
    if (value?.isNotEmpty() == true) value else throw RuntimeException("$name is empty")

private val log = LogTag("Json")

// return null if the json value is "null"
fun String.decodeJsonValue() = try {
    JsonTokenizer(this).nextValue()
} catch (ex: Throwable) {
    log.e(ex, "decodeJsonValue failed. $this")
    throw ex
}

//fun String.parseJsonOrNull() =try {
//	decodeJsonValue()
//}catch(ex:Throwable){
//	log.e(ex,"decodeJsonValue() failed.")
//	null
//}

@Suppress("unused")
fun String.decodeJsonObject() = decodeJsonValue() as JsonObject

@Suppress("unused")
fun String.decodeJsonArray() = decodeJsonValue() as JsonArray

@Suppress("unused")
fun Array<*>.toJsonArray(): JsonArray = JsonArray(this)

@Suppress("unused")
fun List<*>.toJsonArray() = JsonArray(this)

inline fun jsonObject(initializer: JsonObject.() -> Unit) =
    JsonObject().apply { initializer() }

@Suppress("unused")
inline fun jsonArray(initializer: JsonArray.() -> Unit) =
    JsonArray().apply { initializer() }

@Suppress("unused")
fun jsonArray(vararg args: Any?) = JsonArray(args)

@Suppress("unused")
fun jsonObject(vararg args: Pair<String, *>) = JsonObject().apply {
    for (pair in args) {
        put(pair.first, pair.second)
    }
}

val rePathDelimiterDefault = """[./]""".toRegex()
fun Any.pathToAny(
    path: String,
    delimiters: Regex = rePathDelimiterDefault,
): Any? {
    var value: Any? = this
    var lastEnd = 0
    var hasError = false
    fun handleDelimiter(dlmStart: Int, dlmEnd: Int) {
        if (!hasError) {
            val k = path.substring(lastEnd, dlmStart)
            value = when (val v = value) {
                is JsonObject -> v[k]
                is JsonArray -> {
                    var i = k.toIntOrNull()
                    when (i) {
                        null -> {
                            val prefix = path.substring(0, lastEnd).takeIf { it.isNotEmpty() } ?: "(root)"
                            log.v { "'$prefix' is JsonArray, but '$k' is not integer." }
                            hasError = true
                            null
                        }

                        else -> {
                            if (i < 0) i += v.size
                            if (i !in v.indices) {
                                val prefix = path.substring(0, lastEnd).takeIf { it.isNotEmpty() } ?: "(root)"
                                log.v { "'$prefix' is JsonArray, but '$k' is not in range ${v.indices}." }
                                hasError = true
                                null
                            } else {
                                v.elementAtOrNull(i)
                            }
                        }
                    }
                }

                else -> {
                    val prefix = path.substring(0, lastEnd).takeIf { it.isNotEmpty() } ?: "(root)"
                    log.v { "'$prefix' is $v, that has no child '$k'" }
                    hasError = true
                    null
                }
            }
        }
        lastEnd = dlmEnd
    }
    delimiters.findAll(path).forEach {
        handleDelimiter(it.range.first, it.range.last + 1)
    }
    if (lastEnd < path.length) {
        handleDelimiter(path.length, path.length)
    }
    return value
}

fun Any.pathToJsonObject(
    path: String,
    delimiters: Regex = rePathDelimiterDefault,
) = pathToAny(path, delimiters) as? JsonObject

fun Any.pathToJsonArray(
    path: String,
    delimiters: Regex = rePathDelimiterDefault,
) = pathToAny(path, delimiters) as? JsonArray

fun Any.pathToString(
    path: String,
    delimiters: Regex = rePathDelimiterDefault,
) = pathToAny(path, delimiters)?.toString()
