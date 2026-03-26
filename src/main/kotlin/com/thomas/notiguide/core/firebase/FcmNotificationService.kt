package com.thomas.notiguide.core.firebase

import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.WebpushConfig
import com.google.firebase.messaging.WebpushFcmOptions
import com.thomas.notiguide.core.redis.RedisKeyManager
import com.thomas.notiguide.core.redis.RedisTTLPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.stereotype.Service
import java.util.UUID

@Service
@ConditionalOnBean(FirebaseMessaging::class)
class FcmNotificationService(
    private val firebaseMessaging: FirebaseMessaging,
    private val redis: ReactiveRedisTemplate<String, String>
) {

    private val log = LoggerFactory.getLogger(this::class.java)

    suspend fun registerToken(storeId: UUID, ticketId: UUID, fcmToken: String) {
        val key = RedisKeyManager.fcmToken(storeId, ticketId)
        redis.opsForValue().set(key, fcmToken, RedisTTLPolicy.FCM_TOKEN).awaitSingle()
        log.debug("FCM token registered: store={} ticket={}", storeId, ticketId)
    }

    suspend fun removeToken(storeId: UUID, ticketId: UUID) {
        val key = RedisKeyManager.fcmToken(storeId, ticketId)
        redis.delete(key).awaitSingle()
    }

    suspend fun sendProactiveAlert(
        storeId: UUID,
        ticketId: UUID,
        ticketNumber: String?,
        position: Long,
        storeName: String
    ) {
        val tokenKey = RedisKeyManager.fcmToken(storeId, ticketId)
        val token = redis.opsForValue().get(tokenKey).awaitSingleOrNull() ?: return

        val ticketKey = RedisKeyManager.ticket(storeId, ticketId)
        val alertFlag = "alert_pos_$position"
        val alreadySent = redis.opsForHash<String, String>()
            .get(ticketKey, alertFlag).awaitSingleOrNull()
        if (alreadySent != null) return

        redis.opsForHash<String, String>()
            .put(ticketKey, alertFlag, "1").awaitSingle()

        val data = mapOf(
            "type" to "POSITION_ALERT",
            "storeId" to storeId.toString(),
            "ticketId" to ticketId.toString(),
            "ticketNumber" to (ticketNumber ?: ""),
            "position" to position.toString(),
            "storeName" to storeName
        )

        val message = Message.builder()
            .setToken(token)
            .putAllData(data)
            .setWebpushConfig(
                WebpushConfig.builder()
                    .setFcmOptions(
                        WebpushFcmOptions.builder()
                            .setLink("/store/$storeId/ticket/$ticketId")
                            .build()
                    )
                    .build()
            )
            .build()

        try {
            withContext(Dispatchers.IO) {
                firebaseMessaging.send(message)
            }
            log.info("Proactive alert sent: store={} ticket={} position={}", storeId, ticketId, position)
        } catch (e: FirebaseMessagingException) {
            log.warn("Proactive alert failed: store={} ticket={} code={}", storeId, ticketId, e.messagingErrorCode)
            if (e.messagingErrorCode?.name in setOf("UNREGISTERED", "INVALID_ARGUMENT")) {
                removeToken(storeId, ticketId)
            }
        }
    }

    suspend fun sendTicketCalledNotification(
        storeId: UUID,
        ticketId: UUID,
        ticketNumber: String?,
        counterId: String?
    ) {
        val key = RedisKeyManager.fcmToken(storeId, ticketId)
        val token = redis.opsForValue().get(key).awaitSingleOrNull() ?: return

        // Data-only message — no Notification block.
        // The service worker constructs localized notification text from the data payload.
        val message = Message.builder()
            .setToken(token)
            .setWebpushConfig(
                WebpushConfig.builder()
                    .setFcmOptions(
                        WebpushFcmOptions.builder()
                            .setLink("/store/$storeId/ticket/$ticketId")
                            .build()
                    )
                    .build()
            )
            .putData("type", "TICKET_CALLED")
            .putData("storeId", storeId.toString())
            .putData("ticketId", ticketId.toString())
            .putData("ticketNumber", ticketNumber ?: "")
            .putData("counterId", counterId ?: "")
            .build()

        try {
            withContext(Dispatchers.IO) {
                firebaseMessaging.send(message)
            }
            log.info("FCM notification sent: store={} ticket={}", storeId, ticketId)
        } catch (e: FirebaseMessagingException) {
            log.warn("FCM send failed: store={} ticket={} code={}", storeId, ticketId, e.messagingErrorCode)
            // Clean up invalid tokens
            if (e.messagingErrorCode?.name in setOf("UNREGISTERED", "INVALID_ARGUMENT")) {
                removeToken(storeId, ticketId)
            }
        }
    }
}
