package com.thomas.notiguide.domain.queue.types

enum class QueueState {
    ACTIVE, PAUSED;

    companion object {
        fun from(raw: String?): QueueState = when (raw) {
            "PAUSED" -> PAUSED
            else -> ACTIVE
        }
    }
}
