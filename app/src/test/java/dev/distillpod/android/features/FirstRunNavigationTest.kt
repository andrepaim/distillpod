package dev.distillpod.android.features

import dev.distillpod.core.FirstRunStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class FirstRunNavigationTest {
    @Test fun coldLaunchPreservesExternalDestinationsAboveWelcome() {
        assertEquals(listOf(OnboardingWelcomeRoute, "player", "settings:about"),
            firstRunBackStack(emptyList(), listOf("player", "settings:about", "settings:about"), FirstRunStatus.PENDING))
    }

    @Test fun recreationPreservesTheRecoveryStackAndAppDestinations() {
        val restored = listOf(OnboardingWelcomeRoute, OnboardingRecoveryRoute, "settings:sync", "backup")
        assertEquals(restored, firstRunBackStack(restored, emptyList(), FirstRunStatus.PENDING))
        assertEquals(listOf(OnboardingWelcomeRoute), firstRunBackStack(emptyList(), emptyList(), FirstRunStatus.PENDING))
    }

    @Test fun completionRemovesSetupWhileKeepingAnyNewExternalDestination() {
        val restored = listOf(OnboardingWelcomeRoute, OnboardingRecoveryRoute, "settings:about")
        assertEquals(listOf("search", "settings:about"), firstRunBackStack(restored, emptyList(), FirstRunStatus.DONE, "search"))
        assertEquals(listOf("library", "settings:about"), firstRunBackStack(restored, emptyList(), FirstRunStatus.DONE))
    }

    @Test fun ordinaryLaunchAndRestorationKeepTheirExistingRoutes() {
        assertEquals(listOf("library", "player"), firstRunBackStack(emptyList(), listOf("player"), FirstRunStatus.DONE))
        val restored = listOf("queue", "episode:example")
        assertEquals(restored, firstRunBackStack(restored, emptyList(), FirstRunStatus.DONE))
        assertEquals(listOf("search"), replaceProductRoot(restored, "search"))
    }

    @Test fun changingTabsFromRecoveryKeepsRecoveryAsTheBackDestination() {
        val prior = listOf(OnboardingWelcomeRoute, OnboardingRecoveryRoute, "settings:sync", "backup")
        assertEquals(listOf(OnboardingWelcomeRoute, OnboardingRecoveryRoute, "library"), replaceProductRoot(prior, "library"))
    }
}
