plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":legacy"))
    implementation(project(":topdf"))
    implementation(project(":pdfa"))

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("stirling.software.officeconvert.cli.Main")
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

val bsdCurves = """
    curvesapi (com.github.virtuald:curvesapi), BSD 3-Clause License

    Copyright (c) 2005, Graph Builder
    All rights reserved.

    Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
    following conditions are met:

    * Redistributions of source code must retain the above copyright notice, this list of conditions and the following
      disclaimer.
    * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
      following disclaimer in the documentation and/or other materials provided with the distribution.
    * Neither the name of Graph Builder nor the names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
    INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
    DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
    SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
    SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
    WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
    OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
""".trimIndent() + "\n"

val extraLicences by tasks.registering {
    val runtime = configurations.runtimeClasspath
    val out = layout.buildDirectory.dir("extra-licences")
    inputs.files(runtime)
    outputs.dir(out)
    doLast {
        // Folder names follow the resolved jars, so a version bump cannot leave a note under a stale name
        val jars = runtime.get().filter { it.name.endsWith(".jar") }.map { it.name.removeSuffix(".jar") }
        fun jar(prefix: String) = jars.firstOrNull {
            it.startsWith("$prefix-") && it.getOrNull(prefix.length + 1)?.isDigit() == true
        } ?: error("$prefix is not on the runtime classpath")
        val apacheNote = "Licensed under the Apache License, Version 2.0: https://www.apache.org/licenses/LICENSE-2.0\n" +
            "(the full text is in META-INF/licenses/${jar("pdfbox")}/LICENSE)\n"
        val texts = mapOf(
            "graphics2d" to "pdfbox-graphics2d (de.rototor.pdfbox:graphics2d)\n$apacheNote",
            "SparseBitSet" to "SparseBitSet (com.zaxxer:SparseBitSet)\n$apacheNote",
            "curvesapi" to bsdCurves,
        )
        val root = out.get().asFile
        root.deleteRecursively()
        texts.forEach { (prefix, text) ->
            val dir = root.resolve("META-INF/licenses/${jar(prefix)}")
            dir.mkdirs()
            dir.resolve("LICENSE.txt").writeText(text)
        }
    }
}

tasks.named<Jar>("jar") {
    archiveFileName.set("stirling-office-convert-cli.jar")
    manifest {
        attributes("Main-Class" to "stirling.software.officeconvert.cli.Main")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(configurations.runtimeClasspath, mergeServices, extraLicences)
    from(mergeServices)
    from(extraLicences)
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
