package io.github.sumirenokai.vesqen.library

/**
 * Private data kept outside the catalog database but bound to a catalog track ID, such as imported
 * lyrics (#169). When two rows merge, [into] keeps its ID and [from] is deleted: the #139 choice,
 * the #164 folder reattachment and the legacy MediaStore re-key. The survivor keeps its own data;
 * [from]'s moves over only when [into] has none (owner decision 7).
 *
 * [into]'s copy is made inside the merging transaction, before it commits, so a crash between the
 * commit and the clean-up never loses data on the row the user keeps.
 */
internal interface TrackMergeFollower {
    /** Inside the merging transaction, as the rows merge. */
    fun prepareMerge(from: Long, into: Long)

    /** The merge committed: [from] no longer exists. */
    fun mergeCommitted(from: Long, into: Long)

    /**
     * A transaction committed [merges], each (from, into) in the order they merged: one call for all
     * of them (#191 A4).
     */
    fun mergesCommitted(merges: List<Pair<Long, Long>>) = merges.forEach { (from, into) -> mergeCommitted(from, into) }

    /** The merge rolled back: undoes what [prepareMerge] did. */
    fun mergeRolledBack(from: Long, into: Long)

    companion object {
        val None: TrackMergeFollower = object : TrackMergeFollower {
            override fun prepareMerge(from: Long, into: Long) = Unit

            override fun mergeCommitted(from: Long, into: Long) = Unit

            override fun mergeRolledBack(from: Long, into: Long) = Unit
        }
    }
}

/** Several followers of the same merges, each told in turn: imported lyrics and the playback queue. */
internal class TrackMergeFollowers(private vararg val followers: TrackMergeFollower) : TrackMergeFollower {
    override fun prepareMerge(from: Long, into: Long) = followers.forEach { it.prepareMerge(from, into) }

    override fun mergeCommitted(from: Long, into: Long) = followers.forEach { it.mergeCommitted(from, into) }

    override fun mergesCommitted(merges: List<Pair<Long, Long>>) = followers.forEach { it.mergesCommitted(merges) }

    override fun mergeRolledBack(from: Long, into: Long) = followers.forEach { it.mergeRolledBack(from, into) }
}

/** Every catalog row's ID, hidden and displaced rows included, read for the lyrics clean-up (#169). */
class CatalogTrackIds(
    val trackIds: Set<Long>,
    /** IDs are never reused (AUTOINCREMENT), so a row inserted after this read has a larger ID. */
    val largestAssignedId: Long,
)
