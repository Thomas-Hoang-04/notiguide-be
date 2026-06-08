package com.thomas.notiguide.domain.organization.service

import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.core.tenant.JoinCodeGenerator
import com.thomas.notiguide.domain.organization.dto.OrganizationDto
import com.thomas.notiguide.domain.organization.dto.OrganizationPublicDto
import com.thomas.notiguide.domain.organization.repository.OrganizationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class OrganizationService(
    private val organizationRepository: OrganizationRepository
) {
    @Transactional(readOnly = true)
    suspend fun getOrganization(id: UUID): OrganizationDto =
        organizationRepository.findById(id)?.toDto()
            ?: throw NotFoundException("Organization", "id", id.toString())

    @Transactional(readOnly = true)
    suspend fun getOrganizationPublic(id: UUID): OrganizationPublicDto =
        organizationRepository.findById(id)?.toPublicDto()
            ?: throw NotFoundException("Organization", "id", id.toString())

    @Transactional
    suspend fun rotateJoinCode(id: UUID): OrganizationDto {
        val org = organizationRepository.findById(id)
            ?: throw NotFoundException("Organization", "id", id.toString())
        val newCode = generateUniqueJoinCode()
        return organizationRepository.save(org.copy(joinCode = newCode)).toDto()
    }

    private suspend fun generateUniqueJoinCode(): String {
        repeat(5) {
            val code = JoinCodeGenerator.generate(JoinCodeGenerator.ORG_PREFIX)
            if (!organizationRepository.existsByJoinCode(code)) return code
        }
        throw IllegalStateException("Could not generate a unique organization join code")
    }
}
