package org.photolibrary.app.feasibility

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.library.FeedbackFeatures
import org.photolibrary.app.library.FeedbackRanking
import org.photolibrary.app.library.PhotoOrigin
import org.photolibrary.app.library.ResultJudgment
import org.photolibrary.app.library.SelectedPhoto

@RunWith(AndroidJUnit4::class)
class FeedbackRankingTest {
    @Test
    fun localRatingsRerankAndHideOnlyForTheSamePromptAndPhotoRevision() {
        val neutral = photo("neutral")
        val relevant = photo("relevant")
        val wrong = photo("wrong")
        val current = listOf(neutral, relevant, wrong)
        val ratings = listOf(
            judgment("dog on a beach", relevant, positive = true, baseRank = 1),
            judgment("dog on a beach", wrong, positive = false, baseRank = 2),
        )

        val samePrompt = FeedbackRanking(ratings, "Dog on a beach", null)
            .ranked(current, emptyMap()) { 0 }
        val otherPrompt = FeedbackRanking(ratings, "red car", null)
            .ranked(current, emptyMap()) { 0 }

        assertEquals(listOf(relevant, neutral), samePrompt)
        assertEquals(current, otherPrompt)
    }

    @Test
    fun editsDoNotCarryOldRatingsToChangedPhotoRevisions() {
        val original = photo("changed", revision = "revision-1")
        val edited = photo("changed", revision = "revision-2")
        val other = photo("other")
        val rating = judgment("receipt", original, positive = false, baseRank = 0)

        val ranked = FeedbackRanking(listOf(rating), "receipt", null)
            .ranked(listOf(edited, other), emptyMap()) { 0 }

        assertEquals(listOf(edited, other), ranked)
    }

    private fun judgment(query: String, photo: SelectedPhoto, positive: Boolean, baseRank: Int) =
        ResultJudgment.create(
            query,
            photo,
            positive,
            FeedbackFeatures.from(query, photo, baseRank, 0, .7f),
        )

    private fun photo(name: String, revision: String = "revision-$name") = SelectedPhoto(
        Uri.parse("content://feedback-fixture/$name"),
        "$name.jpg",
        PhotoOrigin.GALLERY,
        revision,
    )
}
