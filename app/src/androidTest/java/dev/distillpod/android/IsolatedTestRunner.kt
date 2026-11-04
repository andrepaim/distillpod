package dev.distillpod.android

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dev.distillpod.android.sync.SubscriptionCloudAccess

/** A developer emulator may have cached real credentials: never auto-sync them during tests. */
class IsolatedTestRunner:AndroidJUnitRunner() {
    override fun newApplication(cl:ClassLoader,className:String,context:Context):Application {
        SubscriptionCloudAccess.instrumentation=true
        return super.newApplication(cl,className,context)
    }
}
