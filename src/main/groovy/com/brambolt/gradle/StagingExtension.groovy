package com.brambolt.gradle

import com.brambolt.gradle.staging.tasks.Stage
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty

import java.util.regex.Matcher
import java.util.regex.Pattern

import static com.brambolt.gradle.api.provider.Properties.createListProperty
import static com.brambolt.gradle.api.provider.Properties.createMapProperty

class StagingExtension {

  /**
   * Allow case-insensitive alpha-numerical with dashes, underscores.
   *
   * Dots and slashes are not allowed. This is on purpose.
   */
  static final TARGET_PATTERN = '([a-zA-Z0-9_\\-]*)'

  static final PROPERTIES_MASK = "${TARGET_PATTERN}.properties"

  static final PROPERTIES_PATTERN = Pattern.compile(PROPERTIES_MASK)

  static final PROPERTIES_LOAD = { Properties properties, InputStream stream ->
    properties.load(stream)
  }

  static final XML_PROPERTIES_MASK = "${TARGET_PATTERN}.xml"

  static final XML_PROPERTIES_PATTERN = Pattern.compile(XML_PROPERTIES_MASK)

  static final XML_PROPERTIES_LOAD = { Properties properties, InputStream stream ->
    properties.loadFromXML(stream)
  }

  static class Template {

    /**
     * The pattern is used to read the target name from a properties file
     * defining a context - it must define at least one grouping and the
     * target name will be parsed as
     * <pre>
     *   pattern.matcher(fileName).group(1)
     * </pre>
     */
    final Pattern pattern

    /**
     * The closure must accept a properties object and an input stream, and
     * load from the input stream into the properties.
     *
     * <p>If the input stream is for a normal Java properties text file then the
     * closure can simply invoke <code>Properties#load</code>.</p>
     *
     * <p>If the input stream is for an XML-formatted Java properties file then
     * the closure can invoke <code>Properties#loadFromXML</code></p>
     *
     * <p>Any closure implementation is fine as long as the input stream is
     * converted into the properties object in a manner similar to what's done
     * by <code>Properties#load</code> and <code>Properties#loadFromXML</code>.
     * </p>
     */
    final Closure<Void> load

    Template(String mask) {
      this(mask, PROPERTIES_LOAD)
    }

    Template(Pattern pattern) {
      this(pattern, PROPERTIES_LOAD)
    }

    Template(String mask, Closure<Void> load) {
      this(compile(mask), load)
    }

    Template(Pattern pattern, Closure<Void> load) {
      this.pattern = pattern
      this.load = (null != load ? load : PROPERTIES_LOAD)
    }

    Template(Map values) {
      this(getPattern(values), getLoad(values))
    }

    static Pattern compile(String mask) {
      try {
        Pattern.compile(mask)
      } catch (Exception x) {
        throw new GradleException("Not a valid context mask: ${mask}", x)
      }
    }

    static Pattern getPattern(Map values) {
      try {
        (values.containsKey('pattern')
          ? values.pattern as Pattern
          : compile(values.mask as String)) as Pattern
      } catch (Exception x) {
        throw new GradleException("Define a string mask or regular expression pattern", x)
      }
    }

    static Closure<Void> getLoad(Map values) {
      (values.containsKey('load') ? values.load : null) as Closure<Void>
    }
  }

  static final PROPERTIES_TEMPLATE = new Template(PROPERTIES_MASK, PROPERTIES_LOAD)

  static final XML_PROPERTIES_TEMPLATE = new Template(XML_PROPERTIES_MASK, XML_PROPERTIES_LOAD)

  static final TEMPLATE_DEFAULTS = [
    PROPERTIES_TEMPLATE,
    XML_PROPERTIES_TEMPLATE
  ]

  final Project project

  final MapProperty<String, Object> targetValues

  final MapProperty<String, Object> targets

  final ListProperty<Template> templateValues

  StagingExtension(Project project) {
    this.project = project
    targetValues = createMapProperty(project, String, Object, new HashMap<>())
    targets = createMapProperty(project, String, Object, new HashMap<>())
    templateValues = createListProperty(project, Template, new ArrayList<>())
  }

  Stage findStageTask() {
    (Stage) project.tasks.findByPath('stage')
  }

  Stage configureStageTask(Closure closure) {
    Stage stage = findStageTask()
    if (null != stage)
      stage.configure(closure)
    stage
  }

  void targets(Map<String, Object> map) {
    targetValues.putAll(map)
    configureStageTask { Task t ->
      t.targets = targetValues
    }
  }

  void targets(Closure<?> closure) {
    closure.setDelegate(targetValues)
    closure.call()
    configureStageTask { Task t ->
        t.targets = targetValues
    }
  }

  void targetsDir(File targetsDir) {
    if (!templateValues.isPresent() || 1 > templateValues.size())
      templateValues.addAll(TEMPLATE_DEFAULTS)
    Map<String, Object> map = new HashMap<>()
    templateValues.get().each {Template template ->
      map.putAll(parseProperties(targetsDir, template))
    }
    // map.putAll(parseProperties(targetsDir))
    // map.putAll(parseXml(targetsDir))
    targets(map)
  }

  void template(String mask) {
    templateValues.add(new Template(mask))
  }

  void template(Pattern pattern) {
    templateValues.add(new Template(pattern))
  }

  void template(String mask, Closure<Void> load) {
    templateValues.add(new Template(mask, load))
  }

  void template(Pattern pattern, Closure<Void> load) {
    templateValues.add(new Template(pattern, load))
  }

  void template(Map template) {
    templateValues.add(new Template(template))
  }

  void templates(List<Map<String, Object>> values) {
    values.each { template(it) }
  }

  static Map<String, Object> parseProperties(File targetsDir, String mask, Closure<Void> closure) {
    parseProperties(targetsDir, new Template(mask, closure))
  }

  static Map<String, Object> parseProperties(File targetsDir, Template template) {
    Map<String, Object> result = new HashMap<>()
    targetsDir.listFiles(new FilenameFilter() {
      @Override
      boolean accept(File dir, String name) {
        template.pattern.matcher(name).find()
      }
    }).each {result.putAll(parsePropertiesFile(it, template)) }
    result
  }

  static Map<String, Object> parsePropertiesFile(File propertiesFile, Template template) {
    parsePropertiesFile(
      parseTargetName(propertiesFile, template),
      propertiesFile,
      template.load)
  }

  static String parseTargetName(File propertiesFile, Template template) {
    try {
      // We always go after group 1 here, see notes on the template class:
      Matcher matcher = template.pattern.matcher(propertiesFile.name)
      matcher.find()
      matcher.group(1)
    } catch (IllegalStateException | IndexOutOfBoundsException x) {
      // No match found...
      throw new GradleException(
        "Unable to parse target name ${propertiesFile.name} using pattern ${template.pattern.pattern()}: ${propertiesFile}", x)
    }
  }

  static Map<String, Object> parsePropertiesFile(String targetName, File propertiesFile, Closure<Void> closure) {
    InputStream stream = null
    try {
      stream = new FileInputStream(propertiesFile)
      Properties properties = new Properties()
      closure.call(properties, stream)
      createTarget(targetName, properties)
    } catch (Exception x) {
      throw new GradleException("Unable to parse target properties file: ${propertiesFile.absolutePath}", x)
    } finally {
      if (null != stream)
        stream.close()
    }
  }

  static Map<String, Object> createTarget(String targetName, Properties properties) {
    Map<String, Object> target = [:]
    target[targetName] = [
      context: properties,
      name: targetName
    ]
    target
  }
}
