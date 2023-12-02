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

import org.springframework.cloud.dataflow.common.flyway.AbstractMigration;

/**
 * Provides for converting lower case table names to uppercase to ensure it works correctly with a MariaDB or MySQL installation with case-sensitive table or column names.
 * @author Corneil du Plessis
 */
public abstract class AbstractCaseSensitiveMigration extends AbstractMigration {
	protected final static String RENAME_TASK_EXECUTION_METADATA_LC = "alter table task_execution_metadata rename to task_execution_metadata_lc";

	protected final static String RENAME_TASK_EXECUTION_METADATA = "alter table task_execution_metadata_lc rename to TASK_EXECUTION_METADATA";

	protected final static String RENAME_TASK_EXECUTION_METADATA_SEQ_LC_TBL = "alter table task_execution_metadata_seq rename to task_execution_metadata_seq_lc";

	protected final static String RENAME_TASK_EXECUTION_METADATA_SEQ_TBL = "alter table task_execution_metadata_seq_lc rename to TASK_EXECUTION_METADATA_SEQ";

	protected final static String RENAME_TASK_EXECUTION_METADATA_SEQ_LC = "alter sequence task_execution_metadata_seq rename to task_execution_metadata_seq_lc";

	protected final static String RENAME_TASK_EXECUTION_METADATA_SEQ = "alter sequence task_execution_metadata_seq_lc rename to TASK_EXECUTION_METADATA_SEQ";

	protected final static String CREATE_SEQUENCE_TASK_EXECUTION_METADATA_SEQ_LC = "CREATE SEQUENCE task_execution_metadata_seq_lc";

	protected final static String ALTER_SEQUENCE_TASK_EXECUTION_METADATA_SEQ_LC = "select setval(task_execution_metadata_seq_lc, select nextval(task_execution_metadata_seq), false)";

	protected final static String DROP_SEQUENCE_TASK_EXECUTION_METADATA_SEQ = "drop sequence task_execution_metadata_seq";

	protected final static String CREATE_SEQUENCE_TASK_EXECUTION_METADATA_SEQ = "create sequence TASK_EXECUTION_METADATA_SEQ START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775806 INCREMENT BY 1 NOCACHE NOCYCLE ENGINE=InnoDB";

	protected final static String ALTER_SEQUENCE_TASK_EXECUTION_METADATA_SEQ = "select setval(TASK_EXECUTION_METADATA_SEQ, select nextval(task_execution_metadata_seq_lc), false)";

	protected final static String DROP_SEQUENCE_TASK_EXECUTION_METADATA_SEQ_LC = "drop sequence task_execution_metadata_seq_lc";

	public AbstractCaseSensitiveMigration() {
		super(null);
	}

}
