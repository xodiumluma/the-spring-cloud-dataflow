/*
 * Copyright 2023 the original author or authors.
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
package org.springframework.cloud.skipper.server.db.migration;

import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.utility.DockerImageName;

import org.springframework.test.context.TestPropertySource;


/**
 * Basic database schema and JPA tests for MariaDB 10.4 or later.
 *
 * @author Corneil du Plessis
 */
@TestPropertySource(properties = {
		"spring.jpa.database-platform=org.hibernate.dialect.MariaDB106Dialect"
})
public class MariaDBSkipperSmokeTest extends AbstractSkipperSmokeTest {

	@BeforeAll
	static void startContainer() {
		container = new MariaDBContainer<>(DockerImageName.parse("mariadb:10.6"));
		container.start();
	}
}
