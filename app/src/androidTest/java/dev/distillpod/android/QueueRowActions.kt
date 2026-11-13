package dev.distillpod.android

import android.app.UiAutomation
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry

/** Labels of a queue row's custom accessibility actions, from Compose semantics. */
internal fun ComposeTestRule.queueRowActions(episodeId:String):List<String> {
    scrollQueueTo(hasTestTag("queue-row-$episodeId"))
    return onNodeWithTag("queue-row-$episodeId").fetchSemanticsNode().config
        .getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }
}

/** Scrolls the queue to [matcher], waiting for the list to render the controller's latest state. */
internal fun ComposeTestRule.scrollQueueTo(matcher:SemanticsMatcher) {
    runCatching { waitUntil(5000) { runCatching { onNodeWithTag("queue-list").performScrollToNode(matcher) }.isSuccess } }
    onNodeWithTag("queue-list").performScrollToNode(matcher)
}

/** Waits for displayed state to reach [expected] TalkBack actions, then asserts them. */
internal fun ComposeTestRule.assertQueueRowActions(episodeId:String,expected:List<String>) {
    runCatching { waitUntil(5000) { runCatching { queueRowActions(episodeId) }.getOrNull()==expected } }
    org.junit.Assert.assertEquals(expected,queueRowActions(episodeId))
}

/** Opens a row's overflow menu, requires [disabled] items to be unavailable, then selects [label]. */
internal fun ComposeTestRule.selectQueueMenuAction(title:String,label:String,disabled:List<String> = emptyList()) {
    scrollQueueTo(hasContentDescription("Queue actions for $title"))
    onNodeWithContentDescription("Queue actions for $title").performClick()
    disabled.forEach { onNodeWithText(it).assertIsNotEnabled() }
    onNodeWithText(label).assertIsEnabled().performClick()
    waitUntil(5000) { onAllNodesWithText(label).fetchSemanticsNodes().isEmpty() }
}

/**
 * Performs a row's custom action through the platform accessibility tree, as TalkBack does.
 * Only queue rows offer "Remove from queue", so the row is the action-bearing node whose
 * own or descendant text contains [title].
 */
internal fun ComposeTestRule.performQueueAccessibilityAction(episodeId:String,title:String,label:String) {
    scrollQueueTo(hasTestTag("queue-row-$episodeId"))
    waitForIdle()
    // Never suppress the device's own accessibility services (such as an owner's TalkBack).
    val automation=InstrumentationRegistry.getInstrumentation()
        .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    fun AccessibilityNodeInfo.texts():List<String> =
        listOfNotNull(text?.toString(),contentDescription?.toString()) +
            (0 until childCount).flatMap { getChild(it)?.texts().orEmpty() }
    fun AccessibilityNodeInfo.rows():List<AccessibilityNodeInfo> =
        (if(actionList.any { it.label=="Remove from queue" }) listOf(this) else emptyList()) +
            (0 until childCount).flatMap { getChild(it)?.rows().orEmpty() }
    val deadline=SystemClock.uptimeMillis()+5000
    var seen=emptyList<List<String>>()
    while(true) {
        val rows=automation.rootInActiveWindow?.rows().orEmpty()
        seen=rows.map { row -> row.actionList.mapNotNull { it.label?.toString() } }
        val row=rows.filter { candidate -> candidate.texts().any { title in it } }
        val action=row.singleOrNull()?.actionList?.singleOrNull { it.label==label }
        if(action!=null) {
            check(row.single().performAction(action.id)) { "Accessibility action '$label' was rejected" }
            waitForIdle()
            return
        }
        check(SystemClock.uptimeMillis()<deadline) { "No accessible '$label' action for $title; row actions $seen" }
        SystemClock.sleep(100)
    }
}
