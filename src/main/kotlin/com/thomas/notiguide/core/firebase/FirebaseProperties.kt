package com.thomas.notiguide.core.firebase

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "firebase")
data class FirebaseProperties(
    val credentialsPath: String = ""
)
