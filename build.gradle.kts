plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "com.slavaslava"
version = "0.0.1-SNAPSHOT"
description = "Transfer API"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-flyway")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("com.bucket4j:bucket4j_jdk17-core:8.21.0")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// API tests: black-box HTTP tests (REST Assured) against the app booted on a random port.
// Kept in their own source set so they run as a separate level; `check` depends on them.
val apiTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[apiTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[apiTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    "apiTestImplementation"("io.rest-assured:rest-assured:6.0.1")
    "apiTestImplementation"("org.testcontainers:testcontainers-toxiproxy")
}

val apiTestTask = tasks.register<Test>("apiTest") {
    description = "Runs the REST Assured API tests."
    group = "verification"
    testClassesDirs = apiTest.output.classesDirs
    classpath = apiTest.runtimeClasspath
    shouldRunAfter(tasks.test)
}

// UI tests: Playwright for Java drives a headless Chromium through the /ui page of the app booted on a
// random port (own Testcontainers PostgreSQL + Redis). Own source set and task; `check` depends on it.
val uiTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[uiTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[uiTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

dependencies {
    "uiTestImplementation"("com.microsoft.playwright:playwright:1.63.0")
}

// Installs the Chromium build this Playwright version needs into ~/.cache/ms-playwright (a quick no-op
// once it is there). On CI (the CI env var is set) it also installs Chromium's OS libraries via apt.
val playwrightInstall = tasks.register<JavaExec>("playwrightInstall") {
    description = "Installs the Chromium browser used by the Playwright UI tests."
    group = "verification"
    classpath = uiTest.runtimeClasspath
    mainClass = "com.microsoft.playwright.CLI"
    args = if (System.getenv("CI") != null) listOf("install", "--with-deps", "chromium") else listOf("install", "chromium")
}

val uiTestTask = tasks.register<Test>("uiTest") {
    description = "Runs the Playwright UI tests."
    group = "verification"
    testClassesDirs = uiTest.output.classesDirs
    classpath = uiTest.runtimeClasspath
    shouldRunAfter(apiTestTask)
    dependsOn(playwrightInstall)
    // the browser comes from playwrightInstall; don't let Playwright.create() download every browser
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    // screenshots and traces of failed tests (CI uploads build/reports/playwright/)
    systemProperty("playwright.reports", layout.buildDirectory.dir("reports/playwright").get().asFile.absolutePath)
}

tasks.withType<Test> {
    useJUnitPlatform()
    // One line per test in the build log, so CI output shows which tests actually ran.
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

// Quality gate: `check` (and therefore `build`/CI) fails if coverage drops below
// these floors. Set a few points under the measured values (line 88%, branch 84%).
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.85".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification, apiTestTask, uiTestTask)
}

// Mutation testing: `./gradlew pitest` mutates service/ and domain/ and re-runs the
// Docker-free unit tests against each mutant. Deliberately NOT wired into `check`
// (too slow for every PR); report in build/reports/pitest/index.html.
pitest {
    junit5PluginVersion = "1.2.3"
    targetClasses = setOf("com.slavaslava.transferapi.service.*", "com.slavaslava.transferapi.domain.*")
    targetTests = setOf(
        "com.slavaslava.transferapi.service.*",
        "com.slavaslava.transferapi.domain.*",
        "com.slavaslava.transferapi.dto.*",
    )
    threads = 2
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false
}
