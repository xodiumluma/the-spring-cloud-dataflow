/*
 * Copyright 2017-2023 the original author or authors.
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
package org.springframework.cloud.dataflow.registry.support;

import java.net.MalformedURLException;
import java.net.URI;

import org.junit.jupiter.api.Test;

import org.springframework.cloud.deployer.resource.docker.DockerResource;
import org.springframework.cloud.deployer.resource.maven.MavenProperties;
import org.springframework.cloud.deployer.resource.maven.MavenResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.UrlResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * @author Mark Pollack
 * @author Ilayaperumal Gopinathan
 * @author Christian Tzolov
 */
public class AppResourceCommonTests {

	private ResourceLoader resourceLoader = mock(ResourceLoader.class);
	private AppResourceCommon appResourceCommon = new AppResourceCommon(new MavenProperties(), resourceLoader);

	@Test
	public void testBadNamedJars() throws Exception {
		UrlResource urlResource = new UrlResource("https://repo.maven.apache.org/maven2/org/springframework/cloud/stream/app/file-sink-rabbit/3.2.1/file-sink-rabbit.jar");
		assertThatIllegalArgumentException().isThrownBy( () -> appResourceCommon.getUrlResourceVersion(urlResource));
	}

	@Test
	public void testInvalidUrlResourceWithoutVersion() throws Exception {
		assertThat(appResourceCommon.getUrlResourceWithoutVersion(
				new UrlResource("https://repo.maven.apache.org/maven2/org/springframework/cloud/stream/app/file-sink-rabbit/3.2.1/file-sink-rabbit-3.2.1.jar")))
				.isEqualTo("https://repo.maven.apache.org/maven2/org/springframework/cloud/stream/app/file-sink-rabbit/3.2.1/file-sink-rabbit");
	}

	@Test
	public void testInvalidURIPath() throws Exception {
		UrlResource urlResource = new UrlResource("https://com.com-0.0.2-SNAPSHOT");
		assertThatThrownBy(() -> appResourceCommon.getUrlResourceVersion(urlResource))
				.hasMessage("URI path doesn't exist");
	}

	@Test
	public void testInvalidUriSchema() {
		assertThatIllegalArgumentException().isThrownBy(() ->
				appResourceCommon.getResource("springcloud/polyglot-python-processor:0.1"))
				.withMessage("Invalid URI schema for resource: " +
					"springcloud/polyglot-python-processor:0.1 Expected URI schema prefix like file://, " +
					"http:// or classpath:// but got none");
	}

	@Test
	public void testDefaultResource() {
		String classpathUri = "classpath:AppRegistryTests-importAll.properties";
		Resource resource = appResourceCommon.getResource(classpathUri);
		assertThat(resource instanceof ClassPathResource).isTrue();
	}

	@Test
	public void testDockerUriString() throws Exception {
		String dockerUri = "docker:springcloudstream/log-sink-rabbit:3.2.1";
		Resource resource = appResourceCommon.getResource(dockerUri);
		assertThat(resource instanceof DockerResource).isTrue();
		assertThat(resource.getURI().toString().equals(dockerUri));
	}

	@Test
	public void testJarMetadataUriDockerApp() throws Exception {
		String appUri = "docker:springcloudstream/log-sink-rabbit:3.2.1";
		String metadataUri = "https://repo.maven.apache.org/maven2/org/springframework/cloud/stream/app/file-sink-rabbit/3.2.1/file-sink-rabbit-3.2.1.jar";
		appResourceCommon.getMetadataResource(new URI(appUri), new URI(metadataUri));
		verify(resourceLoader).getResource(eq(metadataUri));
	}

	@Test
	public void testMetadataUriHttpApp() throws Exception {
		String appUri = "https://repo.maven.apache.org/maven2/org/springframework/cloud/stream/app/file-sink-rabbit/3.2.1/file-sink-rabbit-3.2.1.jar";
		Resource metadataResource = appResourceCommon.getMetadataResource(new URI(appUri), null);
		assertThat(metadataResource instanceof UrlResource).isTrue();
		assertThat(metadataResource.getURI().toString().equals(appUri));
	}

	@Test
	public void testMetadataUriDockerApp() throws Exception {
		String appUri = "docker:springcloudstream/log-sink-rabbit:3.2.1";
		Resource metadataResource = appResourceCommon.getMetadataResource(new URI(appUri), null);
		assertThat(metadataResource).isNotNull();
		assertThat(metadataResource instanceof DockerResource).isTrue();
	}

	@Test
	public void testResourceURIWithMissingFileNameExtension() throws Exception {
		UrlResource urlResource = new UrlResource("https://com.com-0.0.2-SNAPSHOT/test");
		assertThatThrownBy(() -> appResourceCommon.getUrlResourceVersion(urlResource))
				.hasMessage("URI file name extension doesn't exist");
	}

	@Test
	public void testInvalidUrlResourceURI() throws Exception {
		UrlResource urlResource = new UrlResource("https://com.com-0.0.2-SNAPSHOT/test.zip");
		assertThatThrownBy(() -> appResourceCommon.getUrlResourceVersion(urlResource))
				.hasMessageStartingWith("Could not parse version from https://com.com-0.0.2-SNAPSHOT/test.zip, expected format is <artifactId>-<version>.jar");
	}

