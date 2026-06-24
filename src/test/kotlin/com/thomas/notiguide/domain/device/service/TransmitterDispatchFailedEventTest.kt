package com.thomas.notiguide.domain.device.service

import com.thomas.notiguide.domain.device.types.DeviceDispatchEventType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TransmitterDispatchFailedEventTest {
    private fun dispatchActionOf(type: DeviceDispatchEventType): String =
        when (type) {
            DeviceDispatchEventType.DEVICE_CALL_REQUESTED -> "call"
            DeviceDispatchEventType.DEVICE_STOP_REQUESTED -> "stop"
        }
    @Test
    fun `dispatchActionOf maps call and stop`() {
        assertThat(dispatchActionOf(DeviceDispatchEventType.DEVICE_CALL_REQUESTED)).isEqualTo("call")
        assertThat(dispatchActionOf(DeviceDispatchEventType.DEVICE_STOP_REQUESTED)).isEqualTo("stop")
    }
}
