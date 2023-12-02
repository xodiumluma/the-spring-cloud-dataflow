/*
 * Copyright 2016-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.dataflow.rest.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import org.springframework.util.FileCopyUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link DeploymentPropertiesUtils}.
 *
 * @author Janne Valkealahti
 * @author Christian Tzolov
 * @author Ilayaperumal Gopinathan
 * @author Glenn Renfro
 */
public class DeploymentPropertiesUtilsTests {

	private static void assertArrays(String[] left, String[] right) {
		ArrayList<String> params = new ArrayList<>(Arrays.asList(left));
		assertThat(DeploymentPropertiesUtils.removeQuoting(params)).contains(right);
	}

	@Test
	public void testDeploymentPropertiesParsing() {
		Map<String, String> props = DeploymentPropertiesUtils.parse("app.foo.bar=v, app.foo.wizz=v2  , deployer.foo"
				+ ".pot=fern, app.other.key = value  , deployer.other.cow = meww, scheduler.other.key = baz");
		assertThat(props.entrySet()).contains(entry("app.foo.bar", "v"));
		assertThat(props.entrySet()).contains(entry("app.other.key", "value"));
		assertThat(props.entrySet()).contains(entry("app.foo.wizz", "v2"));
		assertThat(props.entrySet()).contains(entry("deployer.foo.pot", "fern"));
		assertThat(props.entrySet()).contains(entry("deployer.other.cow", "meww"));
		assertThat(props.entrySet()).contains(entry("scheduler.other.key", "baz"));

		props = DeploymentPropertiesUtils.parse("app.f=v");
		assertThat(props.entrySet()).contains(entry("app.f", "v"));

		props = DeploymentPropertiesUtils.parse("app.foo1=bar1,app.foo2=bar2,app.foo3=bar3,xxx3");
		assertThat(props.entrySet()).contains(entry("app.foo1", "bar1"));
		assertThat(props.entrySet()).contains(entry("app.foo2", "bar2"));
		assertThat(props.entrySet()).contains(entry("app.foo3", "bar3,xxx3"));

		props = DeploymentPropertiesUtils.parse("deployer.foo1 = bar1 , app.foo2= bar2,  deployer.foo3  = bar3,xxx3");
		assertThat(props.entrySet()).contains(entry("deployer.foo1", "bar1"));
		assertThat(props.entrySet()).contains(entry("app.foo2", "bar2"));
		assertThat(props.entrySet()).contains(entry("deployer.foo3", "bar3,xxx3"));

		props = DeploymentPropertiesUtils.parse("app.*.count=1");
		assertThat(props.entrySet()).contains(entry("app.*.count", "1"));

		props = DeploymentPropertiesUtils.parse("app.*.my-count=1");
		assertThat(props.entrySet()).contains(entry("app.*.my-count", "1"));

		props = DeploymentPropertiesUtils.parse("app.transform.producer.partitionKeyExpression=fakeExpression('xxx')");
		assertThat(props.entrySet()).contains(entry("app.transform.producer.partitionKeyExpression", "fakeExpression('xxx')"));

		try {
			DeploymentPropertiesUtils.parse("invalidkeyvalue");
			fail("Illegal Argument Exception expected.");
		}
		catch (Exception e) {
			assertThat(e.getMessage()).isEqualTo("Only deployment property keys starting with 'app.' or 'scheduler' or 'deployer.'  or 'version.' allowed. Not invalidkeyvalue");
		}

		props = DeploymentPropertiesUtils.parse("deployer.foo=bar,invalidkeyvalue2");
		assertThat(props.size()).isEqualTo(1);
		assertThat(props.entrySet()).contains(entry("deployer.foo", "bar,invalidkeyvalue2"));

		props = DeploymentPropertiesUtils.parse("app.foo.bar1=jee1,jee2,jee3,deployer.foo.bar2=jee4,jee5,jee6");
		assertThat(props.entrySet()).contains(entry("app.foo.bar1", "jee1,jee2,jee3"));
		assertThat(props.entrySet()).contains(entry("deployer.foo.bar2", "jee4,jee5,jee6"));

		props = DeploymentPropertiesUtils.parse("app.foo.bar1=xxx=1,app.foo.bar2=xxx=2");
		assertThat(props.entrySet()).contains(entry("app.foo.bar1", "xxx=1"));
		assertThat(props.entrySet()).contains(entry("app.foo.bar2", "xxx=2"));

		props = DeploymentPropertiesUtils.parse("app.foo.bar1=xxx=1,test=value,app.foo.bar2=xxx=2");
		assertThat(props.entrySet()).contains(entry("app.foo.bar1", "xxx=1,test=value"));
		assertThat(props.entrySet()).contains(entry("app.foo.bar2", "xxx=2"));
	}


