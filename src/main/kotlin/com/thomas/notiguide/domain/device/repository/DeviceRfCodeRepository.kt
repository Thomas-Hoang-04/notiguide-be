package com.thomas.notiguide.domain.device.repository

import com.thomas.notiguide.core.device.DeviceCommandSigningProperties
import com.thomas.notiguide.core.exception.ServiceUnavailableException
import com.thomas.notiguide.domain.device.types.DeviceKind
import com.thomas.notiguide.domain.device.types.DeviceRfAckStatus
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
class DeviceRfCodeRepository(
    private val client: DatabaseClient,
    private val properties: DeviceCommandSigningProperties
) {

    suspend fun insert(
        deviceId: UUID,
        plaintext: ByteArray,
        bits: Int,
        version: Int,
        issuedAt: OffsetDateTime,
        ack: DeviceRfAckStatus,
        ackAt: OffsetDateTime?
    ) {
        val encryptionKey = requireEncryptionKey()
        client.sql(
            """
            INSERT INTO device_rf_code (device_id, payload, bits, byte_len, version, issued_at, ack, ack_at)
            VALUES (
                :deviceId,
                pgp_sym_encrypt_bytea(:plaintext, :encryptionKey),
                :bits,
                :byteLen,
                :version,
                :issuedAt,
                :ack::device_rf_ack_status,
                :ackAt
            )
            """
        )
            .bind("deviceId", deviceId)
            .bind("plaintext", plaintext)
            .bind("encryptionKey", encryptionKey)
            .bind("bits", bits)
            .bind("byteLen", plaintext.size)
            .bind("version", version)
            .bind("issuedAt", issuedAt)
            .bind("ack", ack.name)
            .bindNullable("ackAt", ackAt, OffsetDateTime::class.java)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun deleteByDeviceId(deviceId: UUID) {
        client.sql("DELETE FROM device_rf_code WHERE device_id = :deviceId")
            .bind("deviceId", deviceId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun findCollision(
        kind: DeviceKind,
        storeId: UUID?,
        plaintext: ByteArray
    ): DeviceCodeCollision? {
        val encryptionKey = requireEncryptionKey()
        return if (kind.is24Band()) {
            client.sql(
                """
                SELECT d.id, d.public_id
                FROM device_rf_code r
                JOIN device d ON d.id = r.device_id
                WHERE d.kind = 'RECEIVER_2_4G'
                  AND d.status IN ('PENDING_RF_CODE', 'ACTIVE', 'SUSPENDED')
                  AND pgp_sym_decrypt_bytea(r.payload, :encryptionKey) = :plaintext
                LIMIT 1
                """
            )
                .bind("encryptionKey", encryptionKey)
                .bind("plaintext", plaintext)
                .map(::mapCollision)
                .one()
                .awaitSingleOrNull()
        } else {
            requireNotNull(storeId) { "433M RF-code uniqueness checks require a storeId" }
            client.sql(
                """
                SELECT d.id, d.public_id
                FROM device_rf_code r
                JOIN device d ON d.id = r.device_id
                WHERE d.kind IN ('RECEIVER_433M', 'RECEIVER_433M_PASSIVE')
                  AND d.store_id = :storeId
                  AND d.status IN ('PENDING_RF_CODE', 'ACTIVE', 'SUSPENDED')
                  AND pgp_sym_decrypt_bytea(r.payload, :encryptionKey) = :plaintext
                LIMIT 1
                """
            )
                .bind("storeId", storeId)
                .bind("encryptionKey", encryptionKey)
                .bind("plaintext", plaintext)
                .map(::mapCollision)
                .one()
                .awaitSingleOrNull()
        }
    }

    private fun requireEncryptionKey(): String =
        properties.rfCode.encryptionKey
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: throw ServiceUnavailableException("Device RF-code encryption is unavailable")

    private fun mapCollision(row: Readable): DeviceCodeCollision = DeviceCodeCollision(
        deviceId = row.get("id", UUID::class.java)!!,
        publicId = row.get("public_id", String::class.java)
    )

    data class DeviceCodeCollision(
        val deviceId: UUID,
        val publicId: String?
    )

    private fun <T> DatabaseClient.GenericExecuteSpec.bindNullable(
        name: String,
        value: T?,
        type: Class<T>
    ): DatabaseClient.GenericExecuteSpec =
        if (value == null) bindNull(name, type) else bind(name, value)
}
