package io.github.sumirenokai.vesqen.service

/** Deeper than any answer the owner's service or website sends, which nest five levels at most. */
internal const val MAX_REMOTE_JSON_DEPTH = 16

/**
 * Whether JSON [text] from the network nests objects and arrays at most [maxDepth] deep. org.json
 * goes one call deeper per level without a limit, so a small body nested thousands deep overflows
 * the stack with an Error that nothing catches, and the app ends (#194 S1).
 *
 * org.json also reads comments and single-quoted strings, whose quotes could hide brackets from this
 * count, so text with either is refused; the service and the website send neither. A closing
 * bracket outside a string closes a level for org.json too, or stops it with a syntax error.
 */
internal fun jsonNestedWithin(text: String, maxDepth: Int = MAX_REMOTE_JSON_DEPTH): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (char in text) {
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            continue
        }
        when (char) {
            '"' -> inString = true
            '{', '[' -> if (++depth > maxDepth) return false
            '}', ']' -> depth--
            '\'', '/', '#' -> return false
        }
    }
    return true
}
