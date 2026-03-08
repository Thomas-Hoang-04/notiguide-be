package com.thomas.notiguide.core.redis

import io.lettuce.core.ClientOptions
import io.lettuce.core.protocol.ProtocolVersion
import org.springframework.boot.autoconfigure.data.redis.RedisProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveRedisTemplate
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.RedisSerializationContext
import org.springframework.data.redis.serializer.StringRedisSerializer

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
    fun reactiveRedisTemplate(factory: ReactiveRedisConnectionFactory): ReactiveRedisTemplate<String, Any> {
        val stringSerializer = StringRedisSerializer()
        val valueSerializer = GenericJackson2JsonRedisSerializer()

        val context = RedisSerializationContext.newSerializationContext<String, Any>(stringSerializer)
            .value(valueSerializer)
            .hashKey(stringSerializer)
            .hashValue(stringSerializer)
            .build()

        return ReactiveRedisTemplate(factory, context)
    }

    @Bean
    fun reactiveStringRedisTemplate(factory: ReactiveRedisConnectionFactory): ReactiveStringRedisTemplate =
        ReactiveStringRedisTemplate(factory)
}
