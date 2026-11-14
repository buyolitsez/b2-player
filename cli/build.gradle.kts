// cmus-b2-ask: the assistant of the app (core/) in a terminal, playing in cmus. ./gradlew :cli:installDist
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

application {
    mainClass.set("dev.buyolitsez.b2player.CliKt")
    applicationName = "cmus-b2-ask"
}

dependencies { implementation(project(":core")) }
