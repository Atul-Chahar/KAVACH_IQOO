plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// CLAUDE.md hard rule 2: this module is pure Kotlin. It must stay JVM-testable
// and must never depend on the Android SDK. No android plugin here, ever.
kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

// Guards hard rule 2 mechanically rather than by convention.
tasks.register("assertDomainIsPureKotlin") {
    group = "verification"
    description = "Fails if any android.* import appears in :domain sources."

    val sources = fileTree("src") { include("**/*.kt") }
    inputs.files(sources)

    doLast {
        val offenders =
            sources.files
                .filter { file -> file.readLines().any { it.trimStart().startsWith("import android") } }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine()
                    appendLine("  ✗ :domain must have zero android.* imports (CLAUDE.md hard rule 2).")
                    appendLine("    Offending files:")
                    offenders.forEach { appendLine("      - $it") }
                    appendLine("    Move this code to :app instead.")
                },
            )
        }
        logger.lifecycle("✓ :domain has no android.* imports.")
    }
}

tasks.named("check") { dependsOn("assertDomainIsPureKotlin") }
