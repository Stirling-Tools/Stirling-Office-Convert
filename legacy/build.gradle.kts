plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":core"))
    implementation(libs.poi.scratchpad)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

base {
    archivesName.set("stirling-office-convert-legacy")
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "stirling.software.officeconvert.legacy")
    }
}

publishing.publications.named<MavenPublication>("mavenJava") {
    artifactId = "stirling-office-convert-legacy"
    pom {
        name.set("Stirling Office Convert Legacy")
        description.set("Legacy PowerPoint (.ppt) output for Stirling Office Convert, through Apache POI")
    }
}
