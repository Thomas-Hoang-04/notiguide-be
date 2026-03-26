package com.thomas.notiguide.domain.queue.types

enum class TicketStatus {
    WAITING,
    CALLED,
    SERVED,
    CANCELLED,
    SKIPPED,
    REQUEUED,
    UNKNOWN;

    companion object {
        fun from(rawStatus: String?): TicketStatus {
            if (rawStatus.isNullOrBlank()) return UNKNOWN
            return entries.firstOrNull { it.name.equals(rawStatus, ignoreCase = true) } ?: UNKNOWN
        }
    }
}
