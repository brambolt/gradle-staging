package com.brambolt.gradle.staging.tasks

import com.brambolt.gradle.velocity.tasks.Velocity
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.PublishArtifact
import org.gradle.api.provider.MapProperty
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip
import org.gradle.language.jvm.tasks.ProcessResources

import static com.brambolt.gradle.text.Strings.firstToUpperCase

/**
 * This task creates target-specific staging binaries.
 *
 * <p>A staging binary is a zip file containing target-specific resources. The
 * targets normally correspond to application environments like development
 * or production but targets can also correspond to hosts, or any other logical
 * unit for which an independent set of resources is required.</p>
 *
 * <p>The execution sequence is as follows:</p>
 * <ul>
 *   <li>First the generate-properties task populates <code>build/vtl</code>.</li>
 *   <li>Then velocity tasks create <code>build/templates</code>.</li>
 *   <li>Independently, process-resources creates <code>build/resources/main</code>.</li>
 *   <li>Next the copy-resources tasks create <code>build/targets</code>.</li>
 *   <li>Finally the archive tasks create <code>build/lib/*.zip</code>.</li>
 * </ul>
 *
 * <p>The staging tasks don't support source sets properly. Instead of the
 * <code>build/targets</code> directory, <code>build/targets/main</code>
 * should be used, etc.</p>
 *
 * <p>The generated archives are zip files, without jar manifests.</p>
 */
class Stage extends DefaultTask {

  /**
   * Defines the targets to produce staging binaries for.
   */
  MapProperty<String, Object> targets

  private MapProperty<String, Object> artifactCache

  /**
   * If this property is set to true then the task will use all available
   * resources when creating the staging binary for each target; if the property
   * is set to false then the task only considers resources with base names
   * that are suffixed by the target name. Defaults to false.
   *
   * <p>For example, if there are two targets <code>dev</code> and
   * <code>test</code> and three resources <code>file1.txt</code>,
   * <code>file2.txt.dev</code> and <code>file2.txt.test</code> then the
   * default behavior is to ignore <code>file1.txt</code> and produce two
   * staging binaries that each contains a file named <code>file2.txt</code>.</p>
   *
   * <p>If on the other hand the property is set to true then each binary
   * will contain all three resources and the target name suffixes will not be
   * removed from the file names.</p>
   *
   * <p>The sensible uses are to either set the property to false and include
   * target suffixes on all file names, or set the property to true and remove
   * all target suffixes from the file names.</p>
   */
  Boolean includeAllResources = false

  Stage() {
    targets = project.objects.mapProperty(String, Map)
    artifactCache = project.objects.mapProperty(String, Object)
    artifactCache.set(new HashMap<String, Object>())
  }

  /**
   * Provides the artifact identifier for the generated archives. This is
   * either <code>project.ext.artifactId</code> or (by default) the project
   * name.
   *
   * <p>Every staging binary shares the same artifact identifier. The
   * staging binaries are distinguished by the artifact classifier, which is
   * always set to the target name for the respective artifact.</p>
   *
   * @param project The project the task belongs to
   * @return The artifact identifier to use for the staging binaries
   */
  static String getArtifactId(Project project) {
    (project.hasProperty('artifactId')
      ? project.artifactId
      : project.name)
  }

  static File getResourcesDir(Project project, Map target) {
    new File("${project.buildDir}/targets/${target.name}")
  }

  static File getTemplatesDir(Project project, Map target) {
    new File("${project.buildDir}/templates/${target.name}")
  }

  @Override
  Task configure(Closure closure) {
    project.logger.info("""Configuring staging:
Targets: [${targets.getOrElse(null)}]""")
     // Force execution, until input/output is handled correctly:
    outputs.upToDateWhen { false }
    super.configure(closure)
    setResourcesDirs()
    configureTargets()
    this
  }

  void setResourcesDirs() {
    if (!includeAllResources)
      project.sourceSets {
        main {
          resources {
            srcDirs(
              "${project.projectDir}/src/main/resources",
            )
          }
        }
      }
  }

  void configureTargets() {
    targets.get().each { Map.Entry target -> configureTarget((Map) target.value) }
  }

