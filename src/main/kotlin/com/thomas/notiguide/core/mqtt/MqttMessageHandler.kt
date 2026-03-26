package com.thomas.notiguide.core.mqtt

import org.eclipse.paho.mqttv5.common.MqttMessage

fun interface MqttMessageHandler {
    fun onMessage(topic: String, message: MqttMessage)
}
