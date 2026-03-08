package com.thomas.notiguide.domain.queue.controller

import com.thomas.notiguide.domain.queue.dto.NextTicketResponse
import com.thomas.notiguide.domain.queue.dto.TicketStatusResponse
import com.thomas.notiguide.domain.queue.service.QueueService
import com.thomas.notiguide.domain.queue.types.CallNextResult
import com.thomas.notiguide.shared.principal.AdminPrincipal
import com.thomas.notiguide.shared.principal.StoreAccessUtil
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/queue/admin/{storeId}")
class QueueAdminController(
    private val queueService: QueueService
) {

    @GetMapping("/tickets/{ticketId}")
    suspend fun getTicketStatus(
        @PathVariable storeId: UUID,
        @PathVariable ticketId: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<TicketStatusResponse> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        val status = queueService.getTicketStatus(storeId, ticketId)
        return ResponseEntity.ok(status)
    }

    @PostMapping("/next")
    suspend fun callNext(
        @PathVariable storeId: UUID,
        @RequestParam(required = false) counterId: String?,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<NextTicketResponse> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        return when (val result = queueService.callNextUntilSuccess(storeId, counterId)) {
            is CallNextResult.Success -> ResponseEntity.ok(NextTicketResponse(ticket = result.ticket))
            is CallNextResult.QueueEmpty -> ResponseEntity.ok(NextTicketResponse(ticket = null))
            is CallNextResult.GhostTicketSkipped -> ResponseEntity.ok(NextTicketResponse(ticket = null))
        }
    }

    @PostMapping("/tickets/{ticketId}/serve")
    suspend fun serveTicket(
        @PathVariable storeId: UUID,
        @PathVariable ticketId: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<Void> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        queueService.serveTicket(storeId, ticketId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/tickets/{ticketId}/cancel")
    suspend fun cancelTicket(
        @PathVariable storeId: UUID,
        @PathVariable ticketId: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<Void> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        queueService.cancelTicket(storeId, ticketId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/cleanup")
    suspend fun cleanupServingSet(
        @PathVariable storeId: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<Map<String, Int>> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        val cleaned = queueService.cleanupServingSet(storeId)
        return ResponseEntity.ok(mapOf("cleanedEntries" to cleaned))
    }
}
