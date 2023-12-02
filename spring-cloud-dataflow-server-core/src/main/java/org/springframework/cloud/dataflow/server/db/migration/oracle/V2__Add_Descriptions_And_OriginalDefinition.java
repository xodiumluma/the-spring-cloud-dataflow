/*
 * Copyright 2019 the original author or authors.
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
package org.springframework.cloud.dataflow.server.db.migration.oracle;

import java.util.Arrays;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import org.springframework.cloud.dataflow.common.flyway.SqlCommand;
import org.springframework.cloud.dataflow.common.flyway.SqlCommandsRunner;

/**
 * This migration class adds description column to stream_definitions and task_definitions
 * tables and original_definition column to stream_definitions.
 *
 * @author Daniel Serleg
 * @author Ilayaperumal Gopinathan
 * @author Michael Minella
 *
 * @since 2.3
 */
public class V2__Add_Descriptions_And_OriginalDefinition extends BaseJavaMigration {

	public final static String ALTER_STREAM_DEFINITION_TABLE_DESC = "alter table stream_definitions add description varchar2(255)";

	public final static String ALTER_STREAM_DEFINITION_TABLE_ORIG_DEF = "alter table stream_definitions add original_definition clob";

	public final static String ALTER_TASK_DEFINITION_TABLE = "" +
			"alter table task_definitions add description varchar2(255)";

	public final static String UPDATE_STREAM_DEFINITION_TABLE_ORIG_DEF = "update stream_definitions set original_definition=definition";

	public final static String CREATE_TASK_METADATA_TABLE =
			"CREATE TABLE task_execution_metadata (\n" +
					"    id number(19,0) NOT NULL,\n" +
					"    task_execution_id  number(19,0) NOT NULL,\n" +
					"    task_execution_manifest CLOB,\n" +
					"    primary key (id),\n" +
					"    CONSTRAINT TASK_METADATA_FK FOREIGN KEY (task_execution_id)\n" +
					"    REFERENCES TASK_EXECUTION(TASK_EXECUTION_ID)\n" +
					")";

	private final static String CREATE_TASK_METADATA_SEQUENCE =
			"CREATE SEQUENCE task_execution_metadata_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807 NOCACHE NOCYCLE";

	private final SqlCommandsRunner runner = new SqlCommandsRunner();

	@Override
	public void migrate(Context context) throws Exception {
		runner.execute(context.getConnection(), Arrays.asList(
				SqlCommand.from(ALTER_STREAM_DEFINITION_TABLE_DESC),
				SqlCommand.from(ALTER_STREAM_DEFINITION_TABLE_ORIG_DEF),
				SqlCommand.from(ALTER_TASK_DEFINITION_TABLE),
				SqlCommand.from(UPDATE_STREAM_DEFINITION_TABLE_ORIG_DEF),
				SqlCommand.from(CREATE_TASK_METADATA_TABLE),
				SqlCommand.from(CREATE_TASK_METADATA_SEQUENCE)));
	}
}