	@Test
	public void testJars() throws MalformedURLException {
		//Dashes in artifact name
		UrlResource urlResource = new UrlResource("https://repo.maven.apache.org/maven2/org/springframework/cloud/stream/app/file-sink-rabbit/file-sink-rabbit-3.2.1.jar");
		String version = appResourceCommon.getUrlResourceVersion(urlResource);
		assertThat(version).isEqualTo("3.2.1");

		String theRest = appResourceCommon.getResourceWithoutVersion(urlResource);
		assertThat(theRest).isEqualTo("https://repo.maven.apache.org/maven2/org/springframework/cloud/stream/app/file-sink-rabbit/file-sink-rabbit");

		//No dashes in artfiact name - BUILD-SNAPSHOT
		urlResource = new UrlResource("https://repo.maven.apache.org/maven2/org/springframework/cloud/stream/app/file/file-3.2.1-SNAPSHOT.jar");
		version = appResourceCommon.getUrlResourceVersion(urlResource);
		assertThat(version).isEqualTo("3.2.1-SNAPSHOT");
		theRest = appResourceCommon.getResourceWithoutVersion(urlResource);
		assertThat(theRest).isEqualTo("https://repo.maven.apache.org/maven2/org/springframework/cloud/stream/app/file/file");

		//No dashes in artfiact name - RELEASE
		urlResource = new UrlResource("https://repo.maven.apache.org/maven2/org/springframework/cloud/stream/app/file/file-3.2.1.jar");
		version = appResourceCommon.getUrlResourceVersion(urlResource);
		assertThat(version).isEqualTo("3.2.1");
		theRest = appResourceCommon.getResourceWithoutVersion(urlResource);
		assertThat(theRest).isEqualTo("https://repo.maven.apache.org/maven2/org/springframework/cloud/stream/app/file/file");

		//Spring style snapshots naming scheme
		urlResource = new UrlResource("https://repo.maven.apache.org/maven2/org/springframework/cloud/stream/app/file-sink-rabbit/file-sink-rabbit-3.2.1-SNAPSHOT.jar");
		version = appResourceCommon.getUrlResourceVersion(urlResource);
		assertThat(version).isEqualTo("3.2.1-SNAPSHOT");
		theRest = appResourceCommon.getResourceWithoutVersion(urlResource);
		assertThat(theRest).isEqualTo("https://repo.maven.apache.org/maven2/org/springframework/cloud/stream/app/file-sink-rabbit/file-sink-rabbit");

		//Standard maven style naming scheme
		urlResource = new UrlResource("https://repo.maven.apache.org/maven2/org/springframework/cloud/stream/app/file-sink-rabbit/file-sink-rabbit-3.2.1-SNAPSHOT.jar");
		version = appResourceCommon.getUrlResourceVersion(urlResource);
		assertThat(version).isEqualTo("3.2.1-SNAPSHOT");
		theRest = appResourceCommon.getResourceWithoutVersion(urlResource);
		assertThat(theRest).isEqualTo("https://repo.maven.apache.org/maven2/org/springframework/cloud/stream/app/file-sink-rabbit/file-sink-rabbit");
	}

	@Test
	public void testGetResourceWithoutVersion() {
		assertThat(appResourceCommon.getResourceWithoutVersion(
				MavenResource.parse("org.springframework.cloud.stream.app:aggregate-counter-sink-rabbit:war:exec:3.2.1")))
				.isEqualTo("maven://org.springframework.cloud.stream.app:aggregate-counter-sink-rabbit:war:exec");
		assertThat(appResourceCommon.getResourceWithoutVersion(
				MavenResource.parse("org.springframework.cloud.stream.app:aggregate-counter-sink-rabbit::exec:3.2.1")))
				.isEqualTo("maven://org.springframework.cloud.stream.app:aggregate-counter-sink-rabbit:jar:exec");
		assertThat(appResourceCommon.getResourceWithoutVersion(
				MavenResource.parse("org.springframework.cloud.stream.app:aggregate-counter-sink-rabbit:3.2.1")))
				.isEqualTo("maven://org.springframework.cloud.stream.app:aggregate-counter-sink-rabbit:jar");
	}

	@Test
	public void testGetResource() {
		String mavenUri = "maven://org.springframework.cloud.stream.app:aggregate-counter-sink-rabbit:3.2.1";
		Resource resource = appResourceCommon.getResource(mavenUri);
		assertThat(resource).isInstanceOf(MavenResource.class);
	}

	@Test
	public void testGetResourceVersion() {
		String mavenUri = "maven://org.springframework.cloud.stream.app:aggregate-counter-sink-rabbit:3.2.1";
		String version = appResourceCommon.getResourceVersion(appResourceCommon.getResource(mavenUri));
		assertThat(version).isEqualTo("3.2.1");
	}

	@Test
	public void testGetMetadataResourceVersion() {
		String httpUri = "http://repo.maven.apache.org/maven2/org/springframework/cloud/stream/app/cassandra-sink-rabbit/3.2.1-SNAPSHOT/cassandra-sink-rabbit-3.2.1-SNAPSHOT-metadata.jar";
		String version = appResourceCommon.getResourceVersion(appResourceCommon.getResource(httpUri));
		assertThat(version).isEqualTo("3.2.1-SNAPSHOT");
	}
}
