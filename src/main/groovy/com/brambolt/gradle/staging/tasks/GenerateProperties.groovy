package com.brambolt.gradle.staging.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

import java.nio.file.Path
import java.util.stream.Collectors
import java.util.stream.Stream

import static com.brambolt.gradle.util.Properties.asProperties
import static com.brambolt.gradle.util.Properties.checkStructure
import static groovy.io.FileType.FILES

class GenerateProperties extends DefaultTask {

  /**
   * The directory holding the defaults, normally <code>src/main/defaults</code>.
   */
  // The defaults directory is now optional so we can't annotate it as input:
  // @InputDirectory
  File defaultsDir

  /**
   * The file extension that identifies default templates.
   */
  String defaultsFileExtension = '.defaults.vtl'

  /**
   * The directory holding the templates, normally
   * or <code>src/main/templates</code>.
   */
  @InputDirectory
  File templatesDir

  /**
   * The output directory to generate into.
   */
  @OutputDirectory
  File outputDir

  Map<Path, Map<String, Object>> defaults

  /**
   * The generated properties will be sorted alphabetically if this field is
   * set to true. The default is false, which leaves the properties in the
   * order defined in the source files.
   */
  boolean sort = false

  /**
   * Indicates that the generated properties are structured, if set to true.
   * This means that every result must define the same properties, or in other
   * words, that if one result defines a particular property then every other
   * result must also define a value for that property. Defaults to false. If
   * the value is false then the generated properties are semi-structured,
   * which means that individual results may include properties that some of
   * the other results don't have.
   */
  boolean structured = false

  boolean trim = false

  /**
   * Controls whether the target-specific properties are prepended to the
   * defaults or vice versa. The default is to append, which means the defaults
   * appear first.
   */
  boolean prepend = false

  @Override
  Task configure(Closure closure) {
    super.configure(closure)
    configureDefaults()
    onlyIf { templatesDir.exists() }
    this
  }

  void configureDefaults() {
    if (null == outputDir)
      outputDir = getDefaultOutputDir(project)
    if (null == defaultsDir)
      defaultsDir = getDefaultDefaultsDir(project)
    if (null == templatesDir)
      templatesDir = getDefaultTemplatesDir(project)
    if (null == defaults)
      defaults = readDefaults()
  }

  static File getDefaultOutputDir(Project project) {
    new File("${project.buildDir}/vtl")
  }

  static File getDefaultDefaultsDir(Project project) {
    File defaultsDir = new File("${project.projectDir}/src/main/defaults")
    if (!defaultsDir.exists()) {
      // Create an empty defaults directory so we don't crash...
      defaultsDir = new File("${project.buildDir}/defaults")
      defaultsDir.mkdirs()
    }
    defaultsDir
  }

  static File getDefaultTemplatesDir(Project project) {
    new File("${project.projectDir}/src/main/templates")
  }

  @TaskAction
  void apply() {
    if (!outputDir.exists())
      outputDir.mkdirs()
    if (!defaults.isEmpty())
      applyWithDefaults()
    else applyWithoutDefaults()
  }

  void applyWithDefaults() {
    // If the defaults exist then we run one operation for each set of
    // defaults to generate the output properties:
    defaults.each {applyWithDefaults(it) }
  }

  void applyWithDefaults(Map.Entry<Path, Map<String, Object>> entry) {
    List<File> generatedFiles = generateProperties(entry.key, entry.value as Map)
    if (structured)
      throwIfNotStructured(generatedFiles.collect { asProperties(it) })
  }

  void applyWithoutDefaults() {
    // If there are no defaults then we don't modify the templates:
    // (This is useful when the task runs as part of staging but the
    // staging operation only makes use of Velocity instantiation.)
    project.copy {
      from templatesDir
      into outputDir
    }
    if (structured)
      throwIfNotStructured(
        Arrays.asList(outputDir.listFiles()).collect { asProperties(it) })
  }

  List<File> generateProperties(Path defaultsPath, Map defaults) {
    File resolvedInputDir = templatesDir.toPath().resolve(defaultsPath).toFile().parentFile
    File resolvedOutputDir = outputDir.toPath().resolve(defaultsPath).toFile().parentFile
    File[] propertiesFiles = resolvedInputDir.listFiles({ it.name.startsWith(defaults.basename as String) } as FileFilter)
    propertiesFiles.collect { File file ->
      generateProperties(defaults.lines as List, file, resolvedOutputDir) }
  }

  File generateProperties(List<String> defaults, File file, File resolvedOutputDir) {
    File propertiesFile = new File(resolvedOutputDir, file.name)
    propertiesFile.text = generateProperties(defaults, file.readLines())
    project.logger.info("Generated ${propertiesFile.absolutePath}")
    propertiesFile
  }

  String generateProperties(List<String> defaults, List<String> properties) {
    // If we prepend, then the defaults appear after the properties.
    // But if we append, then the defaults appear first, followed by the properties.
    // (If nothing is specified then we append.)
    List<String> prefix = prepend ? properties : defaults
    List<String> suffix = prepend ? defaults : properties
    List<String> builder = new ArrayList<>(prefix)
    builder.addAll(suffix)
    Stream<String> lines = builder.stream()
    if (trim)
      lines = lines.filter({ String line -> !line.trim().isEmpty() })
    if (sort)
      lines = lines.sorted()
    String.join(System.getProperty('line.separator'),
      lines.collect(Collectors.toList()))
  }

  /**
   * Collects the available defaults into a map keyed on the property filenames.
   *
   * The values are string lists holding the lines of the defaults files.
   *
   * @return A map with the available defaults.
   */
  Map<Path, Map<String, Object>> readDefaults() {
    findDefaults().inject([:]) { Map defaults, File file ->
      defaults.put(relativizeDefaults(file), readDefaults(file))
      defaults
    }
  }

  /**
   * Finds every file in the defaults source set with the defaults extension.
   *
   * @return The list of files in the defaults source set ending with the defaults extension.
   */
  List<File> findDefaults() {
    List<File> defaults = []
    if (null != defaultsDir && defaultsDir.isDirectory())
      defaultsDir.eachFileRecurse(FILES) { File file ->
        if (file.name.endsWith(defaultsFileExtension))
          defaults.add(file)
      }
    defaults
  }

  Path relativizeDefaults(File defaultsFile) {
    defaultsDir.toPath().relativize(defaultsFile.toPath())
  }

  Map<String, Object> readDefaults(File defaultsFile) {
    if (null == defaultsFile || !defaultsFile.exists() || !defaultsFile.isFile())
      throw new GradleException(
        "Unable to access defaults at: ${null != defaultsFile ?: '(no path)'}")
    [
      basename: getPropertiesBasename(defaultsFile),
      file: defaultsFile,
      lines: defaultsFile.readLines().stream()
        .filter({ String line -> !line.trim().isEmpty() })
        .collect(Collectors.toList())
    ]
  }

  /**
   * Strips the <code>.defaults.vtl</code> suffix from the defaults filename to
   * produce the basename of the properties file the defaults are for.
   *
   * @param defaultsFile The defaults file to derive a basename from
   * @return The basename of the properties file the defaults are for
   */
  String getPropertiesBasename(File defaultsFile) {
    defaultsFile.name.substring(0, defaultsFile.name.size() - defaultsFileExtension.length())
  }

  void throwIfNotStructured(List<Properties> properties) {
    Map<String, Set> results = checkStructure(properties)
    if (results.difference.isEmpty())
      return
    String formattedDifference = results.difference.sort().join('\n\t')
    String message = "Detected disjoint property sets: \n\t${formattedDifference}"
    throw new IllegalStateException(message)
  }
}