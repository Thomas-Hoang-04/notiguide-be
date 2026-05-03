package com.thomas.notiguide.core.database

import com.thomas.notiguide.core.config.AppProperties
import com.thomas.notiguide.domain.admin.types.AdminRole
import com.thomas.notiguide.domain.device.types.DeviceHardwareModel
import com.thomas.notiguide.domain.device.types.DeviceKind
import com.thomas.notiguide.domain.device.types.DeviceRfAckStatus
import com.thomas.notiguide.domain.device.types.DeviceStatus
import com.thomas.notiguide.shared.principal.AdminPrincipal
import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.Connection
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryMetadata
import io.r2dbc.postgresql.codec.EnumCodec
import org.slf4j.LoggerFactory
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
import org.reactivestreams.Publisher
import org.slf4j.Logger
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
    appProperties: AppProperties
): AbstractR2dbcConfiguration() {
    private val log = LoggerFactory.getLogger(this::class.java)
    private val appZone: ZoneId = ZoneId.of(appProperties.timezone)

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
        Optional.of(OffsetDateTime.now(appZone) as TemporalAccessor)
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
            .codecRegistrar(
                EnumCodec.builder()
                    .withEnum("admin_role", AdminRole::class.java)
                    .withEnum("device_kind", DeviceKind::class.java)
                    .withEnum("device_hardware_model", DeviceHardwareModel::class.java)
                    .withEnum("device_status", DeviceStatus::class.java)
                    .withEnum("device_rf_ack_status", DeviceRfAckStatus::class.java)
                    .build()
            )
            .build()

        val pgFactory = PostgresqlConnectionFactory(pgConfig)

        val poolConfig = ConnectionPoolConfiguration.builder(pgFactory)
            .initialSize(r2Props.pool.initialSize)
            .maxSize(r2Props.pool.maxSize)
            .maxIdleTime(r2Props.pool.maxIdleTime)
            .validationQuery(r2Props.pool.validationQuery)
            .build()

        log.info(
            "Configuring R2DBC connection pool: host={}, port={}, database={}, user={}, initialSize={}, maxSize={}, validationQuery={}",
            host,
            port,
            database,
            r2Props.username,
            r2Props.pool.initialSize,
            r2Props.pool.maxSize,
            r2Props.pool.validationQuery
        )

        return LoggingConnectionFactory(ConnectionPool(poolConfig), log, host, port, database)
    }

    @Bean
    override fun getCustomConverters(): List<Any?> = listOf(
        AdminRoleWriteConverter,
        DeviceKindWriteConverter,
        DeviceHardwareModelWriteConverter,
        DeviceStatusWriteConverter,
        DeviceRfAckStatusWriteConverter
    )

    @WritingConverter
    object AdminRoleWriteConverter : EnumWriteSupport<AdminRole>()

    @WritingConverter
    object DeviceKindWriteConverter : EnumWriteSupport<DeviceKind>()

    @WritingConverter
    object DeviceHardwareModelWriteConverter : EnumWriteSupport<DeviceHardwareModel>()

    @WritingConverter
    object DeviceStatusWriteConverter : EnumWriteSupport<DeviceStatus>()

    @WritingConverter
    object DeviceRfAckStatusWriteConverter : EnumWriteSupport<DeviceRfAckStatus>()

    private class LoggingConnectionFactory(
        private val delegate: ConnectionFactory,
        private val log: Logger,
        private val host: String,
        private val port: Int,
        private val database: String
    ) : ConnectionFactory {
        override fun create(): Publisher<out Connection> {
            return Mono.from(delegate.create())
                .doOnSubscribe {
                    log.debug("Requesting R2DBC connection for {}:{} / {}", host, port, database)
                }
                .doOnSuccess {
                    log.debug("R2DBC connection acquired for {}:{} / {}", host, port, database)
                }
                .doOnError { ex ->
                    log.error("Failed to acquire R2DBC connection for {}:{} / {}", host, port, database, ex)
                }
        }

        override fun getMetadata(): ConnectionFactoryMetadata = delegate.metadata
    }
}
