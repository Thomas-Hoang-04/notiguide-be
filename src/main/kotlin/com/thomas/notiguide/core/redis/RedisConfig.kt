package com.thomas.notiguide.core.redis

import io.lettuce.core.ClientOptions
import io.lettuce.core.protocol.ProtocolVersion
import org.springframework.boot.autoconfigure.data.redis.RedisProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.serializer.RedisSerializationContext

@Configuration
class RedisConfig {

    @Bean
    fun redisConnectionFactory(props: RedisProperties): LettuceConnectionFactory {
        val redisConfig = RedisStandaloneConfiguration(props.host, props.port).apply {
            username = props.username ?: "default"
            setPassword(RedisPassword.of(props.password))
        }
        val clientConfig = LettuceClientConfiguration.builder()
            .clientOptions(
                ClientOptions.builder()
                    .protocolVersion(ProtocolVersion.RESP3)
                    .build()
            )
            .build()
        return LettuceConnectionFactory(redisConfig, clientConfig)
    }

    @Bean
    @Primary
    fun reactiveRedisTemplate(factory: ReactiveRedisConnectionFactory): ReactiveRedisTemplate<String, String> {
        val context = RedisSerializationContext.string()

        return ReactiveRedisTemplate(factory, context)
    }

}
