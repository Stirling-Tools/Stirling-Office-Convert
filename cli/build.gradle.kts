plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":legacy"))
}

application {
    mainClass.set("stirling.software.officeconvert.cli.Main")
}

tasks.named<Jar>("jar") {
    archiveFileName.set("stirling-office-convert-cli.jar")
    manifest {
        attributes("Main-Class" to "stirling.software.officeconvert.cli.Main")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath)
    val libraries = configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }
    from({ libraries.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class", "module-info.class")
        exclude("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES*")
    }
    libraries.forEach { jar ->
        from(zipTree(jar)) {
            include("META-INF/LICENSE*", "META-INF/NOTICE*")
            eachFile { path = "META-INF/licenses/${jar.name.removeSuffix(".jar")}/$name" }
            includeEmptyDirs = false
        }
    }
}
