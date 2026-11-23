package dev.distillpod.android

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import dev.distillpod.android.features.FirstRunTestAccess
import dev.distillpod.android.sync.SubscriptionCloudAccess

/** A developer emulator may have cached real credentials: never auto-sync them during tests. */
class IsolatedTestRunner:AndroidJUnitRunner() {
    override fun newApplication(cl:ClassLoader,className:String,context:Context):Application {
        SubscriptionCloudAccess.instrumentation=true
        FirstRunTestAccess.bypass = true
        return super.newApplication(cl,className,context)
    }

    override fun onCreate(arguments: Bundle) {
        FirstRunTestAccess.bypass = arguments.getString("firstRunOnboarding") != "true"
        super.onCreate(arguments)
    }
}
