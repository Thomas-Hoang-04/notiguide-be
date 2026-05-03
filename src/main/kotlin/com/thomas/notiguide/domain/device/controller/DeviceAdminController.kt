package com.thomas.notiguide.domain.device.controller

import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.device.dto.DeviceDto
import com.thomas.notiguide.domain.device.dto.DeviceListResponse
import com.thomas.notiguide.domain.device.request.PassiveDeviceRegistrationRequest
import com.thomas.notiguide.domain.device.service.DeviceQueryService
import com.thomas.notiguide.domain.device.service.PassiveDeviceRegistrationService
import com.thomas.notiguide.domain.device.types.DeviceKind
import com.thomas.notiguide.shared.principal.AdminPrincipal
import com.thomas.notiguide.shared.principal.StoreAccessUtil
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/devices")
class DeviceAdminController(
    private val deviceQueryService: DeviceQueryService,
    private val passiveDeviceRegistrationService: PassiveDeviceRegistrationService
) {

    @GetMapping
    suspend fun listDevices(
        @RequestParam(required = false) kind: DeviceKind?,
        @RequestParam(required = false) storeId: UUID?,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<DeviceListResponse> {
        val effectiveStoreId = when {
            storeId != null -> {
                StoreAccessUtil.requireStoreAccess(principal, storeId)
                storeId
            }
            isSuperAdmin(principal) -> null
            else -> principal.storeId
                ?: throw ForbiddenException("Store-scoped admins need an assigned store to view devices")
        }

        return ResponseEntity.ok(deviceQueryService.listDevices(kind, effectiveStoreId))
    }

    @PostMapping("/passive")
    suspend fun registerPassiveDevice(
        @Valid @RequestBody request: PassiveDeviceRegistrationRequest,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<DeviceDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(
            passiveDeviceRegistrationService.register(request, principal)
        )

    private fun isSuperAdmin(principal: AdminPrincipal): Boolean =
        principal.authorities.any { it.authority == AdminRole.ROLE_SUPER_ADMIN.name }
}
