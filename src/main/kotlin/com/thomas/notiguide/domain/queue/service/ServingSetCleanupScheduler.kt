package com.thomas.notiguide.domain.queue.service

import com.thomas.notiguide.domain.store.repository.StoreRepository
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class ServingSetCleanupScheduler(
    private val storeRepository: StoreRepository,
    private val queueService: QueueService
) {
    private val log = LoggerFactory.getLogger(this::class.java)

    @Scheduled(fixedDelayString = $$"${queue.serving-cleanup.fixed-delay-ms:300000}")
    fun cleanupServingSets() = runBlocking {
        storeRepository.findAllActive().collect { store ->
            val storeId = store.id ?: return@collect
            try {
                val cleaned = queueService.cleanupServingSet(storeId)
                if (cleaned > 0) {
                    log.info("Serving set cleanup: store={} cleaned_entries={}", storeId, cleaned)
                }
            } catch (ex: Exception) {
                log.warn("Serving set cleanup failed: store={}", storeId, ex)
            }
        }
    }
}
