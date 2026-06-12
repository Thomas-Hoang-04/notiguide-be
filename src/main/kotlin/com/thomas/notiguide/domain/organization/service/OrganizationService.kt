package com.thomas.notiguide.domain.organization.service

import com.thomas.notiguide.core.exception.NotFoundException
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
    suspend fun getOrganizationPublic(id: UUID): OrganizationPublicDto =
        organizationRepository.findById(id)?.toPublicDto()
            ?: throw NotFoundException("Organization", "id", id.toString())
}
