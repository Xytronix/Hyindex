plugins {
    java
    alias(libs.plugins.shadow)
}

group = "com.hyve"
version = "1.0.0"

repositories { mavenCentral() }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

dependencies {
    // Optional classpath plugin for embeddingProvider=local (ONNX all-MiniLM-L6-v2-q)
    implementation(libs.langchain4j.embeddings.minilm)
}

tasks.shadowJar {
    archiveBaseName.set("hyve-embeddings-local")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()
}

tasks.named("build") {
    dependsOn(tasks.shadowJar)
}

tasks.jar {
    enabled = false
}
