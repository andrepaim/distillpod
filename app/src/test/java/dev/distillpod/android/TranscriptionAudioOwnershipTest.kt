package dev.distillpod.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptionAudioOwnershipTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun protectsPausedReader() = TranscriptionAudioOwnershipFixture.protectsPausedReader(context)
    @Test fun protectsSelectedOrphan() = TranscriptionAudioOwnershipFixture.protectsSelectedOrphan(context)
    @Test fun nestedCancellationReleasesOnlyItsOwnReader() =
        TranscriptionAudioOwnershipFixture.nestedCancellationReleasesOnlyItsOwnReader(context)
    @Test fun failureIsPreservedAndReleasesReader() = TranscriptionAudioOwnershipFixture.failureIsPreservedAndReleasesReader(context)
}
