# Contributing

Read [architecture](docs/architecture.md) and [product](docs/product.md). Repository text, code, UI and commits are English. Keep changes focused and include evidence for behavior changes. Never put provider credentials or captured user content in fixtures.

Use the checked-in Gradle wrapper. Fast verification: `./gradlew :api:jvmTest :client:jvmTest :domain:test :infrastructure:test :presentation:test :cli:jvmTest`. Native verification is required for platform behavior. Do not stop shared Gradle daemons or someone else's processes.
