plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(libs.pdfbox)
    implementation(project(":core"))
    implementation(libs.brotli.dec)

    testImplementation(testFixtures(project(":core")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
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