	@Test
	public void testDeploymentPropertiesParsing2() {
		List<String> props = DeploymentPropertiesUtils.parseParamList("app.foo.bar=v, app.foo.wizz=v2  , deployer.foo"
				+ ".pot=fern, app.other.key = value  , deployer.other.cow = meww,special=koza=boza,more", ",");

		assertTrue(props.contains("app.foo.bar=v"));
		assertTrue(props.contains(" app.other.key = value  "));
		assertTrue(props.contains(" app.foo.wizz=v2  "));
		assertTrue(props.contains(" deployer.foo.pot=fern"));
		assertTrue(props.contains(" deployer.other.cow = meww,special=koza=boza,more"));

		try {
			DeploymentPropertiesUtils.parseParamList("a=b", " ");
			fail("Illegal Argument Exception expected.");
		}
		catch (Exception e) {
			assertThat(e.getMessage()).isEqualTo("Only deployment property keys starting with 'app.' or 'scheduler' or 'deployer.'  or 'version.' allowed. Not a=b");
		}

		props = DeploymentPropertiesUtils.parseArgumentList("a=b c=d", " ");
		assertTrue(props.contains("c=d"));
		assertTrue(props.contains("a=b"));

		props = DeploymentPropertiesUtils.parseArgumentList("a=b    c=d   ", " ");

		assertTrue(props.contains("a=b"));
		assertTrue(props.contains("c=d"));

		props = DeploymentPropertiesUtils.parseArgumentList("foo1=bar1 foo2=bar2 foo3=bar3 xxx3", " ");
		assertTrue(props.contains("foo1=bar1"));
		assertTrue(props.contains("foo2=bar2"));
		assertTrue(props.contains("foo3=bar3 xxx3"));
	}

	@Test
	public void parseArgumentTestsWithQuotes() {

		List<String> props = DeploymentPropertiesUtils.parseArgumentList("a=\"b c\" e=f g=h", " ");
		assertTrue(props.contains("a=\"b c\""));
		assertTrue(props.contains("e=f"));
		assertTrue(props.contains("g=h"));
		props = DeploymentPropertiesUtils.parseArgumentList("--composedTaskArguments=\"1.timestamp.format=YYYY " +
				"--timestamp.timestamp.format=MM --foo=bar bar=\"bazzz buzz\"\" " +
				"a=b c=d --foo=bar", " ");
		assertTrue(props.contains("--composedTaskArguments=\"1.timestamp.format=YYYY " +
				"--timestamp.timestamp.format=MM --foo=bar bar=\"bazzz buzz\"\""));
		assertTrue(props.contains("a=b"));
		assertTrue(props.contains("c=d"));
		assertTrue(props.contains("--foo=bar"));
	}

	@Test
	public void parseArgumentTestsWithMultipleQuotes() {

		List<String> props = DeploymentPropertiesUtils.parseArgumentList("arg2=\"Argument 2\" arg3=val3", " ");
		assertTrue(props.contains("arg2=\"Argument 2\""));
		assertTrue(props.contains("arg3=val3"));

		props = DeploymentPropertiesUtils.parseArgumentList("arg0=val0 arg1=val1 arg2=\"Argument 2\" arg3=val3", " ");
		assertTrue(props.contains("arg0=val0"));
		assertTrue(props.contains("arg1=val1"));
		assertTrue(props.contains("arg2=\"Argument 2\""));
		assertTrue(props.contains("arg3=val3"));

		props = DeploymentPropertiesUtils.parseArgumentList("-arg1=val1 arg2=\"Argument 2\" arg3=val3", " ");
		assertTrue(props.contains("-arg1=val1"));
		assertTrue(props.contains("arg2=\"Argument 2\""));
		assertTrue(props.contains("arg3=val3"));

		props = DeploymentPropertiesUtils.parseArgumentList("-arg1=val1 arg2=\"Argument 2\" arg3=val3 arg4=\"Argument 4\"", " ");
		assertTrue(props.contains("-arg1=val1"));
		assertTrue(props.contains("arg2=\"Argument 2\""));
		assertTrue(props.contains("arg3=val3"));
		assertTrue(props.contains("arg4=\"Argument 4\""));

		props = DeploymentPropertiesUtils.parseArgumentList("-arg1=val1 arg2=\"Argument 2\" arg3=\"val3\" arg4=\"Argument 4\"", " ");
		assertTrue(props.contains("-arg1=val1"));
		assertTrue(props.contains("arg2=\"Argument 2\""));
		assertTrue(props.contains("arg3=\"val3\""));
		assertTrue(props.contains("arg4=\"Argument 4\""));

		props = DeploymentPropertiesUtils.parseArgumentList("-arg1=\"val1\" arg2=\"Argument 2\" arg3=\"val3\" arg4=\"Argument 4\"", " ");
		assertTrue(props.contains("-arg1=\"val1\""));
		assertTrue(props.contains("arg2=\"Argument 2\""));
		assertTrue(props.contains("arg3=\"val3\""));
		assertTrue(props.contains("arg4=\"Argument 4\""));

	}

