package dev.distillpod.android

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.distillpod.android.data.PodcastEntity
import dev.distillpod.android.features.PublicFeedReview
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PublicFeedReviewDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun unknownFeedsRequireSelectionAndOnlyConfirmedIdsAreShared() {
        var confirmed:Set<String>?=null
        compose.activity.setContent { MaterialTheme {
            PublicFeedReview(listOf(PodcastEntity("private","https://example.test/private-access-path","Private fixture")),emptySet(),true,{confirmed=it},{})
        } }
        compose.onNodeWithContentDescription("Sync Private fixture").assertIsOff()
        compose.onNodeWithContentDescription("Sync Private fixture").performClick()
        compose.onNodeWithText("Continue with Google").performClick()
        compose.runOnIdle { assertEquals(setOf("private"),confirmed) }
    }
    @Test fun cancellingDoesNotAuthorizeAnyFeed() {
        var confirmed=false;var cancelled=false
        compose.activity.setContent { MaterialTheme {PublicFeedReview(emptyList(),emptySet(),true,{confirmed=true},{cancelled=true})} }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertTrue(cancelled);assertFalse(confirmed) }
    }
}
