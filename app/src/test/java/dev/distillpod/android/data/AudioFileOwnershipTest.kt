package dev.distillpod.android.data

import org.junit.Assert.*
import org.junit.Test

class AudioFileOwnershipTest {
    @Test fun removalRevokesEarlierSelectionAndAllowsFreshSelectionAfterwards() {
        val ownership = AudioFileOwnership()
        val old = ownership.select("one")!!
        val removal = ownership.reserve(listOf("one"))!!
        assertNull(ownership.acquire(old))
        assertNull(ownership.select("one"))
        removal.close()
        assertNull(ownership.acquire(old))
        ownership.acquire(ownership.select("one")!!)!!.close()
    }

    @Test fun ownerWinsBeforeRemovalAndReleaseAllowsRetry() {
        val ownership = AudioFileOwnership()
        val owner = ownership.acquire(ownership.select("one")!!)!!
        assertTrue(ownership.busy("one"))
        assertNull(ownership.reserve(listOf("one")))
        owner.close()
        assertFalse(ownership.busy("one"))
        ownership.reserve(listOf("one"))!!.close()
    }

    @Test fun removingAnotherCopyPreservesPreparedSelections() {
        val ownership = AudioFileOwnership()
        val prepared = ownership.select("one")!!
        ownership.reserve(listOf("two"))!!.close()
        val owner = ownership.acquire(prepared)!!
        assertNull(ownership.reserve(listOf("one")))
        owner.close()
    }

    @Test fun duplicateOwnersAndRepeatedCloseKeepAccurateLifetime() {
        val ownership = AudioFileOwnership()
        val selection = ownership.select("one")!!
        val first = ownership.acquire(selection)!!
        val second = ownership.acquire(selection)!!
        first.close()
        first.close()
        assertNull(ownership.reserve(listOf("one")))
        second.close()
        ownership.reserve(listOf("one"))!!.close()
    }

    @Test fun failedBatchReservationDoesNotRevokeAnySelection() {
        val ownership = AudioFileOwnership()
        val one = ownership.select("one")!!
        val two = ownership.select("two")!!
        val second = ownership.acquire(two)!!
        assertNull(ownership.reserve(listOf("one", "two")))
        val first = ownership.acquire(one)!!
        second.close()
        first.close()
        ownership.reserve(listOf("one", "two"))!!.close()
    }

    @Test fun reservationIsExclusiveAndCloseIsIdempotent() {
        val ownership = AudioFileOwnership()
        val removal = ownership.reserve(listOf("one", "one"))!!
        assertTrue(ownership.busy("one"))
        assertNull(ownership.reserve(listOf("one")))
        removal.close()
        removal.close()
        val newer = ownership.reserve(listOf("one"))!!
        removal.close()
        assertNull(ownership.reserve(listOf("one")))
        newer.close()
        assertFalse(ownership.busy("one"))
    }
}
