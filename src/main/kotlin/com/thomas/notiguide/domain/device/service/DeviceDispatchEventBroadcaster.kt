package com.thomas.notiguide.domain.device.service

import com.thomas.notiguide.domain.device.dto.DeviceDispatchEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks

@Component
class DeviceDispatchEventBroadcaster {

    private val log = LoggerFactory.getLogger(this::class.java)

    private val sink: Sinks.Many<DeviceDispatchEvent> =
        Sinks.many().multicast().directBestEffort()

    fun broadcast(event: DeviceDispatchEvent) {
        val result = sink.tryEmitNext(event)
        if (result.isFailure) {
            log.warn(
                "Device dispatch event was dropped: type={} store={} ticket={} result={}",
                event.type,
                event.storeId,
                event.ticketId,
                result
            )
        }
    }

    fun stream(): Flux<DeviceDispatchEvent> = sink.asFlux()
}

