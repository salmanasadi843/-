plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.serialization") version "2.2.10"
    application
}
group = "ir.darsyar"
version = "0.1.0"
repositories { mavenCentral() }
val ktorVersion = "3.2.3"
dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-auth:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("com.google.firebase:firebase-admin:9.4.3")
    implementation("com.zaxxer:HikariCP:6.3.0")
    implementation("org.flywaydb:flyway-core:11.14.1")
    implementation("org.flywaydb:flyway-database-postgresql:11.14.1")
    runtimeOnly("org.postgresql:postgresql:42.7.8")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
application { mainClass.set("ir.darsyar.backend.ApplicationKt") }
kotlin { jvmToolchain(17) }
tasks.test { useJUnitPlatform() }
