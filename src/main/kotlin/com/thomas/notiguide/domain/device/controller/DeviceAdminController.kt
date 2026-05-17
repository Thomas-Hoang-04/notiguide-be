package com.thomas.notiguide.domain.device.controller

import com.thomas.notiguide.core.exception.ForbiddenException
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.device.dto.DeviceDetailDto
import com.thomas.notiguide.domain.device.dto.DeviceDto
import com.thomas.notiguide.domain.device.response.DeviceListResponse
import com.thomas.notiguide.domain.device.request.ApproveDeviceRequest
import com.thomas.notiguide.domain.device.request.DeviceLifecycleRequest
import com.thomas.notiguide.domain.device.request.PassiveDeviceRegistrationRequest
import com.thomas.notiguide.domain.device.request.RotateRfCodeRequest
import com.thomas.notiguide.domain.device.request.DeviceDiagnosticsRelayRequest
import com.thomas.notiguide.domain.device.request.UsbDispatchPayloadRequest
import com.thomas.notiguide.domain.device.response.HubHealthSummaryResponse
import com.thomas.notiguide.domain.device.response.UsbDispatchPayloadResponse
import com.thomas.notiguide.domain.device.service.DeviceApprovalService
import com.thomas.notiguide.domain.device.service.DeviceLifecycleService
import com.thomas.notiguide.domain.device.service.DeviceQueryService
import com.thomas.notiguide.domain.device.service.PassiveDeviceRegistrationService
import com.thomas.notiguide.domain.device.service.RfCodeService
import com.thomas.notiguide.domain.device.service.HubDiagnosticsService
import com.thomas.notiguide.domain.device.service.UsbDispatchPayloadService
import com.thomas.notiguide.domain.device.types.DeviceKind
import com.thomas.notiguide.shared.principal.AdminPrincipal
import com.thomas.notiguide.shared.principal.StoreAccessUtil
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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
    private val passiveDeviceRegistrationService: PassiveDeviceRegistrationService,
    private val deviceApprovalService: DeviceApprovalService,
    private val rfCodeService: RfCodeService,
    private val deviceLifecycleService: DeviceLifecycleService,
    private val usbDispatchPayloadService: UsbDispatchPayloadService,
    private val hubDiagnosticsService: HubDiagnosticsService
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

    @GetMapping("/{id}")
    suspend fun getDevice(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<DeviceDetailDto> {
        val device = deviceQueryService.getRequiredDeviceDetailById(id)
        val storeId = device.storeId
        if (storeId == null && !isSuperAdmin(principal)) {
            throw ForbiddenException("Store-scoped admins need an assigned store to view devices")
        }
        if (storeId != null) {
            StoreAccessUtil.requireStoreAccess(principal, storeId)
        }
        return ResponseEntity.ok(device)
    }

    @PostMapping("/passive")
    suspend fun registerPassiveDevice(
        @Valid @RequestBody request: PassiveDeviceRegistrationRequest,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<DeviceDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(
            passiveDeviceRegistrationService.register(request, principal)
        )

    @PostMapping("/{id}/approve")
    suspend fun approveDevice(
        @PathVariable id: UUID,
        @Valid @RequestBody request: ApproveDeviceRequest,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<DeviceDetailDto> =
        ResponseEntity.ok(deviceApprovalService.approve(id, request, principal))

    @PostMapping("/{id}/reject")
    suspend fun rejectDevice(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<DeviceDetailDto> =
        ResponseEntity.ok(deviceApprovalService.reject(id, principal))

    @PostMapping("/{id}/rf-code")
    suspend fun rotateRfCode(
        @PathVariable id: UUID,
        @RequestBody(required = false) request: RotateRfCodeRequest?,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<DeviceDetailDto> =
        ResponseEntity.ok(rfCodeService.rotate(id, request ?: RotateRfCodeRequest(), principal))

    @PostMapping("/{id}/lifecycle")
    suspend fun issueLifecycleCommand(
        @PathVariable id: UUID,
        @Valid @RequestBody request: DeviceLifecycleRequest,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<DeviceDetailDto> =
        ResponseEntity.ok(deviceLifecycleService.issue(id, request, principal))

    @PostMapping("/{id}/reprovision")
    suspend fun reprovisionDevice(
        @PathVariable id: UUID,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<DeviceDetailDto> =
        ResponseEntity.ok(deviceLifecycleService.reprovision(id, principal))

    @PostMapping("/usb-dispatch-payload")
    suspend fun prepareUsbDispatchPayload(
        @Valid @RequestBody request: UsbDispatchPayloadRequest,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<UsbDispatchPayloadResponse> =
        ResponseEntity.ok(usbDispatchPayloadService.preparePayload(request, principal))

    @PostMapping("/{id}/diagnostics")
    suspend fun relayDiagnostics(
        @PathVariable id: UUID,
        @Valid @RequestBody request: DeviceDiagnosticsRelayRequest,
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<Void> {
        val device = deviceQueryService.getRequiredDeviceDetailById(id)
        val storeId = device.storeId
        if (storeId == null && !isSuperAdmin(principal)) {
            throw ForbiddenException("Store-scoped admins need an assigned store to relay diagnostics")
        }
        if (storeId != null) {
            StoreAccessUtil.requireStoreAccess(principal, storeId)
        }
        hubDiagnosticsService.relayUsbDiagnostics(device, request)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/hub-health")
    suspend fun getHubHealth(
        @AuthenticationPrincipal principal: AdminPrincipal
    ): ResponseEntity<HubHealthSummaryResponse> {
        val effectiveStoreId = when {
            isSuperAdmin(principal) -> null
            else -> principal.storeId
                ?: throw ForbiddenException("Store-scoped admins need an assigned store to view hub health")
        }
        return ResponseEntity.ok(hubDiagnosticsService.getHubHealthSummary(effectiveStoreId))
    }

    private fun isSuperAdmin(principal: AdminPrincipal): Boolean =
        principal.authorities.any { it.authority == AdminRole.ROLE_SUPER_ADMIN.name }
}
