package dev.distillpod.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

internal class ShowsGridActions(
    private val rule: ComposeContentTestRule,
    private val context: () -> String,
) {
    fun scrollToKey(key: Any) {
        rule.onNodeWithTag("shows-grid").performScrollToKey(key)
    }

    fun scrollToIndex(index: Int) {
        rule.onNodeWithTag("shows-grid").performScrollToIndex(index)
    }

    fun scrollHalfPage() {
        val grid = rule.onNodeWithTag("shows-grid")
        val step = grid.fetchSemanticsNode().boundsInRoot.height / 2
        grid.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, step) }
    }
}