  void configureTarget(Map target) {
    checkTarget(target)
    ProcessResources processResources = (ProcessResources) project.tasks.findByName('processResources')
    // Create a target-specific velocity task if the target contains a context:
    if (target.containsKey('context')) {
      Velocity targetVelocity = createTargetVelocityTask(target, project.velocity as Task)
      processResources.dependsOn(targetVelocity)
    }
    String resourcesSuffix = target.containsKey('context') ? "/${target.name}" : ''
    Copy resources = createResourcesTask(target, processResources,
      "${project.buildDir}/templates/${resourcesSuffix}")
    Zip zip = createArchiveTask(target, resources)
    PublishArtifact zipArtifact = createZipArtifact(target, zip)
    project.logger.info("Created publishing artifact $zipArtifact")
    configureArtifactPublishing(target, zipArtifact)
    dependsOn(zip)
  }

  void checkTarget(Map target) {
    if (!target.containsKey('name'))
      throw new GradleException("Missing target name: $target")
  }

  Velocity createTargetVelocityTask(Map target, Task dependency) {
    String taskName = "${target.name}${firstToUpperCase(Velocity.DEFAULT_VELOCITY_TASK_NAME)}"
    Velocity targetVelocity = (Velocity) project.task(type: Velocity, taskName)
    Velocity velocity = (Velocity) project.tasks.findByName(Velocity.DEFAULT_VELOCITY_TASK_NAME)
    if (null != velocity) {
      // Disable the velocity task, since we're doing target velocities instead:
      velocity.onlyIf { false }
      // ... and inherit the velocity task configuration:
      targetVelocity.contextValues.putAll(velocity.contextValues)
      targetVelocity.sort = velocity.sort
      targetVelocity.strict = velocity.strict
    }
    targetVelocity.configure {
      // Now potentially override whatever we may have inherited from the
      // main velocity task...
      if (null != dependency)
        dependsOn(dependency)
      outputs.upToDateWhen { false }
      inputPath = GenerateProperties.getDefaultOutputDir(project).absolutePath
      outputDir = new File("${project.buildDir}/templates/${target.name}")
      contextValues.putAll(target.context)
    }
    targetVelocity
  }

  Copy createResourcesTask(Map target, Task dependency, String inputPath) {
    String taskName = "${target.name}Resources"
    // First check whether we configured the task already:
    Copy existing = project.tasks.findByName(taskName) as Copy
    if (null != existing)
      return existing // Yes - already did - nothing more to be done
    // Closure<String> inclusion = { getResourceInclusion(target) }
    Task processResources = project.tasks.findByName('processResources')
    Closure<Closure<String>> nameFilter = { getNameFilter(target) }
    (Copy) project.task([type: Copy, dependsOn: dependency], taskName) {
      from project.file(inputPath)
      from processResources.destinationDir
      into getResourcesDir(project, target)
      // include inclusion.call()
      include includeAllResources ? "**/*" : "**/*.${target.name}"
      rename nameFilter.call() // Yes, double closure, one call
    }
  }

  String getResourceInclusion(Map target) {
    includeAllResources ? "**/*" : "**/*.${target.name}"
  }

  Closure<String> getNameFilter(Map target) {
    (includeAllResources
      ? { String name -> name }
      : { String name -> name.replaceAll("\\.${target.name}\$", '') })
  }

  Zip createArchiveTask(Map target, Task previous) {
    String taskName = "${target.name}Archive"
    File outputDir = new File(project.buildDir, 'libs')
    Zip existing = project.tasks.findByName(taskName) as Zip
    if (null != existing)
      return existing
    (Zip) project.task([type: Zip, dependsOn: previous], taskName) {
      archiveFileName = "${getArtifactId(project)}-${project.version}-${target.name}.zip"
      destinationDirectory = outputDir
      from getResourcesDir(project, target)
      doFirst {
        if (!outputDir.exists())
          outputDir.mkdirs()
      }
    }
  }

  PublishArtifact createZipArtifact(Map target, Zip zip) {
    if (artifactCache.get().containsKey(target.name))
      // We created the artifact before, and set up publishing:
      return artifactCache.get().get(target.name) as PublishArtifact
    project.artifacts.add('archives', zip.archiveFile) {
      builtBy zip
    }
  }

  void configureArtifactPublishing(Map target, PublishArtifact targetArtifact) {
    if (artifactCache.get().containsKey(target.name))
      return // Do nothing - we've been here before...
    project.publishing {
      publications {
        mavenCustom(MavenPublication) {
          groupId project.group
          artifactId getArtifactId(project)
          version project.version
          artifact(targetArtifact) {
            classifier((String) target.name)
          }
        }
      }
    }
    // Make sure we don't do it all over again...
    artifactCache.put((String) target.name, targetArtifact)
  }

  @TaskAction
  void apply() {}
}
