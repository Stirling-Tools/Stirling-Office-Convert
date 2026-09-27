plugins {
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":core"))
    implementation("org.apache.poi:poi-scratchpad:5.5.1")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
