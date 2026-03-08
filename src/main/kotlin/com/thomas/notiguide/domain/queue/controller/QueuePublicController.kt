package com.thomas.notiguide.domain.queue.controller

import com.thomas.notiguide.domain.queue.dto.IssueTicketResponse
import com.thomas.notiguide.domain.queue.dto.TicketStatusResponse
import com.thomas.notiguide.domain.queue.service.QueueService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/queue/public/{storeId}")
class QueuePublicController(
    private val queueService: QueueService
) {

    @PostMapping("/tickets")
    suspend fun issueTicket(@PathVariable storeId: UUID): ResponseEntity<IssueTicketResponse> {
        val ticket = queueService.issueTicket(storeId)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            IssueTicketResponse(storeId = storeId, ticket = ticket)
        )
    }

    @GetMapping("/tickets/{ticketId}")
    suspend fun getTicketStatus(
        @PathVariable storeId: UUID,
        @PathVariable ticketId: UUID
    ): ResponseEntity<TicketStatusResponse> {
        val status = queueService.getTicketStatus(storeId, ticketId)
        return ResponseEntity.ok(status)
    }
}
