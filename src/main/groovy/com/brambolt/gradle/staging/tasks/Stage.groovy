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
 * This task creates a staging binary.
 *
 * A staging binary is a zip file containing a Gradle wrapper and a build
 * script. The build script is responsible for installing an application or an
 * environment of some sort.
 */
class Stage extends DefaultTask {

  MapProperty<String, Object> targets

  MapProperty<String, Object> artifactCache

  String gradleWrapperPath = '.'

  Boolean includeAllResources = false

  Stage() {
    targets = project.objects.mapProperty(String, Map)
    artifactCache = project.objects.mapProperty(String, Object)
    artifactCache.set(new HashMap<String, Object>())
  }

  static File getResourcesDir(Project project, Map target) {
    new File("${project.buildDir}/resources/${target.name}")
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
    configureTargets()
    this
  }

  void configureTargets() {
    targets.get().each { Map.Entry target -> configureTarget((Map) target.value) }
  }

  void configureTarget(Map target) {
    project.logger.info("""Configuring staging target ${target.name}:
${target}""")
    checkTarget(target)
    ProcessResources processResources = (ProcessResources) project.tasks.findByName('processResources')
    if (target.containsKey('context')) {
      Velocity targetVelocity = createTargetVelocityTask(target, project.velocity as Task)
      processResources.dependsOn(targetVelocity)
    }
    String resourcesSuffix = target.containsKey('context') ? "/${target.name}" : ''
    Copy resources = createResourcesTask(target, processResources,
      "${project.buildDir}/resources/main${resourcesSuffix}")
    // Copy gradleWrapper = createGradleWrapperTask(target, resources)
    // Copy gradleProperties = createGradlePropertiesTask(target, gradleWrapper)
    // Task settings = createSettingsTask(target, gradleProperties)
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
    Velocity targetVelocity = (Velocity) project
      .task(type: Velocity, "${target.name}${firstToUpperCase(Velocity.DEFAULT_VELOCITY_TASK_NAME)}")
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
    Closure<Closure<String>> nameFilter = { getNameFilter(target) }
    (Copy) project.task([type: Copy, dependsOn: dependency], taskName) {
      from project.file(inputPath)
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
      archiveFileName = "${project.ext.artifactId}-${project.version}-${target.name}.zip"
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
          artifactId project.ext.artifactId
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
