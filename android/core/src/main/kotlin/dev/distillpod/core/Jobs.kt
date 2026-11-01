package dev.distillpod.core

enum class JobState { QUEUED, RUNNING, PAUSED, WAITING_FOR_QUOTA, SUCCEEDED, CANCELLED }
class InferenceAttempt(val requestId: String) {
    var state = JobState.QUEUED; private set
    private var buffer = StringBuilder()
    var result: String? = null; private set
    fun start() {
        check(state in setOf(JobState.QUEUED, JobState.PAUSED, JobState.WAITING_FOR_QUOTA))
        buffer = StringBuilder(); state = JobState.RUNNING
    }
    fun append(text: String) { check(state == JobState.RUNNING); buffer.append(text) }
    fun interrupt() { check(state == JobState.RUNNING); state = JobState.PAUSED }
    fun waitForQuota() { check(state == JobState.RUNNING); state = JobState.WAITING_FOR_QUOTA }
    fun cancel() { check(state != JobState.SUCCEEDED); state = JobState.CANCELLED }
    fun complete() { check(state == JobState.RUNNING); result = buffer.toString(); state = JobState.SUCCEEDED }
}
