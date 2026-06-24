package com.thomas.notiguide.domain.device.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

data class DispatchTrackingRecord(
    @field:JsonProperty("device_id")
    val deviceId: UUID,
    @field:JsonProperty("store_id")
    val storeId: UUID,
    @field:JsonProperty("ticket_id")
    val ticketId: UUID,
    @field:JsonProperty("ticket_number")
    val ticketNumber: String? = null,
    @field:JsonProperty("action")
    val action: String? = null
)
