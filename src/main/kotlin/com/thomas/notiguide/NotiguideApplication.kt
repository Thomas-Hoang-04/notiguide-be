package com.thomas.notiguide

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class NotiguideApplication

fun main(args: Array<String>) {
    runApplication<NotiguideApplication>(*args)
}
