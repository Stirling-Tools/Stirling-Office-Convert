plugins {
    `java-library`
    `maven-publish`
}

val pdfboxVersion = "3.0.8"

dependencies {
    api("org.apache.pdfbox:pdfbox:$pdfboxVersion")
    implementation("commons-logging:commons-logging:1.4.0")
    runtimeOnly("org.apache.pdfbox:jbig2-imageio:3.0.5")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

base {
    archivesName.set("stirling-office-convert")
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "stirling.software.officeconvert")
    }
}

publishing.publications.named<MavenPublication>("mavenJava") {
    artifactId = "stirling-office-convert"
    pom {
        name.set("Stirling Office Convert")
        description.set("PDF to editable Word, OpenDocument, RTF, text, PowerPoint and Excel in plain Java on Apache PDFBox")
    }
}
