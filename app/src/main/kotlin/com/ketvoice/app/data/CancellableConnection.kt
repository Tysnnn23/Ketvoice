package com.ketvoice.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicReference

/** Cancelling the awaiting source race closes its socket, not just its coroutine. */
internal object CancellableConnection {
    suspend fun <T> use(open: () -> HttpURLConnection, block: (HttpURLConnection) -> T): T = coroutineScope {
        val active = AtomicReference<HttpURLConnection?>()
        val task = async(Dispatchers.IO) {
            ensureActive()
            val connection = open(); active.set(connection)
            try { ensureActive(); block(connection) } finally { connection.disconnect() }
        }
        try { task.await() } finally { active.get()?.disconnect() }
    }
}
