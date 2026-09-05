package ai.govbiz.core

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.SpringApplication
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

@SpringBootApplication
class CoreApiApplication

fun main(args: Array<String>) {
    val applicationContext = runApplication<CoreApiApplication>(*args)
    if (EVALUATION_COMMAND_LINE_PROFILES.any { applicationContext.environment.matchesProfiles(it) }) {
        exitProcess(SpringApplication.exit(applicationContext))
    }
}

private val EVALUATION_COMMAND_LINE_PROFILES = setOf(
    "evaluation-capture",
    "evaluation-fixture-export",
)
