package com.thomas.notiguide.core.firebase

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.io.ClassPathResource
import java.io.FileInputStream
import java.io.InputStream

@Configuration
class FirebaseConfig(private val env: Environment) {

    @Bean
    fun firebaseApp(): FirebaseApp {
        if (FirebaseApp.getApps().isNotEmpty())
            return FirebaseApp.getInstance()

        val serviceAcc: InputStream = if (env.activeProfiles.contains("dev"))
            ClassPathResource("firebase/notiguide-firebase.json").inputStream
        else FileInputStream(
                System.getenv("FIREBASE_CREDENTIALS_PATH")
                ?: "/app/config/notiguide-firebase.json"
            )

        val options = FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.fromStream(serviceAcc))
            .build()

        return FirebaseApp.initializeApp(options)
    }

    @Bean
    fun firebaseMessaging(firebaseApp: FirebaseApp): FirebaseMessaging =
        FirebaseMessaging.getInstance(firebaseApp)
}