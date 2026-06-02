package com.thomas.notiguide.domain.store.service

import com.thomas.notiguide.domain.store.repository.StorePublicIdRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Component
class SlugGracePurgeScheduler(
    private val storePublicIdRepository: StorePublicIdRepository,
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    @Scheduled(fixedDelayString = $$"${store.slug.grace-delete-task-delay-ms:21600000}")
    fun purgeExpiredGrace() = runBlocking {
        try {
            val purged = storePublicIdRepository.deleteExpiredGrace(OffsetDateTime.now(ZoneOffset.UTC))
            if (purged > 0) {
                log.info("Slug grace purge: deleted {} expired slug(s)", purged)
            }
        } catch (ex: Exception) {
            log.warn("Slug grace purge failed", ex)
        }
    }
}
