package com.fleeksoft.ksoup.internal

import com.fleeksoft.ksoup.ported.Character
import com.fleeksoft.ksoup.ported.appendRelativePath
import com.fleeksoft.ksoup.ported.isAbsResource
import com.fleeksoft.ksoup.ported.isValidResourceUrl
import de.cketti.codepoints.deluxe.CodePoint
import de.cketti.codepoints.deluxe.appendCodePoint
import de.cketti.codepoints.deluxe.codePointAt
import io.ktor.http.*
import kotlin.math.min

/**
 * A minimal String utility class. Designed for **internal** com.fleeksoft.ksoup use only - the API and outcome may change without
 * notice.
 */
internal object StringUtil {
    // memoised padding up to 21 (blocks 0 to 20 spaces)
    val padding = arrayOf(
        "",
        " ",
        "  ",
        "   ",
        "    ",
        "     ",
        "      ",
        "       ",
        "        ",
        "         ",
        "          ",
        "           ",
        "            ",
        "             ",
        "              ",
        "               ",
        "                ",
        "                 ",
        "                  ",
        "                   ",
        "                    ",
    )

    /**
     * Join a collection of strings by a separator
     * @param strings collection of string objects
     * @param sep string to place between strings
     * @return joined string
     */
    fun join(strings: Collection<*>, sep: String?): String {
        return join(strings.iterator(), sep)
    }

    /**
     * Join a collection of strings by a separator
     * @param strings iterator of string objects
     * @param sep string to place between strings
     * @return joined string
     */
    fun join(strings: Iterator<*>, sep: String?): String {
        if (!strings.hasNext()) return ""
        val start = strings.next().toString()
        if (!strings.hasNext()) {
            // only one, avoid builder
            return start
        }
        val j = StringJoiner(sep)
        j.add(start)
        while (strings.hasNext()) {
            j.add(strings.next())
        }
        return j.complete()
    }


    /**
     * Returns space padding, up to a max of maxPaddingWidth.
     * @param width amount of padding desired
     * @param maxPaddingWidth maximum padding to apply. Set to `-1` for unlimited.
     * @return string of spaces * width
     */
    /**
     * Returns space padding (up to the default max of 30). Use [.padding] to specify a different limit.
     * @param width amount of padding desired
     * @return string of spaces * width
     * @see .padding
     */
    fun padding(width: Int, maxPaddingWidth: Int = 30): String {
        require(width >= 0) { "width must be >= 0" }
        require(maxPaddingWidth >= -1)
        val effectiveWidth = if (maxPaddingWidth != -1) min(width, maxPaddingWidth) else width
        return if (effectiveWidth < padding.size) {
            padding[effectiveWidth]
        } else {
            " ".repeat(effectiveWidth)
        }
    }

    /**
     * Tests if a string is blank: null, empty, or only whitespace (" ", \r\n, \t, etc)
     * @param string string to test
     * @return if string is blank
     */
    fun isBlank(string: String?): Boolean {
        if (string.isNullOrEmpty()) return true
        val l = string.length
        for (i in 0 until l) {
            if (!isWhitespace(string.codePointAt(i).value)) return false
        }
        return true
    }

    /**
     * Tests if a string starts with a newline character
     * @param string string to test
     * @return if its first character is a newline
     */
    fun startsWithNewline(string: String?): Boolean {
        return if (string.isNullOrEmpty()) false else string[0] == '\n'
    }

    /**
     * Tests if a string is numeric, i.e. contains only digit characters
     * @param string string to test
     * @return true if only digit chars, false if empty or null or contains non-digit chars
     */
    fun isNumeric(string: String?): Boolean {
        if (string.isNullOrEmpty()) return false
        val l = string.length
        for (i in 0 until l) {
            if (!Character.isDigit(string.codePointAt(i))) return false
        }
        return true
    }

    /**
     * Tests if a code point is "whitespace" as defined in the HTML spec. Used for output HTML.
     * @param c code point to test
     * @return true if code point is whitespace, false otherwise
     * @see .isActuallyWhitespace
     */
    fun isWhitespace(c: Int): Boolean {
        return c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\u000c'.code || c == '\r'.code
    }

    /**
     * Tests if a code point is "whitespace" as defined by what it looks like. Used for Element.text etc.
     * @param c code point to test
     * @return true if code point is whitespace, false otherwise
     */
    fun isActuallyWhitespace(c: Int): Boolean {
        return c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\u000c'.code || c == '\r'.code || c == 160
        // 160 is &nbsp; (non-breaking space). Not in the spec but expected.
    }

    fun isInvisibleChar(c: Int): Boolean {
        return c == 8203 || c == 173 // zero width sp, soft hyphen
        // previously also included zw non join, zw join - but removing those breaks semantic meaning of text
    }

    /**
     * Normalise the whitespace within this string; multiple spaces collapse to a single, and all whitespace characters
     * (e.g. newline, tab) convert to a simple space.
     * @param string content to normalise
     * @return normalised string
     */
    fun normaliseWhitespace(string: String): String {
        val sb: StringBuilder = borrowBuilder()
        appendNormalisedWhitespace(sb, string, false)
        return releaseBuilder(sb)
    }

    /**
     * After normalizing the whitespace within a string, appends it to a string builder.
     * @param accum builder to append to
     * @param string string to normalize whitespace within
     * @param stripLeading set to true if you wish to remove any leading whitespace
     */
    fun appendNormalisedWhitespace(
        accum: StringBuilder,
        string: String,
        stripLeading: Boolean,
    ) {
        var lastWasWhite = false
        var reachedNonWhite = false
        val len = string.length
        var c: CodePoint
        var i = 0
        while (i < len) {
            c = string.codePointAt(i)
            if (isActuallyWhitespace(c.value)) {
                if (stripLeading && !reachedNonWhite || lastWasWhite) {
                    i += c.charCount
                    continue
                }
                accum.append(' ')
                lastWasWhite = true
            } else if (!isInvisibleChar(c.value)) {
                accum.appendCodePoint(c)
                lastWasWhite = false
                reachedNonWhite = true
            }
            i += c.charCount
        }
    }

