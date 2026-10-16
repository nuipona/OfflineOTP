// Compilation-only WorkManager test API shapes. No scheduling or asynchronous work is performed.
package androidx.work

import java.util.concurrent.TimeUnit

class WorkInfo

class WorkFuture<T>(private val value: T) {
    fun get(timeout: Long, unit: TimeUnit): T = value
}

class Operation {
    fun getResult(): WorkFuture<Void?> = WorkFuture(null)
}
