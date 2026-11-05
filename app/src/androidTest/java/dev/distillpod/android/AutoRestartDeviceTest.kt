package dev.distillpod.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Run each phase in a fresh instrumentation process through scripts/android-auto-tests.sh. */
@RunWith(AndroidJUnit4::class)
class AutoRestartDeviceTest {
    @Test fun prepareCheckpoint()=AutoBrowserDeviceTest().prepareRestartCheckpoint()
    @Test fun verifyCheckpoint()=AutoBrowserDeviceTest().verifyRestartCheckpoint()
}
