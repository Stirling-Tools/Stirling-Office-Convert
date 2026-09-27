allprojects {
    group = "com.stirling"
    version = findProperty("officeconvert.version")?.toString() ?: "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-Werror"))
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxHeapSize = "1g"
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    plugins.withId("maven-publish") {
        apply(plugin = "signing")
        extensions.configure<JavaPluginExtension> {
            withJavadocJar()
        }
        tasks.withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions).apply {
                addStringOption("Xdoclint:none", "-quiet")
                encoding = "UTF-8"
                charSet = "UTF-8"
            }
        }
        val publishing = extensions.getByType<PublishingExtension>()
        publishing.publications.create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                url.set("https://github.com/Stirling-Tools/Stirling-Office-Convert")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("stirling-tools")
                        name.set("Stirling Tools")
                        url.set("https://github.com/Stirling-Tools")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/Stirling-Tools/Stirling-Office-Convert.git")
                    developerConnection.set("scm:git:ssh://github.com/Stirling-Tools/Stirling-Office-Convert.git")
                    url.set("https://github.com/Stirling-Tools/Stirling-Office-Convert")
                }
            }
        }
        publishing.repositories {
            maven {
                name = "centralPortal"
                val releasesUrl = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
                val snapshotsUrl = uri("https://central.sonatype.com/repository/maven-snapshots/")
                url = if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
                credentials {
                    username = findProperty("centralPortalUsername")?.toString()
                        ?: System.getenv("CENTRAL_PORTAL_USERNAME") ?: ""
                    password = findProperty("centralPortalPassword")?.toString()
                        ?: System.getenv("CENTRAL_PORTAL_PASSWORD") ?: ""
                }
            }
            maven {
                name = "githubPackages"
                val targetRepo = findProperty("githubPackagesRepo")?.toString()
                    ?: System.getenv("GITHUB_REPOSITORY")
                    ?: "Stirling-Tools/Stirling-Office-Convert"
                url = uri("https://maven.pkg.github.com/$targetRepo")
                credentials {
                    username = findProperty("githubActor")?.toString() ?: System.getenv("GITHUB_ACTOR") ?: ""
                    password = findProperty("githubToken")?.toString() ?: System.getenv("GITHUB_TOKEN") ?: ""
                }
            }
        }
        val signingKey = findProperty("signing.key")?.toString() ?: System.getenv("GPG_SIGNING_KEY")
        val signingPassword = findProperty("signing.password")?.toString() ?: System.getenv("GPG_SIGNING_PASSWORD")
        if (signingKey != null && signingPassword != null) {
            extensions.configure<SigningExtension> {
                useInMemoryPgpKeys(signingKey, signingPassword)
                sign(publishing.publications["mavenJava"])
            }
        }
    }
}

gradle.taskGraph.whenReady {
    val releasing = allTasks.any { it.name.contains("ToCentralPortalRepository") }
    if (releasing && !version.toString().endsWith("SNAPSHOT")
            && (findProperty("signing.key") ?: System.getenv("GPG_SIGNING_KEY")) == null) {
        throw GradleException("Releases to Maven Central must be signed: set GPG_SIGNING_KEY and GPG_SIGNING_PASSWORD")
    }
}

tasks.register("finalizePortalDeployment") {
    group = "publishing"
    description = "POST to the OSSRH staging API to hand the uploaded deployment to the Central Portal"

    doLast {
        val namespace = project.group.toString()
        val autoRelease = (findProperty("autoRelease")?.toString() ?: "false").toBoolean()
        val publishingType = if (autoRelease) "automatic" else "user_managed"
        val url = "https://ossrh-staging-api.central.sonatype.com/manual/upload/defaultRepository/$namespace" +
            "?publishing_type=$publishingType"

        val user = findProperty("centralPortalUsername")?.toString()
            ?: System.getenv("CENTRAL_PORTAL_USERNAME")
            ?: error("No Central Portal username configured")
        val pass = findProperty("centralPortalPassword")?.toString()
            ?: System.getenv("CENTRAL_PORTAL_PASSWORD")
            ?: error("No Central Portal password configured")
        val bearer = java.util.Base64.getEncoder().encodeToString("$user:$pass".toByteArray())

        val request = java.net.http.HttpRequest.newBuilder()
            .uri(java.net.URI.create(url))
            .header("Authorization", "Bearer $bearer")
            .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
            .build()
        println("Finalizing deployment to Central Portal (publishingType=$publishingType) ...")
        val response = java.net.http.HttpClient.newHttpClient()
            .send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Failed to finalize deployment: HTTP ${response.statusCode()}\n${response.body()}")
        }
        println(if (autoRelease) "Finalized; artifacts reach Maven Central after validation (10-30 min)"
                else "Finalized; review and publish at https://central.sonatype.com/publishing/deployments")
    }
}

tasks.register("publishAllToCentralPortal") {
    group = "publishing"
    description = "Publish every published module to the Central Portal (upload + finalize)"
    dependsOn(":core:publishAllPublicationsToCentralPortalRepository", ":legacy:publishAllPublicationsToCentralPortalRepository")
    finalizedBy("finalizePortalDeployment")
}
