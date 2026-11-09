package dev.distillpod.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Settled real-service import; pending-write races are covered separately by the shared protocol. */
@RunWith(AndroidJUnit4::class)
class QueueSyncPlaybackDeviceTest {
    @Test fun applyingCloudQueuePreservesCurrentAudioAndDoesNotEchoTheTemporaryActiveItem() =
        CloudQueueOrderingFixture.scenario("settled")
}
