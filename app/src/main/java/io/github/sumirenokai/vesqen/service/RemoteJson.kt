package io.github.sumirenokai.vesqen.service

/** Deeper than any answer the owner's service or website sends, which nest five levels at most. */
internal const val MAX_REMOTE_JSON_DEPTH = 16

/**
 * Whether JSON [text] from the network nests objects and arrays at most [maxDepth] deep. org.json
 * goes one call deeper per level without a limit, so a small body nested thousands deep overflows
 * the stack with an Error that nothing catches, and the app ends (#194 S1).
 *
 * org.json also reads comments, single-quoted strings and unquoted strings, any of which can hold a
 * quote that would hide the brackets after it from this count (#194, round 3 B3). So text with a
 * comment or a single quote is refused, and so is a double quote anywhere but where org.json starts a
 * string: at the start or after `{`, `[`, `,` or `:`. The service and the website send none of these.
 * A closing bracket outside a string closes a level for org.json too, or stops it with a syntax error.
 */
internal fun jsonNestedWithin(text: String, maxDepth: Int = MAX_REMOTE_JSON_DEPTH): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    // The last character outside a string other than whitespace; '[' stands for the start of the text.
    var previous = '['
    for (char in text) {
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> {
                    inString = false
                    previous = char
                }
            }
            continue
        }
        when (char) {
            ' ', '\t', '\n', '\r' -> continue
            '"' -> if (previous in STRING_STARTS) inString = true else return false
            '{', '[' -> if (++depth > maxDepth) return false
            '}', ']' -> depth--
            '\'', '/', '#' -> return false
        }
        previous = char
    }
    return true
}

/** The characters after which org.json reads a double quote as the start of a string. */
private const val STRING_STARTS = "{[,:"
