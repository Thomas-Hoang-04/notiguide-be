package com.thomas.notiguide.domain.queue.service

import com.thomas.notiguide.domain.queue.request.ReconcileOfflineRequest
import com.thomas.notiguide.domain.queue.response.ReconcileItemResult
import com.thomas.notiguide.domain.queue.response.ReconcileOfflineResponse
import com.thomas.notiguide.shared.principal.AdminPrincipal
import com.thomas.notiguide.shared.principal.StoreAccessService
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class OfflineReconciliationService(
    private val queueService: QueueService,
    private val storeAccess: StoreAccessService
) {
    suspend fun reconcile(
        storeId: UUID,
        request: ReconcileOfflineRequest,
        principal: AdminPrincipal
    ): ReconcileOfflineResponse {
        storeAccess.requireStoreAccess(principal, storeId)
        val results = request.transitions.map { transition ->
            val result = runCatching {
                queueService.reconcileTerminalTransition(storeId, transition.ticketId, transition.action)
            }.getOrDefault("gone")
            ReconcileItemResult(transition.ticketId, result)
        }
        return ReconcileOfflineResponse(results)
    }
}
