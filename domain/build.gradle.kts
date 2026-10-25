plugins { alias(libs.plugins.jvm) }
kotlin { jvmToolchain(21) }
dependencies {
    implementation(libs.coroutines)
    implementation(libs.snakeyaml.engine)
    implementation(libs.serialization)
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit)
}
tasks.test { useJUnitPlatform() }
