package dev.distillpod.android.sync

internal fun shouldRetrySubscriptionWork(completed:Boolean,binding:SyncBindingEntity?,signedIn:Boolean,hasPending:Boolean):Boolean = binding?.enabled==true && signedIn && (hasPending || (!completed && binding.status!="conflict"))
