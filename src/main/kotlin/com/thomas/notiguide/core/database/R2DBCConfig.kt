package com.thomas.notiguide.core.database

import com.thomas.notiguide.shared.principal.AdminPrincipal
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.spi.ConnectionFactory
import com.thomas.notiguide.domain.admin.types.AdminRole
import java.net.URI
import org.springframework.boot.autoconfigure.r2dbc.R2dbcProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.auditing.DateTimeProvider
import org.springframework.data.convert.WritingConverter
import org.springframework.data.domain.ReactiveAuditorAware
import org.springframework.data.r2dbc.config.AbstractR2dbcConfiguration
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing
import org.springframework.data.r2dbc.convert.EnumWriteSupport
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import reactor.core.publisher.Mono
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAccessor
import java.util.Optional
import java.util.UUID

@Configuration
@EnableR2dbcRepositories
@EnableConfigurationProperties(R2dbcProperties::class)
@EnableR2dbcAuditing(
    auditorAwareRef = "reactiveAuditorAware",
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
    fun reactiveAuditorAware(): ReactiveAuditorAware<UUID> = ReactiveAuditorAware {
        ReactiveSecurityContextHolder.getContext()
            .mapNotNull { it.authentication?.principal }
            .filter { it is AdminPrincipal }
            .cast(AdminPrincipal::class.java)
            .map { it.id }
            .switchIfEmpty(Mono.empty())
    }

    @Bean
    fun auditingDateTimeProvider(): DateTimeProvider = DateTimeProvider {
        Optional.of(OffsetDateTime.now(VIETNAM_ZONE) as TemporalAccessor)
    }

    @Bean
    override fun connectionFactory(): ConnectionFactory {
        val uri = URI(r2Props.url.replaceFirst("r2dbc:", ""))
        val host = uri.host
        val port = uri.port
        val database = uri.path.removePrefix("/")

        val pgConfig = PostgresqlConnectionConfiguration.builder()
            .host(host)
            .port(port)
            .database(database)
            .username(r2Props.username)
            .password(r2Props.password)
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

    @Bean
    override fun getCustomConverters(): List<Any?> = listOf(
        AdminRoleWriteConverter
    )

    @WritingConverter
    object AdminRoleWriteConverter : EnumWriteSupport<AdminRole>()
}