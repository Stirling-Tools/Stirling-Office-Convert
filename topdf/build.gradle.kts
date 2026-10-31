plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(libs.pdfbox)
    implementation(project(":core"))
    implementation(libs.poi.ooxml)
    implementation(libs.poi.scratchpad)
    implementation(libs.pdfbox.graphics2d)
    implementation(libs.jsoup)
    implementation(libs.imageio.webp)

    testImplementation(testFixtures(project(":core")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

base {
    archivesName.set("stirling-office-convert-topdf")
}

tasks.test {
    val runtime = configurations.runtimeClasspath
    inputs.files(runtime)
    systemProperty("topdf.expectJava", "25")
    systemProperty("topdf.reportDir", layout.buildDirectory.dir("reports").get().asFile.absolutePath)
    val scanned = listOf("core", "legacy", "pdfa", "lineart", "cli", "app", "optimize")
    dependsOn(scanned.map { ":$it:classes" })
    val scannedDirs = scanned.map { rootProject.layout.projectDirectory.dir("$it/build/classes/java/main").asFile }
    inputs.files(scannedDirs).withPropertyName("scannedClasses")
    systemProperty("topdf.scanClasses", scannedDirs.joinToString(File.pathSeparator) { it.absolutePath })
    doFirst {
        systemProperty("topdf.runtimeClasspath", runtime.get().asPath)
    }
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "stirling.software.officeconvert.topdf")
    }
}

publishing.publications.named<MavenPublication>("mavenJava") {
    artifactId = "stirling-office-convert-topdf"
    pom {
        name.set("Stirling Office Convert To PDF")
        description.set("Office documents to PDF in plain Java on Apache PDFBox and Apache POI: Word (DOCX, DOC, RTF, WordML), PowerPoint (PPTX, PPT), Excel (XLSX, XLSB, XLS), OpenDocument and OpenOffice.org 1.x, Visio, iWork, Lotus 1-2-3, dBASE, SYLK, DIF, CSV, TSV, HTML, Stirling Office Convert JSON and plain text")
    }
}
