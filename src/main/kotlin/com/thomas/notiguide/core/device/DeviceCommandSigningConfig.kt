package com.thomas.notiguide.core.device

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader

@Configuration
class DeviceCommandSigningConfig {

    @Bean
    @ConditionalOnExpression($$"T(org.springframework.util.StringUtils).hasText('${device.command-signing.pk:}')")
    fun deviceCommandSigner(
        properties: DeviceCommandSigningProperties,
        resourceLoader: ResourceLoader
    ): DeviceCommandSigner = DeviceCommandSigner(properties.commandSigning.pk, resourceLoader)
}
