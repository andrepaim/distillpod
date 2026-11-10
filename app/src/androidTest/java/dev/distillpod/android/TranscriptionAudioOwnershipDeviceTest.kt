package dev.distillpod.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranscriptionAudioOwnershipDeviceTest {
    private fun isolatedContext(): Context {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val offline = context.packageName == "dev.distillpod.android.validation.retention60"
        check(offline || Build.HARDWARE.contains("ranchu") || Build.FINGERPRINT.contains("generic")) {
            "Physical execution requires the isolated offline retention validation app"
        }
        if (offline) for (permission in listOf(Manifest.permission.INTERNET, Manifest.permission.RECORD_AUDIO)) {
            check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED)
        }
        return context
    }

    @Test fun protectsPausedReader() = TranscriptionAudioOwnershipFixture.protectsPausedReader(isolatedContext())
    @Test fun protectsSelectedOrphan() = TranscriptionAudioOwnershipFixture.protectsSelectedOrphan(isolatedContext())
    @Test fun nestedCancellationReleasesOnlyItsOwnReader() =
        TranscriptionAudioOwnershipFixture.nestedCancellationReleasesOnlyItsOwnReader(isolatedContext())
    @Test fun failureIsPreservedAndReleasesReader() =
        TranscriptionAudioOwnershipFixture.failureIsPreservedAndReleasesReader(isolatedContext())
}