	@Test
	public void testLongDeploymentPropertyValues() {
		Map<String, String> props = DeploymentPropertiesUtils
				.parse("app.foo.bar=FoooooooooooooooooooooBar,app.foo" + ".bar2=FoooooooooooooooooooooBar");
		assertThat(props.entrySet()).contains(entry("app.foo.bar", "FoooooooooooooooooooooBar"));
		props = DeploymentPropertiesUtils.parse("app.foo.bar=FooooooooooooooooooooooooooooooooooooooooooooooooooooBar");
		assertThat(props.entrySet()).contains(entry("app.foo.bar", "FooooooooooooooooooooooooooooooooooooooooooooooooooooBar"));
	}

	@Test
	public void testDeployerProperties() {
		Map<String, String> props = new LinkedHashMap<>();
		props.put("app.myapp.foo", "bar");
		props.put("deployer.myapp.count", "2");
		props.put("deployer.myapp.foo", "bar");
		props.put("deployer.otherapp.count", "5");
		props.put("deployer.*.precedence", "wildcard");
		props.put("deployer.myapp.precedence", "app");
		Map<String, String> result = DeploymentPropertiesUtils.extractAndQualifyDeployerProperties(props, "myapp");

		assertThat(result.entrySet()).contains(entry("spring.cloud.deployer.count", "2"));
		assertThat(result.entrySet()).contains(entry("spring.cloud.deployer.foo", "bar"));
		assertThat(result.entrySet()).contains(entry("spring.cloud.deployer.precedence", "app"));
		assertThat(result.keySet()).doesNotContain("app.myapp.foo");
	}

	@Test
	public void testDeployerPropertiesWithApp() {
		Map<String, String> props = new LinkedHashMap<>();
		props.put("app.myapp.foo", "bar");
		props.put("deployer.myapp.count", "2");
		props.put("deployer.myapp.foo", "bar");
		props.put("deployer.otherapp.count", "5");
		props.put("deployer.*.precedence", "wildcard");
		props.put("deployer.myapp.precedence", "app");
		Map<String, String> result = DeploymentPropertiesUtils.qualifyDeployerProperties(props, "myapp");

		assertThat(result.entrySet()).contains(entry("spring.cloud.deployer.count", "2"));
		assertThat(result.entrySet()).contains(entry("spring.cloud.deployer.foo", "bar"));
		assertThat(result.entrySet()).contains(entry("spring.cloud.deployer.precedence", "app"));
		assertThat(result.keySet()).contains("app.myapp.foo");
	}

	@Test
	public void testCommandLineParamsParsing() {
		assertArrays(new String[] { "--format=x,y,z" }, new String[] { "--format=x,y,z" });
		assertArrays(new String[] { "--format=yyyy-MM-dd" }, new String[] { "--format=yyyy-MM-dd" });
		assertArrays(new String[] { "'--format=yyyy-MM-dd HH:mm:ss.SSS'" },
				new String[] { "--format=yyyy-MM-dd HH:mm:ss" + ".SSS" });
		assertArrays(new String[] { "\"--format=yyyy-MM-dd HH:mm:ss.SSS\"" },
				new String[] { "--format=yyyy-MM-dd HH:mm:ss" + ".SSS" });
		assertArrays(new String[] { "--format='yyyy-MM-dd HH:mm:ss.SSS'" },
				new String[] { "--format=yyyy-MM-dd HH:mm:ss" + ".SSS" });
		assertArrays(new String[] { "--format=\"yyyy-MM-dd HH:mm:ss.SSS\"" },
				new String[] { "--format=yyyy-MM-dd HH:mm:ss" + ".SSS" });
		assertArrays(new String[] { "--foo1=bar1 --foo2=bar2" }, new String[] { "--foo1=bar1", "--foo2=bar2" });
		assertArrays(new String[] { "--foo1=bar1", "--foo2=bar2" }, new String[] { "--foo1=bar1", "--foo2=bar2" });
		assertArrays(new String[] { " --foo1=bar1 ", " --foo2=bar2 " }, new String[] { "--foo1=bar1", "--foo2=bar2" });
		assertArrays(new String[] { "'--format=yyyy-MM-dd HH:mm:ss.SSS'", "--foo1=bar1" },
				new String[] { "--format=yyyy-MM-dd HH:mm:ss.SSS", "--foo1=bar1" });
	}

	@Test
	public void testParseDeploymentProperties() throws IOException {
		File file = Files.createTempFile(null, ".yaml").toFile();
		FileCopyUtils.copy("app.foo1:\n  bar1: spam".getBytes(), file);

		Map<String, String> props = DeploymentPropertiesUtils.parseDeploymentProperties("app.foo2=bar2", file, 0);
		assertThat(props.size()).isEqualTo(1);
		assertThat(props.get("app.foo2")).isEqualTo("bar2");

		props = DeploymentPropertiesUtils.parseDeploymentProperties("foo2=bar2", file, 1);
		assertThat(props.size()).isEqualTo(1);
		assertThat(props.get("app.foo1.bar1")).isEqualTo("spam");
	}
}
