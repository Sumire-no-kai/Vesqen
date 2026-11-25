package io.github.sumirenokai.vesqen.ui.chain

/**
 * The customizer's metric search. Every word of [query] must appear, ignoring case, in the
 * metric's [label] or in its [sectionLabel], so "USB" also finds a USB section metric whose own
 * name does not say USB. A blank query matches nothing; the customizer shows its index instead.
 */
internal fun chainMetricMatches(query: String, label: String, sectionLabel: String): Boolean {
    val terms = chainSearchTerms(query)
    return terms.isNotEmpty() && terms.all { label.contains(it, ignoreCase = true) || sectionLabel.contains(it, ignoreCase = true) }
}

/** Where the words of [query] appear in [label], merged and in order, for highlighting a result. */
internal fun chainMetricMatchRanges(query: String, label: String): List<IntRange> {
    val ranges = chainSearchTerms(query).flatMap { term ->
        generateSequence(label.indexOf(term, ignoreCase = true).takeIf { it >= 0 }) { previous ->
            label.indexOf(term, previous + 1, ignoreCase = true).takeIf { it >= 0 }
        }.map { start -> start until start + term.length }.toList()
    }.sortedBy(IntRange::first)
    return ranges.fold(mutableListOf()) { merged, range ->
        val last = merged.lastOrNull()
        if (last != null && range.first <= last.last + 1) {
            merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
        } else {
            merged += range
        }
        merged
    }
}

private fun chainSearchTerms(query: String): List<String> = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
