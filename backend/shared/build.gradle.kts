plugins {
    kotlin("jvm") version "1.9.20"
}

group = "com.mbd"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.15.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.0")
    implementation("org.springframework.boot:spring-boot-starter-validation:3.1.0")
}
