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
    systemProperty("topdf.reportDir", layout.buildDirectory.dir("reports").get().asFile.absolutePath)
    doFirst {
        systemProperty("topdf.runtimeClasspath", runtime.get().asPath)
    }
}

val testJava25 by tasks.registering(Test::class) {
    description = "Runs the topdf tests on Java 25, the runtime Stirling-PDF ships, with its stricter JAXP limits"
    group = "verification"
    val test = sourceSets["test"]
    testClassesDirs = test.output.classesDirs
    classpath = test.runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    val runtime = configurations.runtimeClasspath
    inputs.files(runtime)
    systemProperty("topdf.expectJava", "25")
    systemProperty("topdf.reportDir", layout.buildDirectory.dir("reports/java25").get().asFile.absolutePath)
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
        description.set("Word, PowerPoint and Excel (DOCX, PPTX, XLSX) to PDF in plain Java on Apache PDFBox and Apache POI")
    }
}
