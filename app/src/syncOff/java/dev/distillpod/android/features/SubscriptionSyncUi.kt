package dev.distillpod.android.features

import androidx.compose.runtime.Composable

/** A build without library sync has no account section; Settings › Sync shows automatic backup only. */
@Suppress("UNUSED_PARAMETER")
@Composable internal fun SubscriptionSyncSettings(vm: AppViewModel) {}
