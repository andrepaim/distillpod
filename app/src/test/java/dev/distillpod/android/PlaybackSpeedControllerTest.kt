package dev.distillpod.android

import dev.distillpod.android.playback.PlaybackSpeedController
import dev.distillpod.android.playback.SpeedPlayer
import dev.distillpod.core.SpeedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSpeedControllerTest {
    /** Mirrors ExoPlayer: setting a speed notifies the listener synchronously. */
    private class Fixture(prefs: SpeedPreferences? = SpeedPreferences(), var restoring: Boolean = false) {
        val scope = TestScope(UnconfinedTestDispatcher())
        val shows = mutableMapOf("x1" to "x", "x2" to "x", "y1" to "y", "z1" to "z")
        val lookups = mutableMapOf<String, CompletableDeferred<String?>>()
        var stored = prefs ?: SpeedPreferences()
        var saves = 0
        val applied = mutableListOf<Float>()
        lateinit var controller: PlaybackSpeedController
        val player = object : SpeedPlayer {
            override var speed = 1f
                set(value) { field = value; applied += value; controller.speedChanged(value) }
        }
        var deferLookups = false
        init {
            controller = PlaybackSpeedController(scope, player,
                showOf = { id -> if (deferLookups) lookups.getOrPut(id) { CompletableDeferred() }.await() else shows[id] },
                save = { transform -> saves++; stored = transform(stored); controller.preferencesChanged(stored) },
                ignoreChanges = { restoring })
            if (prefs != null) controller.preferencesChanged(prefs)
        }
        /** A speed chosen by the listener through a controller. */
        fun choose(speed: Float) { player.speed = speed; scope.runCurrent() }
        fun item(id: String?) { controller.itemChanged(id); scope.runCurrent() }
    }

    @Test fun `starting a show's episode applies its own speed, other shows use the global speed`() {
        val f = Fixture(SpeedPreferences(global = 1.2f, shows = mapOf("x" to 1.8f)))
        f.item("x1")
        assertEquals(1.8f, f.player.speed, 0f)
        f.item("y1")
        assertEquals(1.2f, f.player.speed, 0f)
        f.item("x2")
        assertEquals(1.8f, f.player.speed, 0f)
        assertEquals("Applying a stored speed is not a new choice", 0, f.saves)
    }

    @Test fun `before any speed is chosen the current speed is kept`() {
        val f = Fixture(SpeedPreferences())
        f.player.speed = 1.5f
        f.applied.clear(); f.saves = 0; f.stored = SpeedPreferences(); f.controller.preferencesChanged(f.stored)
        f.item("y1")
        assertTrue(f.applied.isEmpty())
        assertEquals(1.5f, f.player.speed, 0f)
    }

    @Test fun `a chosen speed is remembered for the show that keeps its own, otherwise globally`() {
        val f = Fixture(SpeedPreferences(global = 1f, shows = mapOf("x" to 1.8f)))
        f.item("x1")
        f.choose(2f)
        assertEquals(SpeedPreferences(1f, mapOf("x" to 2f)), f.stored)
        f.item("y1")
        assertEquals(1f, f.player.speed, 0f)
        f.choose(1.3f)
        assertEquals(SpeedPreferences(1.3f, mapOf("x" to 2f)), f.stored)
        f.item("x2")
        assertEquals(2f, f.player.speed, 0f)
    }

    @Test fun `a choice made while the new item's show is still resolving belongs to the new item and is not overridden`() {
        val f = Fixture(SpeedPreferences(global = 1f, shows = mapOf("x" to 1.8f)))
        f.item("y1")
        f.deferLookups = true
        f.item("x1")
        f.choose(2.2f)
        assertEquals("Nothing is recorded before the show is known", 0, f.saves)
        f.lookups.getValue("x1").complete("x"); f.scope.runCurrent()
        assertEquals(2.2f, f.player.speed, 0f)
        assertEquals(SpeedPreferences(1f, mapOf("x" to 2.2f)), f.stored)
    }

    @Test fun `a stale lookup for an item that is no longer current is ignored`() {
        val f = Fixture(SpeedPreferences(global = 1f, shows = mapOf("x" to 1.8f)))
        f.deferLookups = true
        f.item("x1")
        f.item("y1")
        f.lookups.getValue("y1").complete("y"); f.scope.runCurrent()
        f.lookups.getValue("x1").complete("x"); f.scope.runCurrent()
        assertEquals(1f, f.player.speed, 0f)
        assertFalse("The departed item's show speed is never applied", 1.8f in f.applied)
    }

    @Test fun `forgetting the current show's speed returns to the global speed`() {
        val f = Fixture(SpeedPreferences(global = 1.1f, shows = mapOf("x" to 1.8f)))
        f.item("x1")
        f.stored = f.stored.forget("x"); f.controller.preferencesChanged(f.stored); f.scope.runCurrent()
        assertEquals(1.1f, f.player.speed, 0f)
        // Keeping a speed for the current show changes nothing audible.
        f.applied.clear()
        f.stored = f.stored.keep("x", 1.1f); f.controller.preferencesChanged(f.stored); f.scope.runCurrent()
        assertTrue(f.applied.isEmpty())
    }

    @Test fun `speeds set while restoring are not recorded and the stored speed is applied`() {
        val f = Fixture(SpeedPreferences(global = 1.4f), restoring = true)
        // As in the service: the restore sets its items, then its saved session speed, before the show lookup returns.
        f.deferLookups = true
        f.item("y1")
        f.choose(1.7f)
        f.restoring = false
        f.lookups.getValue("y1").complete("y"); f.scope.runCurrent()
        assertEquals(0, f.saves)
        assertEquals(1.4f, f.player.speed, 0f)
    }

    @Test fun `preferences that load after the item resolve still apply`() {
        val f = Fixture(prefs = null)
        f.item("x1")
        assertTrue(f.applied.isEmpty())
        f.controller.preferencesChanged(SpeedPreferences(global = 1f, shows = mapOf("x" to 1.6f))); f.scope.runCurrent()
        assertEquals(1.6f, f.player.speed, 0f)
    }

    @Test fun `a choice with an empty queue is global`() {
        val f = Fixture(SpeedPreferences(global = 1f, shows = mapOf("x" to 1.8f)))
        f.item("x1")
        f.item(null)
        f.choose(1.5f)
        assertEquals(SpeedPreferences(1.5f, mapOf("x" to 1.8f)), f.stored)
    }

    @Test fun `an unknown show uses the global speed and records globally`() {
        val f = Fixture(SpeedPreferences(global = 1.2f, shows = mapOf("x" to 1.8f)))
        f.item("missing")
        assertEquals(1.2f, f.player.speed, 0f)
        f.choose(1.9f)
        assertEquals(SpeedPreferences(1.9f, mapOf("x" to 1.8f)), f.stored)
    }
}
