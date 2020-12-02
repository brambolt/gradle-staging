package com.brambolt.gradle

import com.brambolt.gradle.staging.tasks.GenerateProperties
import com.brambolt.gradle.staging.tasks.Stage
import com.brambolt.gradle.velocity.tasks.Velocity
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.internal.file.FileResolver
import org.gradle.language.jvm.tasks.ProcessResources

import javax.inject.Inject

/**
 * A Gradle plug-in for database management.
 */
class StagingPlugin implements Plugin<Project> {

  final FileResolver fileResolver

  @Inject
  StagingPlugin(FileResolver fileResolver) {
    this.fileResolver = fileResolver
  }

  /**
   * Applies the plug-in to the parameter project.
   * @param project The project to apply the plug-in to
   */
  void apply(Project project) {
    createPropertiesTask(project)
    createVelocityTask(project)
    configureProcessResourcesTask(project)
    // createEnvironmentsContainer(project)
    // createHostsContainer(project)
    StagingExtension extension = createStagingExtension(project)
    createStagingTask(project, extension)
  }

  GenerateProperties createPropertiesTask(Project project) {
    GenerateProperties generateProperties = (GenerateProperties) project
      .task(type: GenerateProperties, 'generateProperties')
    generateProperties.configure() // Apply default values
    generateProperties
  }

  Velocity createVelocityTask(Project project) {
    Velocity velocity = (Velocity) project
      .task(type: Velocity, Velocity.DEFAULT_VELOCITY_TASK_NAME)
    velocity.configure {
      dependsOn(project.tasks.getByName('generateProperties'))
      outputs.upToDateWhen { false }
      inputPath = GenerateProperties.getDefaultOutputDir(project).absolutePath
      outputDir = new File("${project.buildDir}/templates")
    }
    velocity
  }

  ProcessResources configureProcessResourcesTask(Project project) {
    ProcessResources processResources = (ProcessResources) project
      .tasks.getByName('processResources')
    processResources.dependsOn(project.tasks.getByName('velocity'))
    processResources
  }

  StagingExtension createStagingExtension(Project project) {
    project.extensions.create('staging', StagingExtension.class, project)
  }

  Stage createStagingTask(Project project, StagingExtension extension) {
    (Stage) project.task([type: Stage], 'stage') {
      targets = extension.targetValues
    }
  }
}
