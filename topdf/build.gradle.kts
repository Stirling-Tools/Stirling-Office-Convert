plugins {
    `java-library`
    `maven-publish`
}

val pdfboxVersion = "3.0.8"
val poiVersion = "5.5.1"

dependencies {
    api("org.apache.pdfbox:pdfbox:$pdfboxVersion")
    implementation(project(":core"))
    implementation("org.apache.poi:poi-ooxml:$poiVersion")
    implementation("org.apache.poi:poi-scratchpad:$poiVersion")
    implementation("de.rototor.pdfbox:graphics2d:3.0.5")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

base {
    archivesName.set("stirling-office-convert-topdf")
}

tasks.test {
    val runtime = configurations.runtimeClasspath
    inputs.files(runtime)
    systemProperty("topdf.expectJava", "25")
    systemProperty("topdf.reportDir", layout.buildDirectory.dir("reports").get().asFile.absolutePath)
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
        description.set("Office documents to PDF in plain Java on Apache PDFBox and Apache POI: Word (DOCX, DOC, RTF, WordML), PowerPoint (PPTX, PPT), Excel (XLSX, XLSB, XLS), OpenDocument and OpenOffice.org 1.x, Visio, iWork, Lotus 1-2-3, dBASE, SYLK, DIF, CSV, TSV and plain text")
    }
}
