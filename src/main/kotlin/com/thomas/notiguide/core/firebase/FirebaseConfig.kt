package com.thomas.notiguide.core.firebase

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import java.io.FileInputStream
import java.io.InputStream

@Configuration
@ConditionalOnProperty(
    prefix = "firebase",
    name = ["credentials-path"],
    matchIfMissing = false
)
class FirebaseConfig(private val props: FirebaseProperties) {

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
        val path = props.credentialsPath
        return if (path.startsWith("classpath:")) {
            ClassPathResource(path.removePrefix("classpath:")).inputStream
        } else {
            FileInputStream(path)
        }
    }
}
