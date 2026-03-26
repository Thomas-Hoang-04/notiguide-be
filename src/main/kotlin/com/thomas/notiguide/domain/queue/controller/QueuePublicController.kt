package com.thomas.notiguide.domain.queue.controller

import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.core.firebase.FcmNotificationService
import com.thomas.notiguide.domain.queue.dto.IssueTicketResponse
import com.thomas.notiguide.domain.queue.dto.QueueSizeResponse
import com.thomas.notiguide.domain.queue.dto.StorePublicInfoResponse
import com.thomas.notiguide.domain.queue.dto.TicketStatusResponse
import com.thomas.notiguide.domain.queue.request.RegisterFcmTokenRequest
import com.thomas.notiguide.domain.queue.service.QueueService
import com.thomas.notiguide.domain.store.dto.ServiceTypePublicDto
import com.thomas.notiguide.domain.store.service.ServiceTypeService
import com.thomas.notiguide.domain.store.service.StoreService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/queue/public/{storeId}")
class QueuePublicController(
    private val queueService: QueueService,
    private val storeService: StoreService,
    private val serviceTypeService: ServiceTypeService,
    private val fcmNotificationService: FcmNotificationService? = null
) {

    @PostMapping("/tickets")
    suspend fun issueTicket(
        @PathVariable storeId: UUID,
        @RequestParam(required = false) serviceTypeId: UUID?
    ): ResponseEntity<IssueTicketResponse> {
        val ticket = queueService.issueTicket(storeId, serviceTypeId)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            IssueTicketResponse(storeId = storeId, ticket = ticket)
        )
    }

    @GetMapping("/service-types")
    suspend fun listServiceTypes(@PathVariable storeId: UUID): ResponseEntity<List<ServiceTypePublicDto>> {
        return ResponseEntity.ok(serviceTypeService.listActiveServiceTypes(storeId))
    }

    @GetMapping("/tickets/{ticketId}")
    suspend fun getTicketStatus(
        @PathVariable storeId: UUID,
        @PathVariable ticketId: UUID
    ): ResponseEntity<TicketStatusResponse> {
        val status = queueService.getTicketStatus(storeId, ticketId)
        return ResponseEntity.ok(status)
    }

    @GetMapping("/info")
    suspend fun getStoreInfo(@PathVariable storeId: UUID): StorePublicInfoResponse {
        val store = storeService.getStore(storeId)
        val queueState = queueService.getQueueState(store.id)
        val settings = try {
            storeService.getStoreSettings(store.id)
        } catch (_: NotFoundException) { null }
        return StorePublicInfoResponse(
            id = store.id,
            name = store.name,
            address = store.address,
            isActive = store.isActive,
            queueState = queueState.name,
            maxQueueSize = settings?.maxQueueSize ?: 0
        )
    }

    @GetMapping("/size")
    suspend fun getQueueSize(@PathVariable storeId: UUID): QueueSizeResponse {
        val size = queueService.getQueueSize(storeId)
        return QueueSizeResponse(queueSize = size)
    }

    @PostMapping("/tickets/{ticketId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun cancelTicket(
        @PathVariable storeId: UUID,
        @PathVariable ticketId: UUID
    ) {
        queueService.cancelTicket(storeId, ticketId)
    }

    @PostMapping("/tickets/{ticketId}/fcm-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun registerFcmToken(
        @PathVariable storeId: UUID,
        @PathVariable ticketId: UUID,
        @Valid @RequestBody request: RegisterFcmTokenRequest
    ) {
        // Verify ticket exists before registering token
        queueService.getTicketStatus(storeId, ticketId)
        fcmNotificationService?.registerToken(storeId, ticketId, request.token)
    }
}
