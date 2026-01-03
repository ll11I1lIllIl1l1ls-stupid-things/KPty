import org.jetbrains.kotlin.konan.target.Family

plugins {
  kotlin("multiplatform") version "1.9.22"
  `maven-publish`
}

group = "jpty"
version = "0.2.0"

repositories {
  mavenCentral()
}

kotlin {
  applyDefaultHierarchyTemplate()
  linuxX64 {
    compilations.getByName("main") {
      cinterops {
        val pty by creating {
          defFile(file("src/nativeInterop/cinterop/pty-linux.def"))
          includeDirs(file("src/nativeInterop/cinterop"))
        }
      }
    }
  }
  linuxArm64 {
    compilations.getByName("main") {
      cinterops {
        val pty by creating {
          defFile(file("src/nativeInterop/cinterop/pty-linux.def"))
          includeDirs(file("src/nativeInterop/cinterop"))
        }
      }
    }
  }
  macosX64 {
    compilations.getByName("main") {
      cinterops {
        val pty by creating {
          defFile(file("src/nativeInterop/cinterop/pty-macos.def"))
          includeDirs(file("src/nativeInterop/cinterop"))
        }
      }
    }
  }
  macosArm64 {
    compilations.getByName("main") {
      cinterops {
        val pty by creating {
          defFile(file("src/nativeInterop/cinterop/pty-macos.def"))
          includeDirs(file("src/nativeInterop/cinterop"))
        }
      }
    }
  }
  mingwX64 {
    compilations.getByName("main") {
      cinterops {
        val pty by creating {
          defFile(file("src/nativeInterop/cinterop/pty-win.def"))
          includeDirs(file("src/nativeInterop/cinterop"))
        }
      }
    }
  }

  sourceSets {
      commonTest.dependencies { implementation(kotlin("test")) }
  }
}

kotlin.targets
  .withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>()
  .configureEach {
    binaries.all {
      if (konanTarget.family == Family.LINUX || konanTarget.family == Family.OSX) {
        linkerOpts("-lutil")
      } else if (konanTarget.family == Family.MINGW) {
        linkerOpts("-lkernel32")
      }
    }
  }

publishing {
  repositories {
    maven {
      name = "target"
      val repoUrl = (findProperty("mavenUrl") as String?) ?: System.getenv("MAVEN_URL")
      url = uri(repoUrl ?: layout.buildDirectory.dir("repo"))
      credentials {
        username = (findProperty("mavenUser") as String?) ?: System.getenv("MAVEN_USER")
        password = (findProperty("mavenPassword") as String?) ?: System.getenv("MAVEN_PASSWORD")
      }
    }
  }
}
