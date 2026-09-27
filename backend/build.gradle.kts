import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.ktlint)
}

group = "br.com.mesamar"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

// Entidades JPA precisam ser "abertas" para proxies do Hibernate.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// ---------------------------------------------------------------------------
// Fronts (web-staff e web-customer)
//
// Cada front é um projeto Vite. O Gradle roda `npm ci` + `npm run build` e copia
// o `dist/` para `static/<rota>/` dentro do JAR.
//
// -PskipFrontendBuild pula o npm (usado no Docker, onde os fronts são buildados
// num estágio Node separado e o `dist/` já existe). A cópia acontece sempre.
// ---------------------------------------------------------------------------
val skipFrontendBuild = providers.gradleProperty("skipFrontendBuild").isPresent
val npmCommand = if (Os.isFamily(Os.FAMILY_WINDOWS)) "npm.cmd" else "npm"
val generatedStaticDir = layout.buildDirectory.dir("generated-frontend")

val frontends = mapOf(
    "web-staff" to "staff",
    "web-customer" to "cliente",
)

val copyFrontendTasks = frontends.map { (dirName, route) ->
    val frontDir = rootProject.layout.projectDirectory.dir(dirName)
    val taskSuffix = dirName.split("-").joinToString("") { it.replaceFirstChar(Char::uppercase) }

    val npmCi = tasks.register<Exec>("npmCi$taskSuffix") {
        group = "frontend"
        description = "Instala as dependências npm de $dirName"
        onlyIf { !skipFrontendBuild }
        workingDir = frontDir.asFile
        commandLine(npmCommand, "ci", "--no-audit", "--no-fund")
        inputs.file(frontDir.file("package-lock.json"))
        outputs.dir(frontDir.dir("node_modules"))
    }

    val npmBuild = tasks.register<Exec>("npmBuild$taskSuffix") {
        group = "frontend"
        description = "Gera o bundle de produção de $dirName"
        onlyIf { !skipFrontendBuild }
        dependsOn(npmCi)
        workingDir = frontDir.asFile
        commandLine(npmCommand, "run", "build")
        inputs.dir(frontDir.dir("src"))
        inputs.files(
            frontDir.file("index.html"),
            frontDir.file("package.json"),
            frontDir.file("vite.config.ts"),
            frontDir.file("tsconfig.json"),
        )
        outputs.dir(frontDir.dir("dist"))
    }

    tasks.register<Sync>("copyFrontend$taskSuffix") {
        group = "frontend"
        description = "Copia o dist de $dirName para static/$route"
        dependsOn(npmBuild)
        from(frontDir.dir("dist"))
        into(generatedStaticDir.map { it.dir("static/$route") })
    }
}

sourceSets {
    main {
        resources.srcDir(generatedStaticDir)
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(copyFrontendTasks)
}
