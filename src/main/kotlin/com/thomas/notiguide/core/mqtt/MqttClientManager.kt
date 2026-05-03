package com.thomas.notiguide.core.mqtt

import org.eclipse.paho.mqttv5.client.IMqttToken
import org.eclipse.paho.mqttv5.client.MqttAsyncClient
import org.eclipse.paho.mqttv5.client.MqttCallback
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse
import org.eclipse.paho.mqttv5.common.MqttException
import org.eclipse.paho.mqttv5.common.MqttMessage
import org.eclipse.paho.mqttv5.common.MqttSubscription
import org.eclipse.paho.mqttv5.common.packet.MqttProperties as PahoMqttProperties
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class MqttClientManager(
    private val client: MqttAsyncClient,
    private val options: MqttConnectionOptions,
    private val mqttProperties: MqttProperties
) : MqttCallback {

    private val log = LoggerFactory.getLogger(this::class.java)
    private val messageHandlers = CopyOnWriteArrayList<MqttMessageHandler>()
    private val activeSubscriptions = ConcurrentHashMap<String, SubscriptionDescriptor>()

    fun connect() {
        client.setCallback(this)
        try {
            client.connect(options).waitForCompletion(mqttProperties.connectionTimeoutSeconds * 1000L)
            log.info("MQTT connected to {} as {}", mqttProperties.broker, client.clientId)
        } catch (e: MqttException) {
            log.warn("MQTT initial connection failed — queue sync will be unavailable: {}", e.message)
        }
    }

    fun disconnect() {
        try {
            if (client.isConnected) {
                client.disconnectForcibly(0, 2000, false)
                log.info("MQTT disconnected")
            }
        } catch (e: MqttException) {
            log.warn("MQTT disconnect error: {}", e.message)
        }
    }

    fun publish(topic: String, payload: ByteArray, qos: Int = mqttProperties.qos, retained: Boolean = false) {
        if (!client.isConnected) {
            log.debug("MQTT not connected, skipping publish to {}", topic)
            return
        }
        try {
            val message = MqttMessage(payload).apply {
                this.qos = qos
                isRetained = retained
            }
            client.publish(topic, message)
        } catch (e: MqttException) {
            log.warn("MQTT publish failed on topic {}: {}", topic, e.message)
        }
    }

    fun subscribe(topicFilter: String, qos: Int = mqttProperties.qos) {
        subscribe(SubscriptionDescriptor(topicFilter = topicFilter, qos = qos))
    }

    fun subscribe(descriptor: SubscriptionDescriptor) {
        activeSubscriptions[descriptor.topicFilter] = descriptor
        if (!client.isConnected) {
            log.debug("MQTT not connected, deferring subscribe to {}", descriptor.topicFilter)
            return
        }
        subscribeInternal(descriptor)
    }

    fun unsubscribe(topicFilter: String) {
        activeSubscriptions.remove(topicFilter)
        if (!client.isConnected) return
        try {
            client.unsubscribe(topicFilter)
        } catch (e: MqttException) {
            log.warn("MQTT unsubscribe failed on {}: {}", topicFilter, e.message)
        }
    }

    fun registerHandler(handler: MqttMessageHandler) {
        messageHandlers.add(handler)
    }

    fun removeHandler(handler: MqttMessageHandler) {
        messageHandlers.remove(handler)
    }

    // -- MqttCallback --

    override fun disconnected(response: MqttDisconnectResponse?) {
        log.warn("MQTT disconnected: {}", response?.reasonString ?: "unknown reason")
    }

    override fun mqttErrorOccurred(exception: MqttException?) {
        log.warn("MQTT error: {}", exception?.message)
    }

    override fun messageArrived(topic: String?, message: MqttMessage?) {
        if (topic == null || message == null) return
        for (handler in messageHandlers) {
            try {
                handler.onMessage(topic, message)
            } catch (e: Exception) {
                log.warn("MQTT handler error on topic {}: {}", topic, e.message)
            }
        }
    }

    override fun deliveryComplete(token: IMqttToken?) {
        // no-op: fire-and-forget publishing
    }

    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
        if (reconnect) {
            log.info("MQTT reconnected to {}", serverURI)
            resubscribeAll()
        }
    }

    override fun authPacketArrived(reasonCode: Int, properties: PahoMqttProperties?) {
        // no-op: not using enhanced auth
    }

    private fun resubscribeAll() {
        for (descriptor in activeSubscriptions.values) {
            subscribeInternal(descriptor, isReconnect = true)
        }
    }

    private fun subscribeInternal(
        descriptor: SubscriptionDescriptor,
        isReconnect: Boolean = false
    ) {
        try {
            val subscription = MqttSubscription(descriptor.topicFilter, descriptor.qos).apply {
                setNoLocal(descriptor.noLocal)
            }
            client.subscribe(subscription)
            if (isReconnect) {
                log.info("MQTT re-subscribed to {} after reconnect", descriptor.topicFilter)
            } else {
                log.info("MQTT subscribed to {}", descriptor.topicFilter)
            }
        } catch (e: MqttException) {
            if (isReconnect) {
                log.warn("MQTT re-subscribe failed on {}: {}", descriptor.topicFilter, e.message)
            } else {
                log.warn("MQTT subscribe failed on {}: {}", descriptor.topicFilter, e.message)
            }
        }
    }

    data class SubscriptionDescriptor(
        val topicFilter: String,
        val qos: Int = 1,
        val noLocal: Boolean = false
    )
}
