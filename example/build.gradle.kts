plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

kotlin {
    applyDefaultHierarchyTemplate()
    
    linuxX64()
    linuxArm64()
    macosX64()
    macosArm64()
    mingwX64()
    
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.executable {
            entryPoint = "main"
        }
    }
    
    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":"))
            }
        }
        val nativeMain by getting
    }
}
