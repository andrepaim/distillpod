package dev.distillpod.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.distillpod.android.ai.AccountState
import dev.distillpod.android.features.ChatGptDataUse
import dev.distillpod.android.features.OnboardingWelcomeContent
import dev.distillpod.android.ui.theme.DistillTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingWelcomeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `at large text cancellation keeps connection available and success promotes listening`() {
        val account = mutableStateOf(AccountState())
        val actions = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                DistillTheme {
                    Box(Modifier.width(360.dp)) {
                        OnboardingWelcomeContent(
                            account = account.value,
                            syncConfigured = false,
                            onConnect = { actions += "connect" },
                            onFindPodcast = { actions += "listen" },
                            onRecover = { actions += "recover" },
                            onSkip = { actions += "skip" },
                        )
                    }
                }
            }
        }
        val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
        compose.onNode(hasText("Turn a podcast moment into a takeaway") and heading)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Example").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("ChatGPT takeaway") and heading).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(ChatGptDataUse).performScrollTo().assertIsDisplayed()

        val primary = compose.onNodeWithTag("onboarding-primary-action")
        primary.assertTextEquals("Connect ChatGPT").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { account.value = AccountState(message = "Sign-in cancelled.") }

        primary.assertTextEquals("Connect ChatGPT").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ChatGPT connected").assertDoesNotExist()
        compose.onNodeWithText("Find my first podcast").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Bring back my library").performScrollTo().assertIsDisplayed().performClick()

        compose.runOnIdle { account.value = AccountState(connected = true) }
        compose.onNodeWithText("ChatGPT connected").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connect ChatGPT").assertDoesNotExist()
        primary.assertTextEquals("Find my first podcast").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Bring back my library").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Skip setup").performScrollTo().assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(listOf("connect", "listen", "recover", "listen", "recover", "skip"), actions) }
    }
}
