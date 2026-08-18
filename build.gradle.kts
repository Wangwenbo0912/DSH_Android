import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    jacoco
}

// Unified JaCoCo setup for JVM unit tests across all AGP modules.
// Local run: ./gradlew testDebugUnitTest jacocoTestReport
// (per-module task exists for :app, :common, :bridge, :sandbox-manager)
jacoco {
    toolVersion = "0.8.12"
}

subprojects {
    plugins.withId("com.android.application") {
        configureAndroidJacoco(project)
    }
    plugins.withId("com.android.library") {
        configureAndroidJacoco(project)
    }

    tasks.withType(Test::class).configureEach {
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
        reports {
            junitXml.required.set(true)
            html.required.set(true)
        }
    }
}

fun Project.configureAndroidJacoco(project: Project) {
    project.extensions.configure<com.android.build.gradle.BaseExtension>("android") {
        buildTypes {
            getByName("debug") {
                enableUnitTestCoverage = true
            }
        }
    }

    project.tasks.register("jacocoTestReport", JacocoReport::class.java) {
        dependsOn("testDebugUnitTest")
        reports {
            xml.required.set(true)
            html.required.set(true)
            csv.required.set(true)
        }
        val debugClassesKotlin = fileTree(project.layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
            include("**/*.class")
        }
        val debugClassesJava = fileTree(project.layout.buildDirectory.dir("intermediates/javac/debug/classes")) {
            include("**/*.class")
        }
        classDirectories.setFrom(debugClassesKotlin, debugClassesJava)
        sourceDirectories.setFrom(
            files(
                "src/main/java",
                "src/main/kotlin",
            ),
        )
        executionData.setFrom(
            fileTree(project.layout.buildDirectory.dir("outputs/unit_test_code_coverage/debugUnitTest")) {
                include("*.exec")
            },
        )
        onlyIf {
            !executionData.files.isEmpty()
        }
    }
}