import com.diffplug.gradle.spotless.SpotlessExtension
import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("org.springframework.boot") version "4.1.2" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.diffplug.spotless") version "7.0.2" apply false
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "com.diffplug.spotless")

    group = "com.flashorder"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    configure<DependencyManagementExtension> {
        imports {
            mavenBom(SpringBootPlugin.BOM_COORDINATES)
        }
    }

    dependencies {
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("user.timezone", "UTC")
    }

    configure<SpotlessExtension> {
        java {
            target("src/**/*.java")
            removeUnusedImports()
            palantirJavaFormat("2.103.0").style("PALANTIR")
            formatAnnotations()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
}
