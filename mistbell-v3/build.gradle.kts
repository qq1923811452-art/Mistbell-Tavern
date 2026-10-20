import com.github.jk1.license.render.CsvReportRenderer

plugins {
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
    id("com.github.jk1.dependency-license-report") version "3.1.4"
}

licenseReport {
    projects = arrayOf(project, project(":app"))
    configurations = arrayOf("releaseRuntimeClasspath")
    outputDir = layout.buildDirectory.dir("reports/licenses").get().asFile.absolutePath
    renderers = arrayOf(CsvReportRenderer("licenses.csv"))
}

tasks.register("licenseGuard") {
    group = "verification"
    description = "Fails when dependency license reports contain GPL/AGPL/AFFERO/SSPL red lines."
    dependsOn("generateLicenseReport")
    doLast {
        val reportDir = layout.buildDirectory.dir("reports/licenses").get().asFile
        if (!reportDir.isDirectory) throw GradleException("licenseGuard: report directory missing: $reportDir")
        val forbidden = Regex("AGPL|AFFERO|SSPL|(^|[^A-Za-z])GPL")
        val violations = reportDir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in setOf("csv", "json", "html") }
            .flatMap { file -> file.readLines().map { "${file.name}: ${it.trim()}" } }
            .filter { forbidden.containsMatchIn(it) }
            .toList()
        if (violations.isNotEmpty()) {
            violations.forEach { logger.lifecycle("LICENSE RED LINE: {}", it) }
            throw GradleException("licenseGuard: found ${violations.size} forbidden license entries.")
        }
        logger.lifecycle("licenseGuard: OK — no GPL/AGPL/AFFERO/SSPL dependency licenses detected.")
    }
}
