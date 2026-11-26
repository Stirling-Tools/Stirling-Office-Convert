plugins {
    `java-library`
    `java-test-fixtures`
    `maven-publish`
}

dependencies {
    api(libs.pdfbox)
    implementation(libs.commons.logging)
    implementation(libs.brotli.dec)
    runtimeOnly(libs.jbig2.imageio)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jai.imageio.core)
    testRuntimeOnly(libs.junit.platform.launcher)

    testFixturesImplementation(platform(libs.junit.bom))
    testFixturesImplementation(libs.junit.jupiter.api)
}

listOf("testFixturesApiElements", "testFixturesRuntimeElements").forEach {
    (components["java"] as AdhocComponentWithVariants).withVariantsFromConfiguration(configurations[it]) { skip() }
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

tasks.register<JavaExec>("conversionBench") {
    description = "Times PDF to Office conversions of generated documents; pass -PbenchArgs=\"<dir> <warmups> <runs> [doc:format ...]\""
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("stirling.software.officeconvert.ConversionBench")
    jvmArgs("-Xmx2g", "-XX:+UseSerialGC")
    val benchArgs = providers.gradleProperty("benchArgs")
    argumentProviders.add(CommandLineArgumentProvider {
        benchArgs.orNull?.split(" ")?.filter { it.isNotBlank() } ?: listOf(layout.buildDirectory.dir("bench").get().asFile.path)
    })
}
