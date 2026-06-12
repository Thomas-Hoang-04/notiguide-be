package com.thomas.notiguide.domain.organization.repository

import com.thomas.notiguide.domain.organization.entity.Organization
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface OrganizationRepository : CoroutineCrudRepository<Organization, UUID>
