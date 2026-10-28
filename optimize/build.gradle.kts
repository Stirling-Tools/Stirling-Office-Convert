plugins {
    `java-library`
    `maven-publish`
}

val pdfboxVersion = "3.0.8"

dependencies {
    api("org.apache.pdfbox:pdfbox:$pdfboxVersion")
    implementation(project(":core"))
    implementation("org.brotli:dec:0.1.2")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

base {
    archivesName.set("stirling-office-convert-optimize")
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "stirling.software.officeconvert.optimize")
    }
}

publishing.publications.named<MavenPublication>("mavenJava") {
    artifactId = "stirling-office-convert-optimize"
    pom {
        name.set("Stirling Office Convert PDF optimizer")
        description.set("Lossless PDF size optimization and repair in plain Java on Apache PDFBox")
    }
}
