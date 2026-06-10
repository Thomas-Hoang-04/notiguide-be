package com.thomas.notiguide.domain.device.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

internal data class TransmitEnvelope(
    @field:JsonProperty("schema_version")
    val schemaVersion: Int = 1,
    @field:JsonProperty("dispatch_id")
    val dispatchId: UUID,
    @field:JsonProperty("receiver_public_id")
    val receiverPublicId: String,
    val band: String,
    @field:JsonProperty("rf_code_hex")
    val rfCodeHex: String,
    @field:JsonProperty("rf_code_bits")
    val rfCodeBits: Int,
    @field:JsonProperty("proto_any")
    val protoAny: Boolean,
    @field:JsonProperty("issued_at")
    val issuedAt: String,
    @field:JsonProperty("device_name")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val deviceName: String?,
    @field:JsonProperty("signature_b64")
    val signatureB64: String
)