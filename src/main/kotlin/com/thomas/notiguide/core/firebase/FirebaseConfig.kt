package com.thomas.notiguide.core.firebase

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.io.ClassPathResource
import java.io.FileInputStream
import java.io.InputStream

@Configuration
class FirebaseConfig(private val env: Environment) {

    private val log = LoggerFactory.getLogger(this::class.java)

    @Bean
    fun firebaseApp(): FirebaseApp? {
        if (FirebaseApp.getApps().isNotEmpty())
            return FirebaseApp.getInstance()

        val serviceAcc: InputStream = try {
            if (env.activeProfiles.contains("dev"))
                ClassPathResource("firebase/notiguide-firebase.json").inputStream
            else FileInputStream(
                System.getenv("FIREBASE_CREDENTIALS_PATH")
                    ?: "/app/config/notiguide-firebase.json"
            )
        } catch (e: Exception) {
            log.warn("Firebase credentials not found — push notifications will be disabled: {}", e.message)
            return null
        }

        val options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(serviceAcc))
            .build()

        log.info("Firebase initialized successfully")
        return FirebaseApp.initializeApp(options)
    }

    @Bean
    @ConditionalOnBean(FirebaseApp::class)
    fun firebaseMessaging(firebaseApp: FirebaseApp): FirebaseMessaging =
        FirebaseMessaging.getInstance(firebaseApp)
}
