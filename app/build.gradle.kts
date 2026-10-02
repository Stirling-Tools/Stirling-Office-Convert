plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":topdf"))

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.apache.poi:poi-ooxml:5.5.1")
    testImplementation("org.apache.poi:poi-scratchpad:5.5.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("stirling.software.officeconvert.app.App")
}

val mergeServices by tasks.registering {
    val runtime = configurations.runtimeClasspath
    val out = layout.buildDirectory.dir("merged-services")
    inputs.files(runtime)
    outputs.dir(out)
    doLast {
        val merged = sortedMapOf<String, LinkedHashSet<String>>()
        runtime.get().filter { it.name.endsWith(".jar") }.forEach { jar ->
            zipTree(jar).matching { include("META-INF/services/*") }.visit {
                if (!isDirectory) {
                    val lines = file.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
                    merged.getOrPut(name) { LinkedHashSet() }.addAll(lines)
                }
            }
        }
        val dir = out.get().asFile.resolve("META-INF/services")
        dir.deleteRecursively()
        dir.mkdirs()
        merged.forEach { (name, lines) -> dir.resolve(name).writeText(lines.joinToString("\n", postfix = "\n")) }
    }
}

tasks.named<Jar>("jar") {
    archiveFileName.set("stirling-office-convert-app.jar")
    manifest {
        attributes("Main-Class" to "stirling.software.officeconvert.app.App")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath, mergeServices, ":cli:extraLicences")
    from(mergeServices)
    from(project(":cli").layout.buildDirectory.dir("extra-licences"))
    val libraries = configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }
    from({ libraries.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class", "module-info.class")
        exclude("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES*", "META-INF/services/**")
    }
    libraries.forEach { jar ->
        from(zipTree(jar)) {
            include("META-INF/LICENSE*", "META-INF/NOTICE*")
            eachFile { path = "META-INF/licenses/${jar.name.removeSuffix(".jar")}/$name" }
            includeEmptyDirs = false
        }
    }
}
