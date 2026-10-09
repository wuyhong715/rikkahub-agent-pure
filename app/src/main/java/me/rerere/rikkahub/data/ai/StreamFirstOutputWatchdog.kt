package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Aborts [this] with [timeoutFailure] if it does not emit its FIRST element within [timeoutMs].
 *
 * Why this exists: a streamed model reply rides one long-lived socket. When that socket dies
 * silently - a backgrounded app changing networks, an OEM freezing the process, a provider
 * dropping the connection without a FIN, or a gateway holding a request open with zero bytes -
 * nothing is emitted and nothing fails. The chat just counts seconds on an empty "thinking"
 * bubble, and with the shared client's 10-minute `readTimeout` (which only bounds the gap
 * between *bytes*) that silence can last a full ten minutes before the transport finally gives
 * up. Users report exactly this: "thinking" with no content for minutes, while re-sending the
 * message immediately streams fine - proof the request itself is fine and the socket was dead.
 *
 * This turns that silence into an ordinary stream failure at the first-element stage, so the
 * caller's existing retry policy re-issues the request on a fresh connection (see
 * `shouldRetryGenerationStreamFailure`, which allows a retry precisely while no meaningful
 * output has arrived).
 *
 * Deliberately disarms on the FIRST element of any kind, not the first *meaningful* one:
 * providers commonly open the stream with a role-only delta (or an empty heartbeat) before the
 * model has produced anything, and a genuine long prefill must never be aborted. Only a stream
 * that has delivered nothing at all is treated as dead. A stream that dies *after* its first
 * element is left to the transport (`pingInterval` / `readTimeout`) and to the post-output
 * "continue" logic, which can resume without duplicating visible text.
 *
 * @param timeoutMs how long to wait for the first element; **`<= 0` disables the watchdog**
 *   and returns [this] unchanged, so a caller can turn it off without branching.
 * @param timeoutFailure builds the failure thrown into the stream; called on the watchdog
 *   coroutine, so it must not capture anything mutable from the collecting coroutine.
 */
internal fun <T> Flow<T>.abortIfSilentBeforeFirstElement(
    timeoutMs: Long,
    timeoutFailure: () -> Throwable,
): Flow<T> {
    if (timeoutMs <= 0L) return this
    return channelFlow {
        val producer = this
        val watchdog = launch {
            delay(timeoutMs)
            // Closing the producer channel with a cause surfaces it to the collector as a
            // thrown exception, which is what the retry policy downstream expects. Closing also
            // lets the flow complete and take the still-suspended upstream collection with it.
            producer.close(timeoutFailure())
        }
        try {
            collect { value ->
                // First element arrived: the socket is alive, stop watching.
                watchdog.cancel()
                send(value)
            }
        } finally {
            watchdog.cancel()
        }
    }
}
