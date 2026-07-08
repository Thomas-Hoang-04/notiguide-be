package com.thomas.notiguide.domain.device.service

import com.thomas.notiguide.core.device.DevicePublicIdMinter
import com.thomas.notiguide.core.exception.HttpException
import com.thomas.notiguide.domain.device.repository.DeviceRepository
import com.thomas.notiguide.domain.device.types.DeviceKind
import com.thomas.notiguide.domain.device.types.DeviceStatus
import com.thomas.notiguide.shared.principal.AdminPrincipal
import com.thomas.notiguide.shared.principal.StoreAccessService
import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class RosterApplyService(
    private val client: DatabaseClient,
    private val deviceRepository: DeviceRepository,
    private val publicIdMinter: DevicePublicIdMinter,
    private val storeAccess: StoreAccessService
) {

    private val log = LoggerFactory.getLogger(this::class.java)

    data class RosterReceiver(
        val slot: Int,
        val band: String,
        val label: String?
    )

    enum class Outcome { APPLIED, DUPLICATE }

    suspend fun relay(
        hubDeviceId: UUID,
        seq: Int,
        receivers: List<RosterReceiver>,
        principal: AdminPrincipal
    ): Outcome {
        val hub = deviceRepository.findById(hubDeviceId)
            ?: throw HttpException(HttpStatus.NOT_FOUND, "device_not_found")

        if (!hub.kind.isHub()) {
            throw HttpException(HttpStatus.CONFLICT, "not_a_transmitter_hub")
        }
        if (hub.status != DeviceStatus.ACTIVE) {
            throw HttpException(HttpStatus.CONFLICT, "device_not_active")
        }
        val storeId = hub.storeId
            ?: throw HttpException(HttpStatus.CONFLICT, "device_store_unassigned")
        storeAccess.requireStoreAccess(principal, storeId)

        val outcome = apply(
            hubDeviceId = hubDeviceId,
            storeId = storeId,
            lastRosterSeq = hub.lastRosterSeq,
            seq = seq,
            receivers = receivers
        )
        log.info(
            "Roster relay {} for hub {} seq={} receivers={}",
            if (outcome == Outcome.APPLIED) "applied" else "duplicate",
            hubDeviceId,
            seq,
            receivers.size
        )
        return outcome
    }

    suspend fun apply(
        hubDeviceId: UUID,
        storeId: UUID?,
        lastRosterSeq: Int?,
        seq: Int,
        receivers: List<RosterReceiver>
    ): Outcome {
        if (lastRosterSeq != null && seq <= lastRosterSeq) {
            return Outcome.DUPLICATE
        }

        val activeSlots = mutableListOf<Short>()
        for (receiver in receivers) {
            val slot = receiver.slot.toShort()
            val kind = bandToKind(receiver.band)

            if (kind == null) {
                log.warn(
                    "Skipping roster receiver with unknown band={} slot={} for hub {}",
                    receiver.band,
                    receiver.slot,
                    hubDeviceId
                )
                continue
            }

            upsertRosterReceiver(
                storeId = storeId,
                hubSlot = slot,
                kind = kind,
                label = receiver.label
            )
            activeSlots.add(slot)
        }

        if (storeId != null) {
            deleteUnpairedReceivers(storeId, activeSlots)
        }

        updateLastRosterSeq(hubDeviceId, seq)
        return Outcome.APPLIED
    }

    private suspend fun upsertRosterReceiver(
        storeId: UUID?,
        hubSlot: Short,
        kind: DeviceKind,
        label: String?
    ) {
        if (storeId == null) return

        val existing = client.sql(
            """
            SELECT id, public_id FROM device
            WHERE store_id = :storeId
              AND hub_slot = :hubSlot
            LIMIT 1
            """
        )
            .bind("storeId", storeId)
            .bind("hubSlot", hubSlot)
            .map(::mapRosterReceiverRecord)
            .one()
            .awaitSingleOrNull()

        if (existing != null) {
            val publicId = existing.publicId ?: publicIdMinter.mint(kind)
            client.sql(
                """
                UPDATE device
                SET kind = :kind::device_kind,
                    status = 'ACTIVE'::device_status,
                    assigned_name = :label,
                    public_id = :publicId,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """
            )
                .bind("id", existing.deviceId)
                .bind("kind", kind.name)
                .bind("label", label ?: "Slot $hubSlot")
                .bind("publicId", publicId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        } else {
            client.sql(
                """
                INSERT INTO device (hub_slot, kind, status, assigned_name, store_id, public_id)
                VALUES (:hubSlot, :kind::device_kind, 'ACTIVE'::device_status, :label, :storeId, :publicId)
                """
            )
                .bind("hubSlot", hubSlot)
                .bind("kind", kind.name)
                .bind("label", label ?: "Slot $hubSlot")
                .bind("storeId", storeId)
                .bind("publicId", publicIdMinter.mint(kind))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    private fun mapRosterReceiverRecord(row: Readable): RosterReceiverRecord = RosterReceiverRecord(
        deviceId = row.get("id", UUID::class.java)!!,
        publicId = row.get("public_id", String::class.java)
    )

    private suspend fun deleteUnpairedReceivers(storeId: UUID, activeSlots: List<Short>) {
        if (activeSlots.isEmpty()) {
            // All receivers unpaired — delete all hub-paired devices for this store
            client.sql(
                """
                DELETE FROM device
                WHERE store_id = :storeId
                  AND hub_slot IS NOT NULL
                """
            )
                .bind("storeId", storeId)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        } else {
            // R2DBC DatabaseClient does not support list binding for NOT IN;
            // build parameterized placeholders dynamically
            val placeholders = activeSlots.indices.joinToString(", ") { ":slot$it" }
            var spec = client.sql(
                """
                DELETE FROM device
                WHERE store_id = :storeId
                  AND hub_slot IS NOT NULL
                  AND hub_slot NOT IN ($placeholders)
                """
            )
                .bind("storeId", storeId)

            activeSlots.forEachIndexed { idx, slot ->
                spec = spec.bind("slot$idx", slot)
            }

            spec.fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    private suspend fun updateLastRosterSeq(hubDeviceId: UUID, seq: Int) {
        client.sql(
            """
            UPDATE device
            SET last_roster_seq = :seq,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
              AND kind = 'TRANSMITTER_HUB'
            """
        )
            .bind("id", hubDeviceId)
            .bind("seq", seq)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private fun bandToKind(band: String): DeviceKind? = when (band) {
        "433M" -> DeviceKind.RECEIVER_433M
        "2_4G" -> DeviceKind.RECEIVER_2_4G
        else -> null
    }
}

private data class RosterReceiverRecord(
    val deviceId: UUID,
    val publicId: String?
)
