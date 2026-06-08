package com.thomas.notiguide.domain.admin.service

import com.thomas.notiguide.core.config.JWTProperties
import com.thomas.notiguide.domain.admin.entity.AdminSession
import com.thomas.notiguide.domain.admin.repository.AdminSessionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.ReactiveRedisTemplate
import java.util.UUID

class SessionServiceTest {
    private val sessionRepository = mockk<AdminSessionRepository>()
    private val redis = mockk<ReactiveRedisTemplate<String, String>>()
    private val service = SessionService(
        sessionRepository,
        redis,
        JWTProperties(900, 604800, "k", "", "k"),
    )

    @Test
    fun `createSession persists and returns the session`() = runTest {
        val adminId = UUID.randomUUID()
        val saved = AdminSession(
            id = UUID.randomUUID(),
            adminId = adminId,
            tokenHash = "hash",
            ipAddress = "1.2.3.4",
            userAgent = "UA",
        )
        coEvery { sessionRepository.save(any()) } returns saved

        val result = service.createSession(adminId, "hash", "1.2.3.4", "UA")

        assertThat(result.adminId).isEqualTo(adminId)
        assertThat(result.tokenHash).isEqualTo("hash")
        coVerify(exactly = 1) { sessionRepository.save(any()) }
    }
}
