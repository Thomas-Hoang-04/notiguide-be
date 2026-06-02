package com.thomas.notiguide.core.store

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "store.slug")
data class StoreSlugProperties(
    val gracePeriodDays: Long = 30,
    val graceDeleteTaskDelayMs: Long = 6 * 3600 * 1000
)
