package com.thomas.notiguide.core.device

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "device")
data class DeviceCommandSigningProperties(
    val commandSigning: CommandSigning = CommandSigning(),
    val rfCode: RfCode = RfCode(),
    val enrollment: Enrollment = Enrollment()
) {

    data class CommandSigning(
        val pk: String = ""
    )

    data class RfCode(
        val encryptionKey: String = "",
        val defaultBits433m: Int = 32,
        val defaultBits24g: Int = 40,
        val pt2272ToggleChannel: Int = 1,
        val pt2272DetoggleChannel: Int = 0
    ) {
        init {
            require(defaultBits433m in 1..32) { "433MHz band default bit count must be between 1 and 32" }
            require(defaultBits24g == 40) { "2.4GHz band default bit count must be exactly 40" }
            require(pt2272ToggleChannel in 0..0xFF) { "Passive RF receiver toggle channel must be a single byte" }
            require(pt2272DetoggleChannel in 0..0xFF) { "Passive RF receiver toggle channel must be a single byte" }
        }
    }

    data class Enrollment(
        val tokenTtlSeconds: Long = 3600
    ) {
        init {
            require(tokenTtlSeconds > 0) { "Token TTL must be positive" }
        }
    }
}
