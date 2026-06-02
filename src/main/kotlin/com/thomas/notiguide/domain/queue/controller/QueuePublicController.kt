package com.thomas.notiguide.domain.queue.controller

import com.thomas.notiguide.core.exception.NotFoundException
import com.thomas.notiguide.core.exception.ServiceUnavailableException
import com.thomas.notiguide.core.firebase.FcmNotificationService
import com.thomas.notiguide.domain.queue.response.IssueTicketResponse
import com.thomas.notiguide.domain.queue.response.QueueSizeResponse
import com.thomas.notiguide.domain.queue.response.StorePublicInfoResponse
import com.thomas.notiguide.domain.queue.response.TicketStatusResponse
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
@RequestMapping("/api/queue/public/{publicId}")
class QueuePublicController(
    private val queueService: QueueService,
    private val storeService: StoreService,
    private val serviceTypeService: ServiceTypeService,
    private val fcmNotificationService: FcmNotificationService? = null
) {

    @PostMapping("/tickets")
    suspend fun issueTicket(
        @PathVariable publicId: String,
        @RequestParam(required = false) serviceTypeId: UUID?
    ): ResponseEntity<IssueTicketResponse> {
        val store = storeService.getStoreByPublicId(publicId)
        val ticket = queueService.issueTicket(store.id, serviceTypeId)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            IssueTicketResponse(storeId = store.publicId, ticket = ticket)
        )
    }

    @GetMapping("/service-types")
    suspend fun listServiceTypes(@PathVariable publicId: String): ResponseEntity<List<ServiceTypePublicDto>> {
        val store = storeService.getStoreByPublicId(publicId)
        return ResponseEntity.ok(serviceTypeService.listActiveServiceTypes(store.id))
    }

    @GetMapping("/tickets/{ticketId}")
    suspend fun getTicketStatus(
        @PathVariable publicId: String,
        @PathVariable ticketId: UUID
    ): ResponseEntity<TicketStatusResponse> {
        val store = storeService.getStoreByPublicId(publicId)
        val status = queueService.getTicketStatus(store.id, ticketId)
        return ResponseEntity.ok(status)
    }

    @GetMapping("/info")
    suspend fun getStoreInfo(@PathVariable publicId: String): StorePublicInfoResponse {
        val resolution = storeService.resolvePublicId(publicId)
            ?: throw NotFoundException("Store", "publicId", publicId)
        val store = resolution.store
        val queueState = queueService.getQueueState(store.id)
        val settings = try {
            storeService.getStoreSettings(store.id)
        } catch (_: NotFoundException) { null }
        return StorePublicInfoResponse(
            publicId = store.publicId,
            name = store.name,
            address = store.address,
            isActive = store.isActive,
            queueState = queueState.name,
            maxQueueSize = settings?.maxQueueSize ?: 0,
            canonicalId = store.publicId,
            matchedSlug = resolution.matchedSlug
        )
    }

    @GetMapping("/size")
    suspend fun getQueueSize(@PathVariable publicId: String): QueueSizeResponse {
        val store = storeService.getStoreByPublicId(publicId)
        val size = queueService.getQueueSize(store.id)
        return QueueSizeResponse(queueSize = size)
    }

    @PostMapping("/tickets/{ticketId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun cancelTicket(
        @PathVariable publicId: String,
        @PathVariable ticketId: UUID
    ) {
        val store = storeService.getStoreByPublicId(publicId)
        queueService.cancelTicket(store.id, ticketId)
    }

    @PostMapping("/tickets/{ticketId}/fcm-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun registerFcmToken(
        @PathVariable publicId: String,
        @PathVariable ticketId: UUID,
        @Valid @RequestBody request: RegisterFcmTokenRequest
    ) {
        val notificationService = fcmNotificationService
            ?: throw ServiceUnavailableException("Push notifications are unavailable")
        val store = storeService.getStoreByPublicId(publicId)
        // Verify ticket exists before registering token
        queueService.getTicketStatus(store.id, ticketId)
        notificationService.registerToken(store.id, ticketId, request.token)
    }
}
