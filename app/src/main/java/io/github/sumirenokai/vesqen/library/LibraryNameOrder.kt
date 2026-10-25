package io.github.sumirenokai.vesqen.library

import android.icu.text.AlphabeticIndex
import android.icu.text.CollationKey
import android.icu.text.Collator
import java.util.Locale

/**
 * The order of the Songs index, for every list of names (#167): the groups A–Z, a Chinese name in
 * the group of its pinyin initial, then "#" for names that do not start with a letter. Within a
 * group ICU collation decides, so 陈, 王, 周 follow pinyin and case does not matter. A plain zh
 * Collator alone puts every Chinese name before every Latin one.
 *
 * Make one per sort: each name is measured once, and that cache is not safe to share between threads.
 */
internal class LibraryNameOrder : Comparator<String> {
    private val keys = HashMap<String, NameKey>()

    /** "A" to "Z", or "#" for a name that starts with a digit, a symbol or nothing. */
    fun group(name: String): String = key(name).group

    override fun compare(first: String, second: String): Int {
        val a = key(first)
        val b = key(second)
        return compareValuesBy(a, b, NameKey::groupRank, NameKey::collation)
    }

    private fun key(name: String): NameKey = keys.getOrPut(name) {
        val trimmed = name.trim()
        val label = buckets.getBucket(buckets.getBucketIndex(trimmed))?.label.orEmpty()
        val group = label.takeIf {
            trimmed.firstOrNull()?.isLetter() == true && it.length == 1 && it[0] in 'A'..'Z'
        } ?: "#"
        NameKey(group, collator.getCollationKey(trimmed))
    }

    private class NameKey(val group: String, val collation: CollationKey) {
        /** "#" sorts after "Z". */
        val groupRank: Char = if (group == "#") '[' else group[0]
    }

    /** Built once: the index is immutable and the collator frozen, so every sort and thread shares them. */
    private companion object {
        val buckets: AlphabeticIndex.ImmutableIndex<Any>
        val collator: Collator

        init {
            val alphabet = AlphabeticIndex<Any>(Locale.SIMPLIFIED_CHINESE).addLabels(Locale.ENGLISH)
            buckets = alphabet.buildImmutableIndex()
            collator = alphabet.collator.apply { strength = Collator.SECONDARY }.freeze()
        }
    }
}
