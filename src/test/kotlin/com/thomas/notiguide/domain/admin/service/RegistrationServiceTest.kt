package com.thomas.notiguide.domain.admin.service

import com.thomas.notiguide.core.exception.HttpException
import com.thomas.notiguide.domain.admin.repository.AdminRepository
import com.thomas.notiguide.domain.admin.request.RegisterRequest
import com.thomas.notiguide.domain.admin.types.RegisterMode
import com.thomas.notiguide.domain.admin.types.RegisterStatus
import com.thomas.notiguide.domain.organization.entity.Organization
import com.thomas.notiguide.domain.organization.repository.OrganizationRepository
import com.thomas.notiguide.domain.store.entity.Store
import com.thomas.notiguide.domain.store.repository.StoreRepository
import com.thomas.notiguide.domain.store.service.StoreService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.UUID

class RegistrationServiceTest {
    private val adminRepository = mockk<AdminRepository> {
        coEvery { existsByUsername(any()) } returns false
    }
    private val organizationRepository = mockk<OrganizationRepository>()
    private val storeRepository = mockk<StoreRepository>()
    private val storeService = mockk<StoreService>()
    private val passwordEncoder = mockk<PasswordEncoder> {
        every { encode(any()) } returns "hashed"
    }
    private val joinRequestService = mockk<JoinRequestService> {
        coEvery { usernameReserved(any()) } returns false
        coEvery { create(any(), any(), any(), any()) } returns "req-1"
    }
    private val inviteLinkService = mockk<InviteLinkService> {
        coEvery { recordUse(any(), any(), any(), any()) } returns Unit
    }
    private val service = RegistrationService(
        adminRepository,
        organizationRepository,
        storeRepository,
        storeService,
        passwordEncoder,
        joinRequestService,
        inviteLinkService
    )

    private val orgId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val storeId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun joinRequest(inviteToken: String? = null) = RegisterRequest(
        mode = RegisterMode.JOIN,
        username = "newjoiner",
        password = "Sup3rSecret!1",
        inviteToken = inviteToken
    )

    private fun orgTarget() = InviteLinkService.InviteTarget(
        targetType = JoinRequestService.TargetType.ORG,
        targetId = orgId.toString(),
        expiresAt = "2026-06-18T10:00:00Z"
    )

    @Test
    fun `join via a valid org invite token files a pending request and records the use`() = runTest {
        coEvery { inviteLinkService.resolve("i_tok") } returns orgTarget()
        coEvery { organizationRepository.findById(orgId) } returns
            Organization(id = orgId, name = "Acme")

        val response = service.register(joinRequest(inviteToken = "i_tok"))

        assertThat(response.status).isEqualTo(RegisterStatus.PENDING)
        assertThat(response.targetType).isEqualTo("ORG")
        coVerify {
            joinRequestService.create("newjoiner", "hashed", JoinRequestService.TargetType.ORG, orgId)
        }
        coVerify {
            inviteLinkService.recordUse(JoinRequestService.TargetType.ORG, orgId, "newjoiner", "i_tok")
        }
    }

    @Test
    fun `join via a valid store invite token files a pending store request`() = runTest {
        coEvery { inviteLinkService.resolve("i_tok") } returns InviteLinkService.InviteTarget(
            targetType = JoinRequestService.TargetType.STORE,
            targetId = storeId.toString(),
            expiresAt = "2026-06-18T10:00:00Z"
        )
        coEvery { storeRepository.findById(storeId) } returns
            Store(id = storeId, orgId = null, name = "Indie Store")

        val response = service.register(joinRequest(inviteToken = "i_tok"))

        assertThat(response.status).isEqualTo(RegisterStatus.PENDING)
        assertThat(response.targetType).isEqualTo("STORE")
        coVerify {
            joinRequestService.create("newjoiner", "hashed", JoinRequestService.TargetType.STORE, storeId)
        }
        coVerify {
            inviteLinkService.recordUse(JoinRequestService.TargetType.STORE, storeId, "newjoiner", "i_tok")
        }
    }

    @Test
    fun `join via an unknown or expired invite token is rejected with 400`() = runTest {
        coEvery { inviteLinkService.resolve("i_dead") } returns null

        val ex = runCatching { service.register(joinRequest(inviteToken = "i_dead")) }.exceptionOrNull()

        assertThat(ex).isInstanceOf(HttpException::class.java)
        assertThat((ex as HttpException).status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(ex.message).contains("invite link")
    }

    @Test
    fun `join without an invite token is rejected with 400`() = runTest {
        val ex = runCatching { service.register(joinRequest()) }.exceptionOrNull()

        assertThat(ex).isInstanceOf(HttpException::class.java)
        assertThat((ex as HttpException).status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(ex.message).isEqualTo("An invite link is required")
    }

    @Test
    fun `join via a store invite token whose store became org-owned is rejected with 400`() = runTest {
        coEvery { inviteLinkService.resolve("i_tok") } returns InviteLinkService.InviteTarget(
            targetType = JoinRequestService.TargetType.STORE,
            targetId = storeId.toString(),
            expiresAt = "2026-06-18T10:00:00Z"
        )
        coEvery { storeRepository.findById(storeId) } returns
            Store(id = storeId, orgId = orgId, name = "Captured Store")

        val ex = runCatching { service.register(joinRequest(inviteToken = "i_tok")) }.exceptionOrNull()

        assertThat(ex).isInstanceOf(HttpException::class.java)
        assertThat((ex as HttpException).status).isEqualTo(HttpStatus.BAD_REQUEST)
        coVerify(exactly = 0) { joinRequestService.create(any(), any(), any(), any()) }
    }

    @Test
    fun `join via an invite token whose target was deleted is rejected with 400`() = runTest {
        coEvery { inviteLinkService.resolve("i_tok") } returns orgTarget()
        coEvery { organizationRepository.findById(orgId) } returns null

        val ex = runCatching { service.register(joinRequest(inviteToken = "i_tok")) }.exceptionOrNull()

        assertThat(ex).isInstanceOf(HttpException::class.java)
        assertThat((ex as HttpException).status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `a recordUse failure does not fail the registration`() = runTest {
        coEvery { inviteLinkService.resolve("i_tok") } returns orgTarget()
        coEvery { organizationRepository.findById(orgId) } returns
            Organization(id = orgId, name = "Acme")
        coEvery { inviteLinkService.recordUse(any(), any(), any(), any()) } throws
            RuntimeException("redis down")

        val response = service.register(joinRequest(inviteToken = "i_tok"))

        assertThat(response.status).isEqualTo(RegisterStatus.PENDING)
    }

}
