plugins {
    kotlin("jvm") version "2.1.10" apply false
    kotlin("android") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
    id("com.android.application") version "8.9.1" apply false
    id("com.google.devtools.ksp") version "2.1.10-1.0.31" apply false
}

allprojects {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        // Fresh CI workspaces must execute verification even when compilation is cached.
        outputs.doNotCacheIf("JVM verification must execute in each fresh workspace") { true }
    }
}
