package dev.distillpod.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*

/** Opens Now playing's peek sheet from its handle and selects [tab] ("Up next", "Transcript" or "Insights"). */
internal fun SemanticsNodeInteractionsProvider.openPlayerSheet(tab: String) {
    onNodeWithContentDescription("Up next, transcript and insights").performScrollTo().performClick()
    onNode(hasText(tab) and isSelectable()).performClick()
}

/** Dismisses the open player sheet through its own dismiss action, as TalkBack does. */
internal fun SemanticsNodeInteractionsProvider.dismissPlayerSheet() {
    onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss)).performSemanticsAction(SemanticsActions.Dismiss)
}

/** Opens the speed sheet from the transport row's pill and picks a preset. */
internal fun SemanticsNodeInteractionsProvider.choosePlayerSpeed(label: String) {
    onNodeWithContentDescription("Speed and effects", substring = true).performScrollTo().performClick()
    onNode(hasText(label) and isSelectable()).performClick()
    dismissPlayerSheet()
}
