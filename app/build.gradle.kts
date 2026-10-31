import stirling.software.gradle.FatJar

plugins {
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":topdf"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.poi.ooxml)
    testImplementation(libs.poi.scratchpad)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("stirling.software.officeconvert.app.App")
}

val mergeServices = FatJar.registerMergeServices(project)

tasks.named<Jar>("jar") {
    archiveFileName.set("stirling-office-convert-app.jar")
    manifest {
        attributes("Main-Class" to "stirling.software.officeconvert.app.App")
    }
    dependsOn(":cli:extraLicences")
    FatJar.bundleRuntime(this, mergeServices, project(":cli").layout.buildDirectory.dir("extra-licences"))
}
