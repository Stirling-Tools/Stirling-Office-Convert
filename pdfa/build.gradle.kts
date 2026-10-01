plugins {
    `java-library`
    `maven-publish`
}

val pdfboxVersion = "3.0.8"

dependencies {
    api("org.apache.pdfbox:pdfbox:$pdfboxVersion")
    implementation(project(":core"))
    implementation(project(":topdf"))

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.verapdf:validation-model:1.30.2")
    testImplementation("com.github.jai-imageio:jai-imageio-jpeg2000:1.4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

base {
    archivesName.set("stirling-office-convert-pdfa")
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "stirling.software.officeconvert.pdfa")
    }
}

publishing.publications.named<MavenPublication>("mavenJava") {
    artifactId = "stirling-office-convert-pdfa"
    pom {
        name.set("Stirling Office Convert PDF/A")
        description.set("PDF to PDF/A (1b, 2b, 2u, 3b, 3u) in plain Java on Apache PDFBox")
    }
}
