package com.thomas.notiguide.domain.analytics.repository

import com.thomas.notiguide.domain.analytics.entity.AnalyticsEvent
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

data class StoreSummaryRow(
    val totalIssued: Long,
    val totalCompleted: Long,
    val totalCancelled: Long,
    val totalSkipped: Long,
    val avgWaitSeconds: Double?,
    val avgServiceSeconds: Double?,
    val medianWaitSeconds: Double?,
    val peakHour: Int?,
    val cancelRate: Double?,
    val skipRate: Double?
)

data class HourlyCountRow(
    val hour: Int,
    val avgTickets: Double
)

data class DailyCountRow(
    val date: LocalDate,
    val issued: Long,
    val completed: Long,
    val cancelled: Long,
    val skipped: Long
)

data class StoreOverviewRow(
    val storeId: UUID,
    val storeName: String,
    val issued: Long,
    val completed: Long,
    val cancelled: Long,
    val skipped: Long,
    val avgWaitSeconds: Double?
)

data class WaitBucketRow(
    val bucket: Int,
    val count: Long
)

data class HeatmapRow(
    val dayOfWeek: Int,
    val hour: Int,
    val avgTickets: Double
)

@Repository
class AnalyticsEventRepository(private val client: DatabaseClient) {

    suspend fun insert(event: AnalyticsEvent) {
        client.sql(
            """
            INSERT INTO analytics_event (time, store_id, event_type, ticket_id, wait_duration_seconds, device_id, metadata)
            VALUES (:time, :storeId, :eventType::analytics_event_type, :ticketId, :waitDurationSeconds, :deviceId, :metadata::jsonb)
            """.trimIndent()
        )
            .bind("time", event.time)
            .bind("storeId", event.storeId)
            .bind("eventType", event.eventType.name)
            .bindNullable("ticketId", event.ticketId, UUID::class.java)
            .bindNullable("waitDurationSeconds", event.waitDurationSeconds, Int::class.javaObjectType)
            .bindNullable("deviceId", event.deviceId, UUID::class.java)
            .bindNullable("metadata", event.metadata, String::class.java)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun insertBatch(events: List<AnalyticsEvent>) {
        for (event in events) {
            insert(event)
        }
    }

    suspend fun getSummary(storeId: UUID, from: OffsetDateTime, to: OffsetDateTime): StoreSummaryRow {
        val row = client.sql(
            """
            SELECT
                COALESCE(SUM(CASE WHEN event_type = 'TICKET_ISSUED' THEN 1 ELSE 0 END), 0) AS total_issued,
                COALESCE(SUM(CASE WHEN event_type = 'TICKET_COMPLETED' THEN 1 ELSE 0 END), 0) AS total_completed,
                COALESCE(SUM(CASE WHEN event_type = 'TICKET_CANCELLED' THEN 1 ELSE 0 END), 0) AS total_cancelled,
                COALESCE(SUM(CASE WHEN event_type = 'TICKET_SKIPPED' THEN 1 ELSE 0 END), 0) AS total_skipped,
                AVG(wait_duration_seconds) FILTER (WHERE event_type = 'TICKET_CALLED' AND wait_duration_seconds IS NOT NULL) AS avg_wait_seconds,
                AVG(wait_duration_seconds) FILTER (WHERE event_type = 'TICKET_COMPLETED' AND wait_duration_seconds IS NOT NULL) AS avg_service_seconds,
                PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY wait_duration_seconds)
                    FILTER (WHERE event_type = 'TICKET_CALLED' AND wait_duration_seconds IS NOT NULL) AS median_wait_seconds
            FROM analytics_event
            WHERE store_id = :storeId AND time >= :from AND time < :to
            """.trimIndent()
        )
            .bind("storeId", storeId)
            .bind("from", from)
            .bind("to", to)
            .map { row: Readable, _ ->
                val totalIssued = row.get("total_issued", Long::class.javaObjectType) ?: 0L
                StoreSummaryRow(
                    totalIssued = totalIssued,
                    totalCompleted = row.get("total_completed", Long::class.javaObjectType) ?: 0L,
                    totalCancelled = row.get("total_cancelled", Long::class.javaObjectType) ?: 0L,
                    totalSkipped = row.get("total_skipped", Long::class.javaObjectType) ?: 0L,
                    avgWaitSeconds = row.get("avg_wait_seconds", Double::class.javaObjectType),
                    avgServiceSeconds = row.get("avg_service_seconds", Double::class.javaObjectType),
                    medianWaitSeconds = row.get("median_wait_seconds", Double::class.javaObjectType),
                    peakHour = null, // computed separately
                    cancelRate = if (totalIssued > 0) {
                        (row.get("total_cancelled", Long::class.javaObjectType) ?: 0L).toDouble() / totalIssued * 100
                    } else null,
                    skipRate = if (totalIssued > 0) {
                        (row.get("total_skipped", Long::class.javaObjectType) ?: 0L).toDouble() / totalIssued * 100
                    } else null
                )
            }
            .one()
            .awaitSingleOrNull()

        if (row == null) {
            return StoreSummaryRow(0, 0, 0, 0, null, null, null, null, null, null)
        }

        // Compute peak hour separately
        val peakHour = client.sql(
            """
            SELECT EXTRACT(HOUR FROM time AT TIME ZONE 'Asia/Ho_Chi_Minh')::int AS hour
            FROM analytics_event
            WHERE store_id = :storeId AND time >= :from AND time < :to AND event_type = 'TICKET_ISSUED'
            GROUP BY hour
            ORDER BY COUNT(*) DESC
            LIMIT 1
            """.trimIndent()
        )
            .bind("storeId", storeId)
            .bind("from", from)
            .bind("to", to)
            .map { r: Readable, _ -> r.get("hour", Int::class.javaObjectType) ?: 0 }
            .one()
            .awaitSingleOrNull()

        return row.copy(peakHour = peakHour)
    }

    suspend fun getHourlyDistribution(storeId: UUID, from: OffsetDateTime, to: OffsetDateTime): List<HourlyCountRow> {
        val dayCount = Duration.between(from, to).toDays().coerceAtLeast(1)

        return client.sql(
            """
            SELECT
                EXTRACT(HOUR FROM time AT TIME ZONE 'Asia/Ho_Chi_Minh')::int AS hour,
                COUNT(*)::double precision / :dayCount AS avg_tickets
            FROM analytics_event
            WHERE store_id = :storeId AND time >= :from AND time < :to AND event_type = 'TICKET_ISSUED'
            GROUP BY hour
            ORDER BY hour
            """.trimIndent()
        )
            .bind("storeId", storeId)
            .bind("from", from)
            .bind("to", to)
            .bind("dayCount", dayCount)
            .map { row: Readable, _ ->
                HourlyCountRow(
                    hour = row.get("hour", Int::class.javaObjectType) ?: 0,
                    avgTickets = row.get("avg_tickets", Double::class.javaObjectType) ?: 0.0
                )
            }
            .all()
            .asFlow()
            .toList()
    }

    suspend fun getDailyThroughput(storeId: UUID, from: OffsetDateTime, to: OffsetDateTime): List<DailyCountRow> {
        return client.sql(
            """
            SELECT
                (time AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS day,
                COALESCE(SUM(CASE WHEN event_type = 'TICKET_ISSUED' THEN 1 ELSE 0 END), 0) AS issued,
                COALESCE(SUM(CASE WHEN event_type = 'TICKET_COMPLETED' THEN 1 ELSE 0 END), 0) AS completed,
                COALESCE(SUM(CASE WHEN event_type = 'TICKET_CANCELLED' THEN 1 ELSE 0 END), 0) AS cancelled,
                COALESCE(SUM(CASE WHEN event_type = 'TICKET_SKIPPED' THEN 1 ELSE 0 END), 0) AS skipped
            FROM analytics_event
            WHERE store_id = :storeId AND time >= :from AND time < :to
            GROUP BY day
            ORDER BY day
            """.trimIndent()
        )
            .bind("storeId", storeId)
            .bind("from", from)
            .bind("to", to)
            .map { row: Readable, _ ->
                DailyCountRow(
                    date = row.get("day", LocalDate::class.java)!!,
                    issued = row.get("issued", Long::class.javaObjectType) ?: 0L,
                    completed = row.get("completed", Long::class.javaObjectType) ?: 0L,
                    cancelled = row.get("cancelled", Long::class.javaObjectType) ?: 0L,
                    skipped = row.get("skipped", Long::class.javaObjectType) ?: 0L
                )
            }
            .all()
            .asFlow()
            .toList()
    }

    suspend fun getOverview(from: OffsetDateTime, to: OffsetDateTime): List<StoreOverviewRow> {
        return client.sql(
            """
            SELECT
                ae.store_id,
                s.name AS store_name,
                COALESCE(SUM(CASE WHEN ae.event_type = 'TICKET_ISSUED' THEN 1 ELSE 0 END), 0) AS issued,
                COALESCE(SUM(CASE WHEN ae.event_type = 'TICKET_COMPLETED' THEN 1 ELSE 0 END), 0) AS completed,
                COALESCE(SUM(CASE WHEN ae.event_type = 'TICKET_CANCELLED' THEN 1 ELSE 0 END), 0) AS cancelled,
                COALESCE(SUM(CASE WHEN ae.event_type = 'TICKET_SKIPPED' THEN 1 ELSE 0 END), 0) AS skipped,
                AVG(ae.wait_duration_seconds) FILTER (WHERE ae.event_type = 'TICKET_CALLED' AND ae.wait_duration_seconds IS NOT NULL) AS avg_wait_seconds
            FROM analytics_event ae
            JOIN store s ON ae.store_id = s.id
            WHERE ae.time >= :from AND ae.time < :to AND ae.store_id IS NOT NULL
            GROUP BY ae.store_id, s.name
            ORDER BY issued DESC
            """.trimIndent()
        )
            .bind("from", from)
            .bind("to", to)
            .map { row: Readable, _ ->
                StoreOverviewRow(
                    storeId = row.get("store_id", UUID::class.java)!!,
                    storeName = row.get("store_name", String::class.java) ?: "",
                    issued = row.get("issued", Long::class.javaObjectType) ?: 0L,
                    completed = row.get("completed", Long::class.javaObjectType) ?: 0L,
                    cancelled = row.get("cancelled", Long::class.javaObjectType) ?: 0L,
                    skipped = row.get("skipped", Long::class.javaObjectType) ?: 0L,
                    avgWaitSeconds = row.get("avg_wait_seconds", Double::class.javaObjectType)
                )
            }
            .all()
            .asFlow()
            .toList()
    }

    suspend fun getOverviewDailyThroughput(from: OffsetDateTime, to: OffsetDateTime): List<DailyCountRow> {
        return client.sql(
            """
            SELECT
                (time AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS day,
                COALESCE(SUM(CASE WHEN event_type = 'TICKET_ISSUED' THEN 1 ELSE 0 END), 0) AS issued,
                COALESCE(SUM(CASE WHEN event_type = 'TICKET_COMPLETED' THEN 1 ELSE 0 END), 0) AS completed,
                COALESCE(SUM(CASE WHEN event_type = 'TICKET_CANCELLED' THEN 1 ELSE 0 END), 0) AS cancelled,
                COALESCE(SUM(CASE WHEN event_type = 'TICKET_SKIPPED' THEN 1 ELSE 0 END), 0) AS skipped
            FROM analytics_event
            WHERE time >= :from AND time < :to
            GROUP BY day
            ORDER BY day
            """.trimIndent()
        )
            .bind("from", from)
            .bind("to", to)
            .map { row: Readable, _ ->
                DailyCountRow(
                    date = row.get("day", LocalDate::class.java)!!,
                    issued = row.get("issued", Long::class.javaObjectType) ?: 0L,
                    completed = row.get("completed", Long::class.javaObjectType) ?: 0L,
                    cancelled = row.get("cancelled", Long::class.javaObjectType) ?: 0L,
                    skipped = row.get("skipped", Long::class.javaObjectType) ?: 0L
                )
            }
            .all()
            .asFlow()
            .toList()
    }

    suspend fun getWaitDistribution(storeId: UUID, from: OffsetDateTime, to: OffsetDateTime): List<WaitBucketRow> {
        return client.sql(
            """
            SELECT
                CASE
                    WHEN wait_duration_seconds < 300 THEN 0
                    WHEN wait_duration_seconds < 600 THEN 1
                    WHEN wait_duration_seconds < 900 THEN 2
                    WHEN wait_duration_seconds < 1200 THEN 3
                    ELSE 4
                END AS bucket,
                COUNT(*) AS count
            FROM analytics_event
            WHERE store_id = :storeId
              AND time >= :from AND time < :to
              AND event_type = 'TICKET_CALLED'
              AND wait_duration_seconds IS NOT NULL
            GROUP BY bucket
            ORDER BY bucket
            """.trimIndent()
        )
            .bind("storeId", storeId)
            .bind("from", from)
            .bind("to", to)
            .map { row: Readable, _ ->
                WaitBucketRow(
                    bucket = row.get("bucket", Int::class.javaObjectType) ?: 0,
                    count = row.get("count", Long::class.javaObjectType) ?: 0L
                )
            }
            .all()
            .asFlow()
            .toList()
    }

    suspend fun getHourlyHeatmap(storeId: UUID, from: OffsetDateTime, to: OffsetDateTime): List<HeatmapRow> {
        val weekCount = ChronoUnit.DAYS.between(from.toLocalDate(), to.toLocalDate()).coerceAtLeast(1) / 7.0

        return client.sql(
            """
            SELECT
                EXTRACT(ISODOW FROM time AT TIME ZONE 'Asia/Ho_Chi_Minh')::int AS dow,
                EXTRACT(HOUR FROM time AT TIME ZONE 'Asia/Ho_Chi_Minh')::int AS hour,
                COUNT(*)::double precision / :weekCount AS avg_tickets
            FROM analytics_event
            WHERE store_id = :storeId
              AND time >= :from AND time < :to
              AND event_type = 'TICKET_ISSUED'
            GROUP BY dow, hour
            ORDER BY dow, hour
            """.trimIndent()
        )
            .bind("storeId", storeId)
            .bind("from", from)
            .bind("to", to)
            .bind("weekCount", weekCount.coerceAtLeast(1.0))
            .map { row: Readable, _ ->
                HeatmapRow(
                    dayOfWeek = row.get("dow", Int::class.javaObjectType) ?: 1,
                    hour = row.get("hour", Int::class.javaObjectType) ?: 0,
                    avgTickets = row.get("avg_tickets", Double::class.javaObjectType) ?: 0.0
                )
            }
            .all()
            .asFlow()
            .toList()
    }

    suspend fun getRecentServiceDurations(storeId: UUID, limit: Int): List<Int> {
        return client.sql(
            """
            SELECT wait_duration_seconds
            FROM analytics_event
            WHERE store_id = :storeId AND event_type = 'TICKET_COMPLETED' AND wait_duration_seconds IS NOT NULL
            ORDER BY time DESC
            LIMIT :limit
            """.trimIndent()
        )
            .bind("storeId", storeId)
            .bind("limit", limit)
            .map { row: Readable, _ ->
                row.get("wait_duration_seconds", Int::class.javaObjectType) ?: 0
            }
            .all()
            .asFlow()
            .toList()
    }
}

private fun <T> DatabaseClient.GenericExecuteSpec.bindNullable(
    name: String,
    value: T?,
    type: Class<T>
): DatabaseClient.GenericExecuteSpec =
    if (value != null) bind(name, value) else bindNull(name, type)
