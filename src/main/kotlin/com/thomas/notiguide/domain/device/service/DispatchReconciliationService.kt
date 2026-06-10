package com.thomas.notiguide.domain.device.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.thomas.notiguide.core.mqtt.MqttPublisher.QueueEventType
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.core.sse.QueueEventBroadcaster
import com.thomas.notiguide.core.sse.QueueSseEvent
import com.thomas.notiguide.domain.device.dto.DispatchTrackingRecord
import com.thomas.notiguide.domain.device.redis.DeviceBusyRecord
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Reconciles dispatches that ended without a successful ack — either rejected by the hub or never
 * acknowledged (the hub died after publishing). Shared by
 * [com.thomas.notiguide.domain.device.listener.TransmitterOperationalListener] (rejection path)
 * and [com.thomas.notiguide.core.redis.RedisKeyExpirationListener] (ack-timeout path).
 */
@Service
@ConditionalOnProperty(prefix = "device.transmitter", name = ["enabled"], havingValue = "true")
class DispatchReconciliationService(
    private val redis: ReactiveRedisTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val queueEventBroadcaster: QueueEventBroadcaster
) {

    private val log = LoggerFactory.getLogger(this::class.java)

    /**
     * Atomically claims a dispatch and marks it failed: releases the device-busy lock (only if it still
     * belongs to this dispatch's ticket) and emits a DEVICE_DISPATCH_FAILED SSE event. Idempotent — only
     * the caller whose DEL removes the tracking record performs the side effects, so a racing ack and
     * timeout can never both fire.
     */
    suspend fun failDispatch(dispatchId: UUID, reason: String) {
        val trackingKey = RedisKeyManager.dispatchTracking(dispatchId)

        val json = runCatching {
            redis.opsForValue().get(trackingKey).awaitSingleOrNull()
        }.getOrElse { ex ->
            log.warn("dispatch_tracking_read_failed dispatch={}", dispatchId, ex)
            return
        } ?: return // already reconciled — idempotent no-op

        val claimed = runCatching {
            redis.delete(trackingKey).awaitSingleOrNull()
        }.getOrElse { ex ->
            log.warn("dispatch_tracking_delete_failed dispatch={}", dispatchId, ex)
            return
        } ?: 0L
        if (claimed == 0L) return // lost the race to a concurrent ack/timeout

        // Best-effort: disarm the timer so it does not fire no-op expiry later.
        runCatching {
            redis.delete(RedisKeyManager.dispatchPendingAck(dispatchId)).awaitSingleOrNull()
        }

        val tracking = runCatching {
            objectMapper.readValue(json, DispatchTrackingRecord::class.java)
        }.getOrElse { ex ->
            log.warn("dispatch_tracking_malformed dispatch={}", dispatchId, ex)
            return
        }

        releaseBusyIfOwned(tracking.deviceId, tracking.ticketId)

        queueEventBroadcaster.broadcast(
            QueueSseEvent(
                type = QueueEventType.DEVICE_DISPATCH_FAILED.name,
                storeId = tracking.storeId,
                ticketId = tracking.ticketId,
                ticketNumber = tracking.ticketNumber,
                reason = reason
            )
        )
        log.warn(
            "dispatch_failed_reconciled dispatch={} reason={} device={}",
            dispatchId, reason, tracking.deviceId
        )
    }

    /**
     * Marks a dispatch as successfully applied: disarms the timer and drops the tracking record so the
     * timeout path cannot fire. Emits nothing and intentionally retains the device-busy lock (the
     * receiver is now serving).
     */
    suspend fun completeDispatch(dispatchId: UUID) {
        // Delete tracking first: it is the claim arbiter, so removing it disarms the timeout path
        // even if the pending-ack delete below fails (an orphaned timer then expires into a no-op).
        runCatching {
            redis.delete(RedisKeyManager.dispatchTracking(dispatchId)).awaitSingleOrNull()
        }.onFailure { ex ->
            log.warn("dispatch_complete_tracking_delete_failed dispatch={}", dispatchId, ex)
        }
        runCatching {
            redis.delete(RedisKeyManager.dispatchPendingAck(dispatchId)).awaitSingleOrNull()
        }.onFailure { ex ->
            log.warn("dispatch_complete_pendingack_delete_failed dispatch={}", dispatchId, ex)
        }
    }

    /**
     * Frees `device:busy:{deviceId}` only when the current busy record's ticket matches [ticketId]. A
     * later dispatch may have re-reserved the same device (e.g., after a STOP+RELEASE); we must not free
     * its lock. Absent busy key => nothing to release.
     */
    private suspend fun releaseBusyIfOwned(deviceId: UUID, ticketId: UUID) {
        runCatching {
            val busyKey = RedisKeyManager.deviceBusy(deviceId)
            val busyJson = redis.opsForValue().get(busyKey).awaitSingleOrNull() ?: return
            val busy = runCatching {
                objectMapper.readValue(busyJson, DeviceBusyRecord::class.java)
            }.getOrElse { ex ->
                log.warn("dispatch_busy_parse_failed device={}", deviceId, ex)
                null
            }
            if (busy == null) return
            if (busy.ticketId == ticketId) {
                redis.delete(busyKey).awaitSingleOrNull()
            } else {
                log.info("dispatch_busy_release_skipped device={} reason=ticket_mismatch", deviceId)
            }
        }.onFailure { ex ->
            log.warn("dispatch_busy_release_failed device={}", deviceId, ex)
        }
    }
}
