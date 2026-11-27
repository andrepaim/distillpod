package dev.distillpod.android.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.distillpod.android.ui.theme.TextButton

/** The hosted privacy policy; the same address is given on the Google Play listing (#325). */
internal const val PrivacyPolicyUrl = "https://andrepaim.github.io/distillpod/privacy.html"

/**
 * Opens the hosted privacy policy, styled like [LearnMoreLink] so it sits beside the consent text it qualifies.
 * [onOpen] is normally [AppViewModel.openLink], which explains a phone with no browser instead of crashing.
 */
@Composable internal fun PrivacyPolicyLink(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = { onOpen(PrivacyPolicyUrl) }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        modifier = modifier) { Text("Privacy policy") }
}
