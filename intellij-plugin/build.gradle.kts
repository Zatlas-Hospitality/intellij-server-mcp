plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij") version "1.17.3"
}

group = "com.zatlas"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.code.gson:gson:2.10.1")
}

kotlin {
    jvmToolchain(17)
}

intellij {
    version.set("2025.1")
    type.set("IC") // IntelliJ IDEA Community Edition
    plugins.set(listOf("java", "JUnit"))
}

tasks {
    patchPluginXml {
        sinceBuild.set("251")
        untilBuild.set("253.*")
    }

    buildSearchableOptions {
        enabled = false
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
