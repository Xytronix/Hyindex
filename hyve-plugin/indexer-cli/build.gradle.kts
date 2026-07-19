plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
}

group = "com.hyve"
version = "1.0.0"

repositories { mavenCentral() }

val monorepoRoot = rootProject.projectDir.parentFile

sourceSets {
    main {
        kotlin.srcDirs(
            monorepoRoot.resolve("plugins/hyve-knowledge/core/src"),
            monorepoRoot.resolve("plugins/hyve-knowledge/cli/src"),
        )
        resources.srcDir(monorepoRoot.resolve("plugins/hyve-knowledge/core/resources"))
    }
    test {
        kotlin.srcDirs(
            monorepoRoot.resolve("plugins/hyve-knowledge/core/testSrc"),
            monorepoRoot.resolve("plugins/hyve-knowledge/cli/testSrc"),
        )
    }
}

dependencies {
    implementation(libs.kotlin.logging)
    implementation(libs.sqlite.jdbc)
    implementation(libs.jvector)
    implementation(libs.agrona)
    implementation(libs.commons.math3)
    implementation(libs.slf4j.api)
    implementation(libs.snakeyaml)
    implementation(libs.javaparser.core)
    implementation(libs.javaparser.symbol.solver)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.addAll("-Xjvm-default=all")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.shadowJar {
    archiveBaseName.set("hyve-knowledge-indexer")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest { attributes("Main-Class" to "com.hyve.knowledge.cli.IndexerMainKt") }
    mergeServiceFiles()
}

tasks.test { useJUnitPlatform() }
