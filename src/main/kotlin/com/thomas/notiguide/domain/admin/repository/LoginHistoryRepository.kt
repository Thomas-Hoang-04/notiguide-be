package com.thomas.notiguide.domain.admin.repository

import com.thomas.notiguide.domain.admin.entity.LoginHistory
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

interface LoginHistoryRepository : CoroutineCrudRepository<LoginHistory, UUID>

