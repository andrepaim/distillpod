package dev.distillpod.android

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule

internal class ShowsGridActions(
    private val rule: ComposeContentTestRule,
    private val context: () -> String,
) {
    fun scrollToKey(key: Any) {
        withGrid("scrollToKey") { node ->
            val lookup = node.config.getOrNull(SemanticsProperties.IndexForKey)
                ?: fail("IndexForKey unavailable")
            val index = lookup(key)
            if (index < 0) fail("key unavailable: $key")
            scrollToIndex(node, index)
        }
    }

    fun scrollToIndex(index: Int) {
        withGrid("scrollToIndex") { node -> scrollToIndex(node, index) }
    }

    fun scrollHalfPage() {
        withGrid("scrollHalfPage") { node ->
            val action = node.config.getOrNull(SemanticsActions.ScrollBy)?.action
                ?: fail("ScrollBy unavailable")
            if (!action(0f, node.boundsInRoot.height / 2)) fail("ScrollBy rejected")
        }
    }

    private fun scrollToIndex(node: SemanticsNode, index: Int) {
        if (index < 0) fail("negative index: $index")
        val action = node.config.getOrNull(SemanticsActions.ScrollToIndex)?.action
            ?: fail("ScrollToIndex unavailable")
        if (!action(index)) fail("ScrollToIndex rejected: $index")
    }

    private fun withGrid(operation: String, action: (SemanticsNode) -> Unit) {
        // The collection API avoids Compose1.7.8's unconditional fetchOneOrThrow
        // diagnostic dump. Read properties/bounds and invoke public actions on main.
        val grids = rule.onAllNodesWithTag("shows-grid").fetchSemanticsNodes()
        if (grids.size != 1) fail("$operation gridCount=${grids.size}")
        rule.runOnUiThread { action(grids.single()) }
    }

    private fun fail(reason: String): Nothing = throw AssertionError("Shows grid: $reason ${context()}")
}
