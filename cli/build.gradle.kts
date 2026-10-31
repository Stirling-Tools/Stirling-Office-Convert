plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":legacy"))
    implementation(project(":topdf"))
    implementation(project(":pdfa"))
    implementation(project(":lineart"))
    implementation(project(":optimize"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("stirling.software.officeconvert.cli.Main")
    applicationDefaultJvmArgs = listOf("-XX:+UseSerialGC")
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

val mitJsoup = """
    jsoup (org.jsoup:jsoup), MIT License
    Copyright (c) 2009-2025 Jonathan Hedley <https://jsoup.org/>
    Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
    documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
    rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
    permit persons to whom the Software is furnished to do so, subject to the following conditions:
    The above copyright notice and this permission notice shall be included in all copies or substantial portions of
    the Software.
    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
    WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
    COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
    OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
""".trimIndent() + "\n"

val mitBrotli = """
    Brotli decoder (org.brotli:dec), MIT License
    Copyright (c) 2009, 2010, 2013-2016 by the Brotli Authors.
    Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
    documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
    rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
    permit persons to whom the Software is furnished to do so, subject to the following conditions:
    The above copyright notice and this permission notice shall be included in all copies or substantial portions of
    the Software.
    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
    WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
    COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
    OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
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
            "jsoup" to mitJsoup,
            "dec" to mitBrotli,
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
    archiveFileName.set("stirling-office-convert-cli-unshrunk.jar")
    destinationDirectory.set(layout.buildDirectory.dir("unshrunk"))
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

val proguard by configurations.creating

dependencies {
    proguard(libs.proguard.base)
}

val toolchainLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}

val jdkClasses by tasks.registering(Exec::class) {
    val home = toolchainLauncher.map { it.metadata.installationPath.asFile }
    val out = layout.buildDirectory.dir("jdk-classes")
    inputs.property("jdk", toolchainLauncher.map { it.metadata.javaRuntimeVersion })
    outputs.dir(out)
    doFirst { out.get().asFile.deleteRecursively() }
    executable(home.get().resolve("bin/jimage"))
    args("extract", "--include", "regex:.*\\.class", "--dir", out.get().asFile, home.get().resolve("lib/modules"))
}

val proguardJar by tasks.registering(JavaExec::class) {
    val unshrunk = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val rules = layout.projectDirectory.file("proguard.pro")
    val services = layout.buildDirectory.file("proguard/services.pro")
    val jdk = layout.buildDirectory.dir("jdk-classes")
    val shrunk = layout.buildDirectory.file("proguard/stirling-office-convert-cli.jar")
    dependsOn(jdkClasses, mergeServices)
    inputs.file(unshrunk)
    inputs.file(rules)
    inputs.dir(mergeServices.map { it.outputs.files.singleFile })
    inputs.dir(jdk)
    outputs.file(shrunk)
    classpath = proguard
    mainClass.set("proguard.ProGuard")
    javaLauncher.set(toolchainLauncher)
    maxHeapSize = "2g"
    doFirst {
        val providers = mergeServices.get().outputs.files.singleFile.resolve("META-INF/services")
            .listFiles().orEmpty().flatMap { it.readLines() }.map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet()
        services.get().asFile.apply { parentFile.mkdirs() }
            .writeText(providers.joinToString("") { "-keep class $it { *; }\n" })
        shrunk.get().asFile.delete()
    }
    argumentProviders.add(CommandLineArgumentProvider {
        val modules = jdk.get().asFile.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }
        listOf("-injars", "${unshrunk.get().asFile}(!META-INF/versions/**)", "-outjars", shrunk.get().asFile.path) +
            modules.flatMap { listOf("-libraryjars", "$it(**.class,!module-info.class)") } +
            listOf("@${rules.asFile}", "@${services.get().asFile}")
    })
}

val shrunkJar by tasks.registering(Jar::class) {
    val shrunk = proguardJar.map { it.outputs.files.singleFile }
    dependsOn(proguardJar)
    archiveFileName.set("stirling-office-convert-cli.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    manifest {
        attributes("Main-Class" to "stirling.software.officeconvert.cli.Main")
    }
    from({ zipTree(shrunk) }) {
        exclude("META-INF/MANIFEST.MF")
    }
}

tasks.named("jar") {
    finalizedBy(shrunkJar)
}

tasks.named("assemble") {
    dependsOn(shrunkJar)
}
