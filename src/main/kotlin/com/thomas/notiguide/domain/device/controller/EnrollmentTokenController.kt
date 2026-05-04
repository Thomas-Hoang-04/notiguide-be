package com.thomas.notiguide.domain.device.controller

import com.thomas.notiguide.domain.device.response.EnrollmentTokenIssueResponse
import com.thomas.notiguide.domain.device.dto.EnrollmentTokenMetadataDto
import com.thomas.notiguide.domain.device.request.IssueEnrollmentTokenRequest
import com.thomas.notiguide.domain.device.service.EnrollmentTokenService
import com.thomas.notiguide.shared.principal.AdminPrincipal
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/devices/enrollment-tokens")
class EnrollmentTokenController(
    private val enrollmentTokenService: EnrollmentTokenService
) {

    @PostMapping
    suspend fun issue(
        @Valid @RequestBody request: IssueEnrollmentTokenRequest,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<EnrollmentTokenIssueResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(enrollmentTokenService.issue(request, principal))

    @GetMapping
    suspend fun list(
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<List<EnrollmentTokenMetadataDto>> =
        ResponseEntity.ok(enrollmentTokenService.list(principal))

    @DeleteMapping("/{sha256}")
    suspend fun revoke(
        @PathVariable sha256: String,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<Void> {
        enrollmentTokenService.revoke(sha256, principal)
        return ResponseEntity.noContent().build()
    }
}
