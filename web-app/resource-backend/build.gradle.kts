plugins {
    java
    id("org.springframework.boot") version "4.0.0"
    // io.spring.dependency-management removed: Spring Boot 4 recommends Gradle's
    // native platform() BOM support, and the plugin has known issues with Gradle 9.5+.
}

group = "ee.authplayground"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Import the Spring Boot BOM via Gradle's native platform support.
    // Must be declared in every configuration that contains unversioned dependencies,
    // because platform() constraints do not flow across independent configurations.
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    annotationProcessor(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))

    // Spring Boot Starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // flyway-core is pulled in transitively by spring-boot-starter-flyway.
    // flyway-database-postgresql must still be declared explicitly — the starter
    // is database-agnostic and does not include any database-specific module.
    implementation("org.flywaydb:flyway-database-postgresql")

    // PostgreSQL
    runtimeOnly("org.postgresql:postgresql")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Test Dependencies
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