    fun isIn(needle: String, vararg haystack: String): Boolean {
        val len = haystack.size
        for (i in 0 until len) {
            if (haystack[i] == needle) return true
        }
        return false
    }

    fun inSorted(needle: String, haystack: Array<String>): Boolean {
        return haystack.toList().binarySearch(needle) >= 0
    }

    /**
     * Tests that a String contains only ASCII characters.
     * @param string scanned string
     * @return true if all characters are in range 0 - 127
     */
    fun isAscii(string: String): Boolean {
        for (element in string) {
            val c = element.code
            if (c > 127) { // ascii range
                return false
            }
        }
        return true
    }

    fun resolve(base: Url, relUrl: String): Url {
        val cleanedRelUrl = stripControlChars(relUrl)

        if (cleanedRelUrl.isEmpty()) {
            return base
        }

        if (cleanedRelUrl.isValidResourceUrl()) {
            return URLBuilder(cleanedRelUrl).apply {
                if (cleanedRelUrl.startsWith("//")) {
                    protocol = base.protocol
                }
            }.build()
        }

        return URLBuilder(
            protocol = base.protocol,
            host = base.host,
            port = base.port,
            pathSegments = base.pathSegments
        ).appendRelativePath(cleanedRelUrl).build()
    }

    /**
     * Create a new absolute URL, from a provided existing absolute URL and a relative URL component.
     * @param baseUrl the existing absolute base URL
     * @param relUrl the relative URL to resolve. (If it's already absolute, it will be returned)
     * @return an absolute URL if one was able to be generated, or the empty string if not
     */
    fun resolve(baseUrl: String, relUrl: String): String {
        // workaround: java will allow control chars in a path URL and may treat as relative, but Chrome / Firefox will strip and may see as a scheme. Normalize to browser's view.
        val cleanedBaseUrl = stripControlChars(baseUrl)
        val cleanedRelUrl = stripControlChars(relUrl)

//        mailto, tel, geo, about etc..
        if (cleanedRelUrl.isAbsResource()) {
            return cleanedRelUrl
        }
        return if (cleanedBaseUrl.isValidResourceUrl()) {
            resolve(Url(cleanedBaseUrl), cleanedRelUrl).toString()
        } else if (cleanedRelUrl.isValidResourceUrl()) {
            Url(cleanedRelUrl).toString()
        } else {
            if (validUriScheme.matches(cleanedRelUrl)) cleanedRelUrl else ""
        }
    }

    private val validUriScheme: Regex = "^[a-zA-Z][a-zA-Z0-9+-.]*:".toRegex()
    private val controlChars: Regex =
        Regex("[\\x00-\\x1f]*") // matches ascii 0 - 31, to strip from url

    private fun stripControlChars(input: String): String {
        return input.replace(controlChars, "")
    }

    private val stringLocalBuilders: ArrayDeque<StringBuilder> = ArrayDeque()

    /**
     * Maintains cached StringBuilders in a flyweight pattern, to minimize new StringBuilder GCs. The StringBuilder is
     * prevented from growing too large.
     *
     *
     * Care must be taken to release the builder once its work has been completed, with [.releaseBuilder]
     * @return an empty StringBuilder
     */
    fun borrowBuilder(): StringBuilder {
        return StringBuilder(MaxCachedBuilderSize)
    }

    /**
     * Release a borrowed builder. Care must be taken not to use the builder after it has been returned, as its
     * contents may be changed by this method, or by a concurrent thread.
     * @param sb the StringBuilder to release.
     * @return the string value of the released String Builder (as an incentive to release it!).
     */

    // TODO: replace this
    fun releaseBuilder(sb: StringBuilder): String {
        var stringBuilder: StringBuilder = sb
        val string: String = stringBuilder.toString()
        if (stringBuilder.length > MaxCachedBuilderSize) {
            stringBuilder = StringBuilder(MaxCachedBuilderSize) // make sure it hasn't grown too big
        } else {
            stringBuilder.clear() // make sure it's emptied on release
        }
        stringLocalBuilders.addLast(stringBuilder)
        while (stringLocalBuilders.size > MaxIdleBuilders) {
            stringLocalBuilders.removeLast()
        }
        return string
    }

    private const val MaxCachedBuilderSize = 8 * 1024
    private const val MaxIdleBuilders = 8

    /**
     * A StringJoiner allows incremental / filtered joining of a set of stringable objects.
     * @since 1.14.1
     */
    class StringJoiner
    /**
     * Create a new joiner, that uses the specified separator. MUST call [.complete] or will leak a thread
     * local string builder.
     *
     * @param separator the token to insert between strings
     */(val separator: String?) {

        // sets null on builder release so can't accidentally be reused
        var sb: StringBuilder? = borrowBuilder()
        var first = true

        /**
         * Add another item to the joiner, will be separated
         */
        fun add(stringy: Any?): StringJoiner {
            if (!first) sb!!.append(separator)
            sb!!.append(stringy)
            first = false
            return this
        }

        /**
         * Append content to the current item; not separated
         */
        fun append(stringy: Any?): StringJoiner {
            sb!!.append(stringy)
            return this
        }

        /**
         * Return the joined string, and release the builder back to the pool. This joiner cannot be reused.
         */
        fun complete(): String {
            val string = sb?.let { releaseBuilder(it) }
            sb = null
            return string ?: ""
        }
    }
}
