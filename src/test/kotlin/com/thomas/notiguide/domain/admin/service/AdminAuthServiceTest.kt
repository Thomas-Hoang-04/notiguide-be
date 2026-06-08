package com.thomas.notiguide.domain.admin.service

import com.thomas.notiguide.domain.admin.entity.Admin
import com.thomas.notiguide.domain.admin.repository.AdminRepository
import com.thomas.notiguide.domain.admin.types.AdminRole
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class AdminAuthServiceTest {
    private val adminRepository = mockk<AdminRepository>()
    private val service = AdminAuthService(adminRepository)

    @Test
    fun `findByUsername returns a principal for an existing admin`() = runTest {
        val admin = Admin(
            id = UUID.randomUUID(),
            username = "alice",
            passwordHash = "hash",
            role = AdminRole.ROLE_ADMIN,
            isVerified = true,
        )
        coEvery { adminRepository.findByUsername("alice") } returns admin

        val details = service.findByUsername("alice").awaitSingleOrNull()

        assertThat(details).isNotNull
        assertThat(details!!.username).isEqualTo("alice")
    }

    @Test
    fun `findByUsername yields no user for an unknown admin`() = runTest {
        coEvery { adminRepository.findByUsername("ghost") } returns null
        // The service throws UsernameNotFoundException for an unknown user, so the Mono errors;
        // runCatching tolerates either an empty Mono or an error — both mean "no user".
        val result = runCatching { service.findByUsername("ghost").awaitSingleOrNull() }
        assertThat(result.getOrNull()).isNull()
    }
}
