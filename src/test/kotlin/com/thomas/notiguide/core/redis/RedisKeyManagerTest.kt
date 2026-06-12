package com.thomas.notiguide.core.redis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class RedisKeyManagerTest {
    private val storeId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val ticketId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val dispatchId = UUID.fromString("33333333-3333-3333-3333-333333333333")

    @Test
    fun `queue key has expected format`() {
        assertThat(RedisKeyManager.queue(storeId)).isEqualTo("store:$storeId:queue")
    }

    @Test
    fun `serving key has expected format`() {
        assertThat(RedisKeyManager.serving(storeId)).isEqualTo("store:$storeId:serving")
    }

    @Test
    fun `ticket key has expected format`() {
        assertThat(RedisKeyManager.ticket(storeId, ticketId)).isEqualTo("ticket:$storeId:$ticketId")
    }

    @Test
    fun `isTicketKey recognises ticket keys only`() {
        assertThat(RedisKeyManager.isTicketKey(RedisKeyManager.ticket(storeId, ticketId))).isTrue()
        assertThat(RedisKeyManager.isTicketKey(RedisKeyManager.queue(storeId))).isFalse()
    }

    @Test
    fun `parseTicketKey round-trips a ticket key`() {
        val key = RedisKeyManager.ticket(storeId, ticketId)
        assertThat(RedisKeyManager.parseTicketKey(key)).isEqualTo(storeId to ticketId)
    }

    @Test
    fun `counter key contains store id and date`() {
        val key = RedisKeyManager.counter(storeId, LocalDate.of(2026, 6, 8))
        assertThat(key).contains(storeId.toString()).contains("2026-06-08")
    }

    @Test
    fun `dispatchPendingAck key has expected format`() {
        assertThat(RedisKeyManager.dispatchPendingAck(dispatchId))
            .isEqualTo("dispatch:pending-ack:$dispatchId")
    }

    @Test
    fun `isPendingAckKey recognises pending-ack keys only`() {
        assertThat(RedisKeyManager.isPendingAckKey(RedisKeyManager.dispatchPendingAck(dispatchId))).isTrue()
        assertThat(RedisKeyManager.isPendingAckKey(RedisKeyManager.dispatchTracking(dispatchId))).isFalse()
        assertThat(RedisKeyManager.isPendingAckKey(RedisKeyManager.ticket(storeId, ticketId))).isFalse()
    }

    @Test
    fun `parsePendingAckKey round-trips a pending-ack key`() {
        val key = RedisKeyManager.dispatchPendingAck(dispatchId)
        assertThat(RedisKeyManager.parsePendingAckKey(key)).isEqualTo(dispatchId)
    }

    @Test
    fun `parsePendingAckKey returns null for a non-uuid suffix`() {
        assertThat(RedisKeyManager.parsePendingAckKey("dispatch:pending-ack:not-a-uuid")).isNull()
    }

    @Test
    fun `parsePendingAckKey returns null for a completely different key`() {
        assertThat(RedisKeyManager.parsePendingAckKey(RedisKeyManager.ticket(storeId, ticketId))).isNull()
    }

    @Test
    fun `invite keys have expected formats`() {
        assertThat(RedisKeyManager.inviteToken("i_abc")).isEqualTo("invite:token:i_abc")
        assertThat(RedisKeyManager.inviteActive("ORG", storeId)).isEqualTo("invite:active:ORG:$storeId")
        assertThat(RedisKeyManager.inviteLock("STORE", storeId)).isEqualTo("invite:lock:STORE:$storeId")
        assertThat(RedisKeyManager.inviteAudit("STORE", storeId)).isEqualTo("invite:audit:STORE:$storeId")
    }
}
