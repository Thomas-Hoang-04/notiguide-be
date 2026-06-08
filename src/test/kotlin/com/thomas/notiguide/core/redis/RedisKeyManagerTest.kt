package com.thomas.notiguide.core.redis

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class RedisKeyManagerTest {
    private val storeId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val ticketId = UUID.fromString("22222222-2222-2222-2222-222222222222")

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
}
