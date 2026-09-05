package ai.govbiz.core

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.SpringApplication
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

@SpringBootApplication
class CoreApiApplication

fun main(args: Array<String>) {
    val applicationContext = runApplication<CoreApiApplication>(*args)
    if (applicationContext.environment.matchesProfiles(EVALUATION_CAPTURE_PROFILE)) {
        exitProcess(SpringApplication.exit(applicationContext))
    }
}

private const val EVALUATION_CAPTURE_PROFILE = "evaluation-capture"
