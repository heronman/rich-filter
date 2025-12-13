plugins {
    kotlin("jvm") version "2.2.21"

    id("java-library")
    id("idea")
    id("maven-publish")
    id("net.agl.gradle.git-version-plugin") version "0.3.0-SNAPSHOT"
}

group = "net.agl.rest.filter"
description = "Exposed predicate builder for AGL Rich Filters"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven { url = uri(findProperty("repo.proxy.url")!! as String) }
}

val kotlinExposedVersion: String by project
val jacksonVersion: String by project

dependencies {
    implementation(project(":rich-filter"))

    implementation("org.jetbrains.exposed:exposed-core:$kotlinExposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$kotlinExposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$kotlinExposedVersion")
    implementation("org.jetbrains.exposed:exposed-json:$kotlinExposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$kotlinExposedVersion")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")

    testImplementation("com.h2database:h2:2.2.224")
    testImplementation("org.jetbrains.exposed:exposed-core:$kotlinExposedVersion")
    testImplementation("org.jetbrains.exposed:exposed-dao:$kotlinExposedVersion")
    testImplementation("org.jetbrains.exposed:exposed-jdbc:$kotlinExposedVersion")
    testImplementation("org.jetbrains.exposed:exposed-jodatime:$kotlinExposedVersion")
    testImplementation("org.jetbrains.exposed:exposed-java-time:$kotlinExposedVersion")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
