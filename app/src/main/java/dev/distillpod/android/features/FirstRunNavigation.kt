package dev.distillpod.android.features

import dev.distillpod.core.FirstRunStatus

internal const val OnboardingWelcomeRoute = "onboarding:welcome"
internal const val OnboardingRecoveryRoute = "onboarding:recovery"

internal fun String.isOnboardingRoute(): Boolean = this == OnboardingWelcomeRoute || this == OnboardingRecoveryRoute

/** Restore the task if possible, then open intents received before the startup decision. */
internal fun firstRunBackStack(
    restored: List<String>,
    launchRoutes: List<String>,
    status: FirstRunStatus,
    finishDestination: String? = null,
): List<String> {
    val root = when {
        status == FirstRunStatus.PENDING -> restored.ifEmpty { listOf(OnboardingWelcomeRoute) }
        restored.firstOrNull()?.isOnboardingRoute() == true ->
            listOf(finishDestination ?: "library") + restored.dropWhile(String::isOnboardingRoute)
        else -> restored.ifEmpty { listOf("library") }
    }
    return launchRoutes.fold(root, ::appendProductRoute)
}

internal fun appendProductRoute(stack: List<String>, route: String): List<String> =
    if (stack.lastOrNull() == route) stack else stack + route

internal fun replaceProductRoot(stack: List<String>, route: String): List<String> =
    stack.takeWhile(String::isOnboardingRoute) + route
