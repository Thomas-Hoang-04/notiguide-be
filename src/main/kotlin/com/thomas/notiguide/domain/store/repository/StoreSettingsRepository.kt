package com.thomas.notiguide.domain.store.repository

import com.thomas.notiguide.domain.store.entity.StoreSettings
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

interface StoreSettingsRepository : CoroutineCrudRepository<StoreSettings, UUID>
