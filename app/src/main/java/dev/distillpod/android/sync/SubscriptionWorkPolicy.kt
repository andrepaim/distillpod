package dev.distillpod.android.sync

internal fun shouldRetrySubscriptionWork(completed:Boolean,binding:SyncBindingEntity?,signedIn:Boolean,hasPending:Boolean):Boolean = binding?.enabled==true && signedIn && binding.status!="upgrade_required" && (hasPending || (!completed && binding.status!="conflict"))
