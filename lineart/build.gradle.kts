plugins {
    `java-library`
    `maven-publish`
}

val pdfboxVersion = "3.0.8"

dependencies {
    api("org.apache.pdfbox:pdfbox:$pdfboxVersion")
    implementation(project(":core"))

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

base {
    archivesName.set("stirling-office-convert-lineart")
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "stirling.software.officeconvert.lineart")
    }
}

publishing.publications.named<MavenPublication>("mavenJava") {
    artifactId = "stirling-office-convert-lineart"
    pom {
        name.set("Stirling Office Convert line art")
        description.set("Turns the pictures in a PDF into 1-bit line art (JBIG2, CCITT G4 or Flate) in plain Java on Apache PDFBox")
    }
}
