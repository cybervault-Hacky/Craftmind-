plugins {
    `java-library`
}

group = "com.craftmind"
version = "1.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

dependencies {
    api("com.google.code.gson:gson:2.10.1")
    testImplementation("junit:junit:4.13.2")
}

// Surface individual test failures (with full stack traces) in the CI log as they happen, so a cancelled or
// interrupted run still shows exactly which assertion failed instead of only the task-level result.
tasks.withType<Test>().configureEach {
    testLogging {
        // Keep per-test progress visible in CI so a timeout identifies the last test in flight, not just the task.
        events("started", "passed", "skipped", "failed")
        setExceptionFormat("full")
        showStackTraces = true
    }
}
