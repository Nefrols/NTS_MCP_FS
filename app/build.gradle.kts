/*
 * Copyright 2025 Aristo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    // Apply the application plugin to add support for building a CLI application in Java.
    application
    id("com.gradleup.shadow") version "9.3.0"
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

dependencies {
    // Use JUnit Jupiter for testing.
    testImplementation(libs.junit.jupiter)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // This dependency is used by the application.
    implementation(libs.guava)
    implementation(libs.jackson.databind)
    implementation(libs.juniversalchardet)

    // Tree-sitter for LSP-like code navigation
    implementation(libs.tree.sitter)
    implementation(libs.tree.sitter.java)
    implementation(libs.tree.sitter.kotlin)
    implementation(libs.tree.sitter.javascript)
    implementation(libs.tree.sitter.typescript)
    implementation(libs.tree.sitter.python)
    implementation(libs.tree.sitter.go)
    implementation(libs.tree.sitter.rust)
    implementation(libs.tree.sitter.c)
    implementation(libs.tree.sitter.cpp)
    implementation(libs.tree.sitter.c.sharp)
    implementation(libs.tree.sitter.php)
    implementation(libs.tree.sitter.html)
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

application {
    // Define the main class for the application.
    mainClass = "ru.nts.tools.mcp.McpServer"
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// Configure shadowJar task
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("app")
    archiveClassifier.set("all")
    archiveVersion.set("")
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}
