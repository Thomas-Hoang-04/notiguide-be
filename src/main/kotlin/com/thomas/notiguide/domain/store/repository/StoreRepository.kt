package com.thomas.notiguide.domain.store.repository

import com.thomas.notiguide.domain.store.entity.Store
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface StoreRepository : CoroutineCrudRepository<Store, UUID>
