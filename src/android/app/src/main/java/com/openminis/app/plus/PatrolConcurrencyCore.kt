package com.openminis.app.plus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Bounded parallel work that returns input order and isolates each item's failure. */
internal suspend fun <T, R> boundedOrderedMap(
    items: List<T>,
    parallelism: Int,
    action: suspend (T) -> R,
): List<Result<R>> {
    require(parallelism > 0)
    val gate = Semaphore(parallelism)
    return coroutineScope {
        items.map { item ->
            async(Dispatchers.IO) { gate.withPermit { captureFailure(action, item) } }
        }.awaitAll()
    }
}

private suspend fun <T, R> captureFailure(action: suspend (T) -> R, item: T): Result<R> = try {
    Result.success(action(item))
} catch (cancel: CancellationException) {
    throw cancel
} catch (failure: Exception) {
    Result.failure(failure)
}
