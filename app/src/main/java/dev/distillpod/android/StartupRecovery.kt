package dev.distillpod.android

import android.util.Log
import dev.distillpod.android.diagnostics.Diagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/** One best-effort startup recovery step; [name] is a fixed identifier, never user content. */
internal class StartupStep(val name: String, val run: suspend () -> Unit)

/**
 * Runs the startup chain so that one failing recovery step cannot block the rest or the app's readiness.
 *
 * Only the [prerequisite] (the database every [AppGraph.ready] awaiter reads) can fail readiness. Each
 * recovery step is attempted in order and its failure reported, then [ready] completes, so workers,
 * playback and sync never start on top of recovery still in progress. A failed step leaves only its own
 * work unrecovered until the next start. The [afterReady] steps are isolated the same way.
 */
internal object StartupRecovery {
    const val DATABASE = "database"
    private const val TAG = "StartupRecovery"

    suspend fun run(ready: CompletableDeferred<Unit>, prerequisite: suspend () -> Unit, recovery: List<StartupStep>,
        afterReady: List<StartupStep>, failed: (step: String, error: Exception) -> Unit) {
        try {
            try { prerequisite() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                failed(DATABASE, error)
                ready.completeExceptionally(error)
                return
            }
            recovery.forEach { attempt(it, failed) }
            ready.complete(Unit)
            afterReady.forEach { attempt(it, failed) }
        } catch (cancelled: CancellationException) {
            // A cancelled start must not leave awaiters suspended forever.
            ready.cancel(cancelled)
            throw cancelled
        }
    }

    private suspend fun attempt(step: StartupStep, failed: (String, Exception) -> Unit) {
        try { step.run() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failed(step.name, error) }
    }

    /** Content-free report: diagnostics receive the categorical reason and stack frames, logcat the step name. */
    fun report(diagnostics: Diagnostics, step: String, error: Exception) {
        runCatching { Log.w(TAG, "Startup step $step failed; continuing", error) }
        diagnostics.failure("startup", error)
    }
}
