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

package org.springframework.cloud.dataflow.server.batch;

import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import org.springframework.cloud.dataflow.core.database.support.DatabaseType;

@Testcontainers
class JdbcJobSearchableExecutionMariadbDaoTests extends AbstractJdbcJobSearchableExecutionDaoTests {

	@Container
	private static final JdbcDatabaseContainer dbContainer = new MariaDBContainer("mariadb:10.9.3");

	@BeforeEach
	void prepareForTest() throws Exception {
		super.prepareForTest(dbContainer, "mariadb", DatabaseType.MARIADB);
	}
}
