plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kavach.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kavach.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            // Debug-signed release builds are fine for a hackathon: we install
            // over Office Kit file transfer, not Play.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        // Version-nag checks are noise during a frozen 30-hour build. The
        // toolchain is pinned deliberately (AGP 8.7.3 / Kotlin 2.0.21) —
        // do not bump it mid-event.
        disable += setOf("OldTargetApi", "GradleDependency", "AndroidGradlePluginVersion")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":demo"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// data/tactic_lexicon.json is the single source of truth (CLAUDE.md §Layout).
// Copy it into assets at build time so it is never duplicated by hand.
val syncTacticLexicon by tasks.registering(Copy::class) {
    from(rootProject.file("data/tactic_lexicon.json"))
    into(layout.projectDirectory.dir("src/main/assets"))
}

// DemoMode replays these through the identical pipeline, so the scripts the
// engine is tuned against are exactly the ones the demo plays.
val syncFixtures by tasks.registering(Copy::class) {
    from(rootProject.file("fixtures")) {
        include("**/*.txt")
        exclude("README.md")
    }
    into(layout.projectDirectory.dir("src/main/assets/fixtures"))
}

tasks.named("preBuild") { dependsOn(syncTacticLexicon, syncFixtures) }

// ---------------------------------------------------------------------------
// The privacy invariant. See scripts/assert_no_internet.gradle.kts for the
// rationale. Fails the build if android.permission.INTERNET reaches the merged
// manifest — the headline claim to the jury, verifiable in ten seconds.
//
// VERIFY THIS GUARD WORKS: add the permission deliberately once, confirm the
// build goes red, then remove it. An untested guard is worse than no guard.
// ---------------------------------------------------------------------------
tasks.register("assertNoInternetPermission") {
    group = "verification"
    description = "Fails if android.permission.INTERNET is in the merged manifest."

    dependsOn("processDebugMainManifest")

    val manifestCandidates =
        listOf(
            "build/intermediates/merged_manifest/debug/AndroidManifest.xml",
            "build/intermediates/merged_manifests/debug/AndroidManifest.xml",
            "build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml",
        ).map { layout.projectDirectory.file(it).asFile }

    doLast {
        val manifest =
            manifestCandidates.firstOrNull { it.exists() }
                ?: throw GradleException(
                    "Merged manifest not found. Checked:\n" +
                        manifestCandidates.joinToString("\n") { "  - $it" },
                )

        if (manifest.readText().contains("android.permission.INTERNET")) {
            throw GradleException(
                """
                |
                |  ✗ PRIVACY INVARIANT VIOLATED
                |
                |  android.permission.INTERNET was found in the merged manifest:
                |    $manifest
                |
                |  Kavach processes audio entirely on-device. This permission must
                |  never ship. If a dependency pulled it in transitively, remove the
                |  dependency or strip the permission with tools:node="remove".
                |
                """.trimMargin(),
            )
        }
        logger.lifecycle("✓ No INTERNET permission in merged manifest.")
    }
}

tasks.named("check") { dependsOn("assertNoInternetPermission") }
