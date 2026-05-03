package com.thomas.notiguide.domain.device.controller

import com.thomas.notiguide.domain.device.service.DeviceBadRequestEnvelopeException
import com.thomas.notiguide.domain.device.service.PassiveDeviceConflictException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [DeviceAdminController::class])
class DeviceControllerExceptionHandler {

    @ExceptionHandler(DeviceBadRequestEnvelopeException::class)
    fun handleBadRequest(ex: DeviceBadRequestEnvelopeException): ResponseEntity<Map<String, Any>> {
        val body = linkedMapOf<String, Any>("error" to ex.error)
        ex.required?.let { body["required"] = it }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(PassiveDeviceConflictException::class)
    fun handleConflict(ex: PassiveDeviceConflictException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("public_id" to ex.publicId))
}
