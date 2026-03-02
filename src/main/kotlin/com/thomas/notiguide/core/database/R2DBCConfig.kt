package com.thomas.notiguide.core.database

import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.spi.ConnectionFactory
import org.springframework.boot.autoconfigure.r2dbc.R2dbcProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.auditing.DateTimeProvider
import org.springframework.data.r2dbc.config.AbstractR2dbcConfiguration
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAccessor
import java.util.Optional

@Configuration
@EnableR2dbcRepositories
@EnableConfigurationProperties(R2dbcProperties::class)
@EnableR2dbcAuditing(
    dateTimeProviderRef = "auditingDateTimeProvider",
    modifyOnCreate = true
)
class R2DBCConfig(
    private val r2Props: R2dbcProperties,
): AbstractR2dbcConfiguration() {
    companion object {
        val VIETNAM_ZONE: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
    }

    @Bean
    fun auditingDateTimeProvider(): DateTimeProvider = DateTimeProvider {
        Optional.of(OffsetDateTime.now(VIETNAM_ZONE) as TemporalAccessor)
    }

    @Bean
    override fun connectionFactory(): ConnectionFactory {
        val details = r2Props.url.substringAfter("//")
        val (host, port) = details.substringBefore("/").split(":")
        val database = details.substringAfter("/")

        val pgConfig = PostgresqlConnectionConfiguration.builder()
            .host(host)
            .port(port.toInt())
            .database(database)
            .username(r2Props.username)
            .password(r2Props.password)
            // .codecRegistrar(<EnumCodec>)
            .build()

        val pgFactory = PostgresqlConnectionFactory(pgConfig)

        val poolConfig = ConnectionPoolConfiguration.builder(pgFactory)
            .initialSize(r2Props.pool.initialSize)
            .maxSize(r2Props.pool.maxSize)
            .maxIdleTime(r2Props.pool.maxIdleTime)
            .validationQuery(r2Props.pool.validationQuery)
            .build()

        return ConnectionPool(poolConfig)
    }
}