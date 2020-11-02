/*
 * Copyright 2017-2020 Brambolt ehf.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.brambolt.gradle

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

import static StagingExtension.PROPERTIES_TEMPLATE
import static StagingExtension.XML_PROPERTIES_TEMPLATE

class StagingExtensionSpec extends Specification {

  @Rule TemporaryFolder testProjectDir = new TemporaryFolder()

  def 'can parse targets dir'() {
    given:
    File targetsDir = testProjectDir.newFolder('targets')
    File t1PropertiesFile = new File(targetsDir, 't1.properties')
    t1PropertiesFile.text = '''
a=1
'''
    File t2PropertiesFile = new File(targetsDir, 't2.properties')
    t2PropertiesFile.text = '''
a=2
'''
    when:
    def targets = StagingExtension.parseProperties(targetsDir, PROPERTIES_TEMPLATE)
    then:
    2 == targets.size()
    targets.containsKey('t1')
    2 == targets.t1.size()
    targets.t1.context.a == '1'
    targets.t1.name == 't1'
    targets.containsKey('t2')
    2 == targets.t2.size()
    targets.t2.context.a == '2'
    targets.t2.name == 't2'
  }

  def 'can parse targets XML'() {
    given:
    File targetsDir = testProjectDir.newFolder('targets')
    File t1PropertiesFile = new File(targetsDir, 't1.xml')
    t1PropertiesFile.text = '''<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">
<properties>
    <entry key="a">1</entry>
</properties>
'''
    File t2PropertiesFile = new File(targetsDir, 't2.xml')
    t2PropertiesFile.text = '''<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">
<properties>
    <entry key="a">2</entry>
</properties>
'''
    when:
    def targets = StagingExtension.parseProperties(targetsDir, XML_PROPERTIES_TEMPLATE)
    then:
    2 == targets.size()
    targets.containsKey('t1')
    2 == targets.t1.size()
    targets.t1.context.a == '1'
    targets.t1.name == 't1'
    targets.containsKey('t2')
    2 == targets.t2.size()
    targets.t2.context.a == '2'
    targets.t2.name == 't2'
  }
}
