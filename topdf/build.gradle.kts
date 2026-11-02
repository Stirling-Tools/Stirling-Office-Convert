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
    testImplementation(libs.junit.platform.launcher)
}

base {
    archivesName.set("stirling-office-convert-topdf")
}

val networkScanTest =
    "stirling.software.officeconvert.topdf.NetworkSafetyTest.everyModuleReferencesOnlyAllowedNetworkOrScriptCode"

tasks.test {
    systemProperty("topdf.expectJava", "25")
    systemProperty("topdf.reportDir", layout.buildDirectory.dir("reports").get().asFile.absolutePath)
    val tmp = layout.buildDirectory.dir("tmp/test-java-io-tmpdir").get().asFile
    systemProperty("java.io.tmpdir", tmp.absolutePath)
    filter {
        excludeTestsMatching(networkScanTest)
    }
    doFirst {
        tmp.deleteRecursively()
        tmp.mkdirs()
    }
}

val networkScan by tasks.registering(Test::class) {
    description = "Checks that no module references code that can reach the network or run scripts."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    val scanned = listOf("core", "legacy", "pdfa", "lineart", "cli", "app", "optimize")
    dependsOn(scanned.map { ":$it:classes" })
    val scannedDirs = scanned.map { rootProject.layout.projectDirectory.dir("$it/build/classes/java/main").asFile }
    inputs.files(scannedDirs).withPropertyName("scannedClasses")
    systemProperty("topdf.scanClasses", scannedDirs.joinToString(File.pathSeparator) { it.absolutePath })
    filter {
        includeTestsMatching(networkScanTest)
    }
}

tasks.check {
    dependsOn(networkScan)
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
