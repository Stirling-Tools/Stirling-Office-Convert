package stirling.software.gradle

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.FileCopyDetails
import org.gradle.api.file.FileVisitDetails
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar

class FatJar {

    static TaskProvider<Task> registerMergeServices(Project project) {
        def runtime = project.configurations.named('runtimeClasspath')
        def out = project.layout.buildDirectory.dir('merged-services')
        project.tasks.register('mergeServices') { Task task ->
            task.inputs.files(runtime)
            task.outputs.dir(out)
            task.doLast {
                def merged = new TreeMap<String, LinkedHashSet<String>>()
                runtime.get().filter { File f -> f.name.endsWith('.jar') }.each { File jar ->
                    project.zipTree(jar).matching { include('META-INF/services/*') }.visit { FileVisitDetails entry ->
                        if (!entry.directory) {
                            def lines = entry.file.readLines('UTF-8')
                                .collect { String line -> beforeHash(line).trim() }
                                .findAll { String line -> !line.isEmpty() }
                            merged.computeIfAbsent(entry.name) { new LinkedHashSet<String>() }.addAll(lines)
                        }
                    }
                }
                def dir = new File(out.get().asFile, 'META-INF/services')
                dir.deleteDir()
                dir.mkdirs()
                merged.each { String name, Set<String> lines ->
                    new File(dir, name).setText(lines.join('\n') + '\n', 'UTF-8')
                }
            }
        }
    }

    static void bundleRuntime(Jar jar, TaskProvider<Task> mergeServices, Object licences) {
        def project = jar.project
        def runtime = project.configurations.getByName('runtimeClasspath')
        jar.duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        jar.dependsOn(runtime, mergeServices)
        jar.from(mergeServices)
        jar.from(licences)
        def libraries = runtime.filter { File f -> f.name.endsWith('.jar') }
        jar.from({ libraries.collect { File lib -> project.zipTree(lib) } }) { CopySpec spec ->
            spec.exclude('META-INF/*.SF', 'META-INF/*.DSA', 'META-INF/*.RSA', 'META-INF/versions/**/module-info.class', 'module-info.class')
            spec.exclude('META-INF/LICENSE*', 'META-INF/NOTICE*', 'META-INF/DEPENDENCIES*', 'META-INF/services/**')
        }
        libraries.each { File lib ->
            def folder = lib.name.substring(0, lib.name.length() - '.jar'.length())
            jar.from(project.zipTree(lib)) { CopySpec spec ->
                spec.include('META-INF/LICENSE*', 'META-INF/NOTICE*')
                spec.eachFile { FileCopyDetails details -> details.path = "META-INF/licenses/${folder}/${details.name}" }
                spec.includeEmptyDirs = false
            }
        }
    }

    private static String beforeHash(String line) {
        int hash = line.indexOf('#')
        hash < 0 ? line : line.substring(0, hash)
    }
}
