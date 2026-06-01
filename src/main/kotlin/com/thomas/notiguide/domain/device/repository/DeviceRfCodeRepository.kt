package com.thomas.notiguide.domain.device.repository

import com.thomas.notiguide.core.device.DeviceCommandSigningProperties
import com.thomas.notiguide.core.exception.ServiceUnavailableException
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

    suspend fun update(
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
            UPDATE device_rf_code
            SET payload = pgp_sym_encrypt_bytea(:plaintext, :encryptionKey),
                bits = :bits,
                byte_len = :byteLen,
                version = :version,
                issued_at = :issuedAt,
                ack = :ack::device_rf_ack_status,
                ack_at = :ackAt
            WHERE device_id = :deviceId
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

    suspend fun findExact24GCollision(
        plaintext: ByteArray
    ): DeviceCodeCollision? {
        val encryptionKey = requireEncryptionKey()
        return client.sql(
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
    }

    suspend fun findCurrent(deviceId: UUID): CurrentRfCodeRecord? =
        client.sql(
            """
            SELECT bits, byte_len, version, ack, issued_at, ack_at
            FROM device_rf_code
            WHERE device_id = :deviceId
            """
        )
            .bind("deviceId", deviceId)
            .map(::mapCurrent)
            .one()
            .awaitSingleOrNull()

    suspend fun findAll433MDecryptedInStore(storeId: UUID): List<Decrypted433MRecord> {
        val encryptionKey = requireEncryptionKey()
        return client.sql(
            """
            SELECT d.id, d.public_id, r.bits,
                   pgp_sym_decrypt_bytea(r.payload, :encryptionKey) AS plaintext
            FROM device_rf_code r
            JOIN device d ON d.id = r.device_id
            WHERE d.kind IN ('RECEIVER_433M', 'RECEIVER_433M_PASSIVE')
              AND d.store_id = :storeId
              AND d.status IN ('PENDING_RF_CODE', 'ACTIVE', 'SUSPENDED')
            """
        )
            .bind("storeId", storeId)
            .bind("encryptionKey", encryptionKey)
            .map { row ->
                Decrypted433MRecord(
                    deviceId = row.get("id", UUID::class.java)!!,
                    publicId = row.get("public_id", String::class.java),
                    bits = row.get("bits", Short::class.java)!!.toInt(),
                    plaintext = row.get("plaintext", ByteArray::class.java)!!
                )
            }
            .all()
            .collectList()
            .awaitSingle()
    }

    suspend fun findMaskedCollision(
        storeId: UUID,
        candidatePlaintext: ByteArray,
        candidateBits: Int,
        excludeDeviceId: UUID? = null
    ): DeviceCodeCollision? {
        val existing = findAll433MDecryptedInStore(storeId)
        return existing
            .filter { it.deviceId != excludeDeviceId }
            .firstOrNull { record ->
                val overlapBits = minOf(candidateBits, record.bits)
                val mask = if (overlapBits >= 32) 0xFFFFFFFF.toInt()
                           else (1 shl overlapBits) - 1
                val candidateInt = candidatePlaintext.toBigEndianInt() and mask
                val existingInt = record.plaintext.toBigEndianInt() and mask
                candidateInt == existingInt
            }
            ?.let { DeviceCodeCollision(it.deviceId, it.publicId) }
    }

    private fun ByteArray.toBigEndianInt(): Int {
        var result = 0
        for (b in this) {
            result = (result shl 8) or (b.toInt() and 0xFF)
        }
        return result
    }

    suspend fun findDecryptedPayload(deviceId: UUID): DispatchRfCodeRecord? {
        val encryptionKey = requireEncryptionKey()
        return client.sql(
            """
            SELECT
                pgp_sym_decrypt_bytea(payload, :encryptionKey) AS plaintext,
                bits
            FROM device_rf_code
            WHERE device_id = :deviceId
            """
        )
            .bind("deviceId", deviceId)
            .bind("encryptionKey", encryptionKey)
            .map(::mapDispatchPayload)
            .one()
            .awaitSingleOrNull()
    }

    suspend fun updateAckIfVersion(
        deviceId: UUID,
        version: Int,
        ack: DeviceRfAckStatus,
        ackAt: OffsetDateTime
    ): Boolean =
        client.sql(
            """
            UPDATE device_rf_code
            SET ack = :ack::device_rf_ack_status,
                ack_at = :ackAt
            WHERE device_id = :deviceId
              AND version = :version
            """
        )
            .bind("deviceId", deviceId)
            .bind("version", version)
            .bind("ack", ack.name)
            .bind("ackAt", ackAt)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    private fun requireEncryptionKey(): String =
        properties.rfCode.encryptionKey
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: throw ServiceUnavailableException("Device RF-code encryption is unavailable")

    private fun mapCollision(row: Readable): DeviceCodeCollision = DeviceCodeCollision(
        deviceId = row.get("id", UUID::class.java)!!,
        publicId = row.get("public_id", String::class.java)
    )

    private fun mapCurrent(row: Readable): CurrentRfCodeRecord = CurrentRfCodeRecord(
        bits = row.get("bits", Short::class.java)!!.toInt(),
        byteLen = row.get("byte_len", Short::class.java)!!.toInt(),
        version = row.get("version", Int::class.java)!!,
        ack = row.get("ack", DeviceRfAckStatus::class.java)!!,
        issuedAt = row.get("issued_at", OffsetDateTime::class.java)!!,
        ackAt = row.get("ack_at", OffsetDateTime::class.java)
    )

    data class DeviceCodeCollision(
        val deviceId: UUID,
        val publicId: String?
    )

    data class Decrypted433MRecord(
        val deviceId: UUID,
        val publicId: String?,
        val bits: Int,
        val plaintext: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Decrypted433MRecord

            if (deviceId != other.deviceId) return false
            if (bits != other.bits) return false
            if (!plaintext.contentEquals(other.plaintext)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = deviceId.hashCode()
            result = 31 * result + bits
            result = 31 * result + plaintext.contentHashCode()
            return result
        }
    }

    data class CurrentRfCodeRecord(
        val bits: Int,
        val byteLen: Int,
        val version: Int,
        val ack: DeviceRfAckStatus,
        val issuedAt: OffsetDateTime,
        val ackAt: OffsetDateTime?
    )

    data class DispatchRfCodeRecord(
        val plaintext: ByteArray,
        val bits: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as DispatchRfCodeRecord

            if (bits != other.bits) return false
            if (!plaintext.contentEquals(other.plaintext)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = bits
            result = 31 * result + plaintext.contentHashCode()
            return result
        }
    }

    private fun <T> DatabaseClient.GenericExecuteSpec.bindNullable(
        name: String,
        value: T?,
        type: Class<T>
    ): DatabaseClient.GenericExecuteSpec =
        if (value == null) bindNull(name, type) else bind(name, value)

    private fun mapDispatchPayload(row: Readable): DispatchRfCodeRecord = DispatchRfCodeRecord(
        plaintext = row.get("plaintext", ByteArray::class.java)!!,
        bits = row.get("bits", Short::class.java)!!.toInt()
    )
}
