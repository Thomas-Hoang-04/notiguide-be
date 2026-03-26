package com.thomas.notiguide.domain.queue.controller

import com.thomas.notiguide.core.sse.QueueEventBroadcaster
import com.thomas.notiguide.core.sse.QueueSseEvent
import com.thomas.notiguide.domain.queue.dto.NextTicketResponse
import com.thomas.notiguide.domain.queue.dto.QueueSizeResponse
import com.thomas.notiguide.domain.queue.dto.TicketDto
import com.thomas.notiguide.domain.queue.dto.TicketStatusResponse
import com.thomas.notiguide.domain.queue.service.QueueService
import com.thomas.notiguide.domain.queue.types.CallNextResult
import com.thomas.notiguide.domain.queue.types.QueueState
import com.thomas.notiguide.shared.principal.AdminPrincipal
import com.thomas.notiguide.shared.principal.StoreAccessUtil
import jakarta.validation.constraints.Size
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import java.time.Duration
import java.util.UUID

@Validated
@RestController
@RequestMapping("/api/queue/admin/{storeId}")
class QueueAdminController(
    private val queueService: QueueService,
    private val queueEventBroadcaster: QueueEventBroadcaster
) {

    @GetMapping("/size")
    suspend fun getQueueSize(
        @PathVariable storeId: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<QueueSizeResponse> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        val queueSize = queueService.getQueueSize(storeId)
        return ResponseEntity.ok(QueueSizeResponse(queueSize = queueSize))
    }

    @GetMapping("/tickets")
    suspend fun listWaitingTickets(
        @PathVariable storeId: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<List<TicketDto>> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        val tickets = queueService.listWaitingTickets(storeId)
        return ResponseEntity.ok(tickets)
    }

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
        @RequestParam(required = false) @Size(max = 100) counterId: String?,
        @RequestParam(required = false) serviceTypeId: UUID?,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<NextTicketResponse> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        return when (val result = queueService.callNextUntilSuccess(storeId, counterId, serviceTypeId)) {
            is CallNextResult.Success -> ResponseEntity.ok(NextTicketResponse(ticket = result.ticket))
            is CallNextResult.QueueEmpty -> ResponseEntity.ok(NextTicketResponse(ticket = null))
            is CallNextResult.GhostTicketSkipped -> ResponseEntity.ok(NextTicketResponse(ticket = null))
        }
    }

    @PostMapping("/tickets/{ticketId}/call")
    suspend fun callSpecificTicket(
        @PathVariable storeId: UUID,
        @PathVariable ticketId: UUID,
        @RequestParam(required = false) @Size(max = 100) counterId: String?,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<NextTicketResponse> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        return when (val result = queueService.callSpecificTicket(storeId, ticketId, counterId)) {
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

    @PostMapping("/pause")
    suspend fun pauseQueue(
        @PathVariable storeId: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<Void> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        queueService.setQueueState(storeId, QueueState.PAUSED)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/resume")
    suspend fun resumeQueue(
        @PathVariable storeId: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<Void> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        queueService.setQueueState(storeId, QueueState.ACTIVE)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/tickets/{ticketId}/transfer")
    suspend fun transferTicket(
        @PathVariable storeId: UUID,
        @PathVariable ticketId: UUID,
        @RequestParam targetServiceTypeId: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<Void> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        queueService.transferTicket(storeId, ticketId, targetServiceTypeId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/tickets/{ticketId}/no-show")
    suspend fun triggerNoShow(
        @PathVariable storeId: UUID,
        @PathVariable ticketId: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<Void> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)
        queueService.handleNoShow(storeId, ticketId)
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

    @GetMapping("/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun streamQueueEvents(
        @PathVariable storeId: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): Flux<ServerSentEvent<QueueSseEvent>> {
        StoreAccessUtil.requireStoreAccess(principal, storeId)

        val heartbeat = Flux.interval(Duration.ofSeconds(30))
            .map { ServerSentEvent.builder<QueueSseEvent>().comment("heartbeat").build() }

        val events = queueEventBroadcaster.streamForStore(storeId)
            .map { event ->
                ServerSentEvent.builder(event)
                    .event(event.type)
                    .build()
            }

        return Flux.merge(events, heartbeat)
    }
}
