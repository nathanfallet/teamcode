plugins { alias(libs.plugins.jvm); alias(libs.plugins.serialization); alias(libs.plugins.exposed) }
kotlin { jvmToolchain(21) }
dependencies {
    api(projects.domain)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.json)
    implementation(libs.ktor.json)
    implementation(libs.flareon.messaging)
    implementation(libs.serialization)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.mysql)
    implementation(libs.mysql)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.exposed.migration.jdbc)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testRuntimeOnly(libs.junit)
}
tasks.test { useJUnitPlatform() }

// Generate and review migration scripts against a throwaway database; Flyway applies them at boot.
exposed {
    migrations {
        tablesPackage = "me.nathanfallet.teamcode.infrastructure.database.tables"
        // The wrapper starts its own tmpfs-backed database and supplies only its loopback port.
        val port = providers.gradleProperty("teamcodeMigrationPort").orNull
        if (port == null) testContainersImageName = "mysql:8.4"
        else {
            require(port.toInt() in 1..65535)
            databaseUrl = "jdbc:mysql://127.0.0.1:$port/teamcode"
            databaseUser = "root"
            databasePassword = "teamcode-migration-fixture"
        }
        fileDirectory = layout.projectDirectory.dir("src/main/resources/db/migration")
    }
}

// External-connection mode in the Exposed generator does not replay migration history itself.
val prepareMigrationDatabase by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "me.nathanfallet.teamcode.infrastructure.database.MigrationBaselineKt"
    args(providers.gradleProperty("teamcodeMigrationPort").orElse("0").get())
}
tasks.named("generateMigrations") {
    if (providers.gradleProperty("teamcodeMigrationPort").isPresent) dependsOn(prepareMigrationDatabase)
}
