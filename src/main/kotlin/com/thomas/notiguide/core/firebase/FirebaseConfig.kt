package com.thomas.notiguide.core.firebase

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader
import java.io.InputStream

@Configuration
@ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('\${firebase.credentials-path:}')")
class FirebaseConfig(
    private val props: FirebaseProperties,
    private val resourceLoader: ResourceLoader
) {

    private val log = LoggerFactory.getLogger(this::class.java)

    @Bean
    fun firebaseApp(): FirebaseApp {
        val credentials = openCredentials().use { stream ->
            GoogleCredentials.fromStream(stream)
        }
        val options = FirebaseOptions.builder()
            .setCredentials(credentials)
            .build()

        val app = if (FirebaseApp.getApps().isEmpty()) {
            FirebaseApp.initializeApp(options)
        } else {
            FirebaseApp.getInstance()
        }

        log.info("Firebase initialized: {}", app.name)
        return app
    }

    @Bean
    fun firebaseMessaging(firebaseApp: FirebaseApp): FirebaseMessaging =
        FirebaseMessaging.getInstance(firebaseApp)

    private fun openCredentials(): InputStream {
        val path = props.credentialsPath.trim()
        return resourceLoader.getResource(path).inputStream
    }
}
