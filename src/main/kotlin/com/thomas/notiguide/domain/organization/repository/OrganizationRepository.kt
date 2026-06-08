package com.thomas.notiguide.domain.organization.repository

import com.thomas.notiguide.domain.organization.entity.Organization
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface OrganizationRepository : CoroutineCrudRepository<Organization, UUID> {
    @Query("SELECT * FROM organization WHERE join_code = :joinCode")
    suspend fun findByJoinCode(joinCode: String): Organization?

    @Query("SELECT EXISTS(SELECT 1 FROM organization WHERE join_code = :joinCode)")
    suspend fun existsByJoinCode(joinCode: String): Boolean
}
