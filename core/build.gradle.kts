plugins {
    `java-library`
    `maven-publish`
}

val pdfboxVersion = "3.0.8"

dependencies {
    api("org.apache.pdfbox:pdfbox:$pdfboxVersion")
    implementation("commons-logging:commons-logging:1.4.0")
    implementation("org.brotli:dec:0.1.2")
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

tasks.register<JavaExec>("glyphRefs") {
    description = "Rebuilds the reference glyph prints from a folder of source fonts given as -PglyphFonts=<dir>"
    group = "build setup"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("stirling.software.officeconvert.extract.GlyphRefsTool")
    val fonts = providers.gradleProperty("glyphFonts")
    val refs = layout.projectDirectory.file("src/main/resources/stirling/software/officeconvert/extract/glyph-refs.txt")
    val notice = layout.projectDirectory.file("src/main/resources/META-INF/licenses/glyph-refs/NOTICE.txt")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(fonts.orNull ?: error("Pass -PglyphFonts=<folder of source fonts>"), refs.asFile.path, notice.asFile.path)
    })
    doFirst {
        notice.asFile.parentFile.mkdirs()
    }
}
