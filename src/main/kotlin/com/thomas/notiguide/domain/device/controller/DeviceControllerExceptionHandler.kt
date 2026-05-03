package com.thomas.notiguide.domain.device.controller

import com.thomas.notiguide.domain.device.service.DeviceBadRequestEnvelopeException
import com.thomas.notiguide.domain.device.service.DeviceConflictEnvelopeException
import com.thomas.notiguide.domain.device.service.DeviceServiceUnavailableEnvelopeException
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
        ex.detailMessage?.let { body["message"] = it }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body)
    }

    @ExceptionHandler(PassiveDeviceConflictException::class)
    fun handlePassiveConflict(ex: PassiveDeviceConflictException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(mapOf("public_id" to ex.publicId))

    @ExceptionHandler(DeviceConflictEnvelopeException::class)
    fun handleConflict(ex: DeviceConflictEnvelopeException): ResponseEntity<Map<String, String>> {
        val body = linkedMapOf("error" to ex.error)
        ex.publicId?.let { body["public_id"] = it }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body)
    }

    @ExceptionHandler(DeviceServiceUnavailableEnvelopeException::class)
    fun handleServiceUnavailable(ex: DeviceServiceUnavailableEnvelopeException): ResponseEntity<Map<String, String>> {
        val body = linkedMapOf("error" to ex.error)
        ex.detailMessage?.let { body["message"] = it }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body)
    }
}
