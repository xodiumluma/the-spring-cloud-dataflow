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
package org.springframework.cloud.dataflow.server.db.migration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.testcontainers.containers.MySQLContainer;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Basic database schema and JPA tests for MySQL 8 or later.
 *
 * @author Corneil du Plessis
 */
@Disabled("Will fix once PR is merged to run all tests")
public class MySQL8SmokeTest extends AbstractSmokeTest {

	@BeforeAll
	static void startContainer() {
		container = new MySQLContainer<>("mysql:8");
		container.start();
	}
}
