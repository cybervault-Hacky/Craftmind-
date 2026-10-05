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
