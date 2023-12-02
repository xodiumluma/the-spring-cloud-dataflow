/*
 * Copyright 2021-2023 the original author or authors.
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

package org.springframework.cloud.dataflow.server.service.impl;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersIncrementer;
import org.springframework.batch.core.JobParametersValidator;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.SimpleJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.dataflow.aggregate.task.AggregateExecutionSupport;
import org.springframework.cloud.dataflow.aggregate.task.AggregateTaskExplorer;
import org.springframework.cloud.dataflow.aggregate.task.TaskDefinitionReader;
import org.springframework.cloud.dataflow.aggregate.task.TaskRepositoryContainer;
import org.springframework.cloud.dataflow.core.Launcher;
import org.springframework.cloud.dataflow.core.TaskDefinition;
import org.springframework.cloud.dataflow.schema.SchemaVersionTarget;
import org.springframework.cloud.dataflow.schema.service.SchemaService;
import org.springframework.cloud.dataflow.server.batch.SearchableJobExecutionDao;
import org.springframework.cloud.dataflow.server.configuration.TaskServiceDependencies;
import org.springframework.cloud.dataflow.server.job.LauncherRepository;
import org.springframework.cloud.dataflow.server.repository.JobExecutionDaoContainer;
import org.springframework.cloud.dataflow.server.repository.JobRepositoryContainer;
import org.springframework.cloud.dataflow.server.repository.TaskBatchDaoContainer;
import org.springframework.cloud.dataflow.server.repository.TaskDefinitionRepository;
import org.springframework.cloud.dataflow.server.service.TaskDeleteService;
import org.springframework.cloud.dataflow.server.service.TaskExecutionService;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.cloud.task.batch.listener.TaskBatchDao;
import org.springframework.cloud.task.repository.TaskExecution;
import org.springframework.cloud.task.repository.TaskRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * @author Glenn Renfro
 * @author Corneil du Plessis
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = { TaskServiceDependencies.class }, properties = {
		"spring.main.allow-bean-definition-overriding=true" })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
public abstract class DefaultTaskDeleteServiceTests {

	private final static String FAKE_PLATFORM = "fakeplatformname";

	private final static String BASE_TASK_NAME = "myTask";

	private final static String TASK_NAME_ORIG = BASE_TASK_NAME + "-ORIG";

	private final static String JOB_NAME = "testjob";

	@Autowired
	TaskRepositoryContainer taskRepositoryContainer;

	@Autowired
	DataSourceProperties dataSourceProperties;

	@Autowired
	DataSource dataSource;

	@Autowired
	LauncherRepository launcherRepository;

	@Autowired
	TaskLauncher taskLauncher;

	@Autowired
	TaskDefinitionRepository taskDefinitionRepository;

	@Autowired
	AggregateTaskExplorer taskExplorer;

	@Autowired
	SchemaService schemaService;

	@Autowired
	TaskDeleteService taskDeleteService;

	@Autowired
	TaskExecutionService taskExecutionService;

	@Autowired
	JobRepositoryContainer jobRepositoryContainer;

	@Autowired
	TaskBatchDaoContainer taskBatchDaoContainer;

	@Autowired
	JobExecutionDaoContainer jobExecutionDaoContainer;

	@Autowired
	AggregateExecutionSupport aggregateExecutionSupport;

	JobLauncherTestUtils jobLauncherTestUtils;

	@Autowired
	TaskDefinitionReader taskDefinitionReader;

	@BeforeEach
	public void setup() {
		setupTest(dataSource);
		this.jobLauncherTestUtils = jobLauncherTestUtils();
	}

	@Test
	public void deleteAllTest() throws Exception{
		createTaskExecutions(50);
		assertThat(this.taskExplorer.getTaskExecutionCount()).isEqualTo(50);
		Collection<String> taskNames = this.taskExplorer.getTaskNames();

		for(String taskName : taskNames) {
			this.taskDeleteService.deleteTaskExecutions(taskName, true);
		}
		assertThat(this.taskExplorer.getTaskExecutionCount()).isEqualTo(0);
		for(SchemaVersionTarget target : schemaService.getTargets().getSchemas()) {
			SearchableJobExecutionDao searchableJobExecutionDao = jobExecutionDaoContainer.get(target.getName());
			assertThat(searchableJobExecutionDao.countJobExecutions(JOB_NAME)).isEqualTo(0);
		}

	}


	@Test
	public void deleteSetTest() throws Exception{
		createTaskExecutions(50);
		assertThat(this.taskExplorer.getTaskExecutionCount()).isEqualTo(50);
		SchemaVersionTarget target = aggregateExecutionSupport.findSchemaVersionTarget(TASK_NAME_ORIG, taskDefinitionReader);
		assertThat(target).isNotNull();
		this.taskDeleteService.deleteTaskExecutions(Collections.singleton(taskExplorer.getLatestTaskExecutionForTaskName(TASK_NAME_ORIG).getExecutionId()), target.getName());
		assertThat(this.taskExplorer.getTaskExecutionCount()).isEqualTo(49);
		SearchableJobExecutionDao searchableJobExecutionDao = jobExecutionDaoContainer.get(target.getName());
		assertThat(searchableJobExecutionDao.countJobExecutions(JOB_NAME)).isEqualTo(49);
	}

	private void createTaskExecutions(int numberOfExecutions) throws Exception{
		List<String> args = new ArrayList<>();
		args.add("test=value");
		args.add("anothertest=anotherValue");
		SchemaVersionTarget schemaVersionTarget = aggregateExecutionSupport.findSchemaVersionTarget(TASK_NAME_ORIG, taskDefinitionReader);
		TaskRepository taskRepository = this.taskRepositoryContainer.get(schemaVersionTarget.getName());
		for (int i = 1; i <= numberOfExecutions; i++) {
			TaskExecution taskExecution = taskRepository.createTaskExecution(new TaskExecution(i, 0, TASK_NAME_ORIG,
					new Date(), new Date(), "", args, "", null,
					null));
			taskRepository.completeTaskExecution(taskExecution.getExecutionId(), 0, new Date(), "complete");
			JobExecution jobExecution = this.jobLauncherTestUtils.launchJob();
			TaskBatchDao taskBatchDao = taskBatchDaoContainer.get(SchemaVersionTarget.defaultTarget().getName());
			taskBatchDao.saveRelationship(taskExecution, jobExecution);
		}
	}

	public void setupTest(DataSource dataSource) {
		this.launcherRepository.save(new Launcher(FAKE_PLATFORM, "local", taskLauncher));
		this.taskDefinitionRepository.save(new TaskDefinition(TASK_NAME_ORIG, "demo"));
		JdbcTemplate template = new JdbcTemplate(dataSource);
		template.execute("DELETE FROM TASK_EXECUTION_PARAMS");
		template.execute("DELETE FROM TASK_TASK_BATCH;");
		template.execute("DELETE FROM TASK_EXECUTION;");
		template.execute("DELETE FROM BATCH_JOB_EXECUTION_PARAMS");
		template.execute("DELETE FROM BATCH_JOB_EXECUTION_CONTEXT;");
		template.execute("DELETE FROM BATCH_JOB_EXECUTION");
		template.execute("DELETE FROM BOOT3_TASK_EXECUTION_PARAMS");
		template.execute("DELETE FROM BOOT3_TASK_TASK_BATCH;");
		template.execute("DELETE FROM BOOT3_TASK_EXECUTION;");
		template.execute("DELETE FROM BOOT3_BATCH_JOB_EXECUTION_PARAMS");
		template.execute("DELETE FROM BOOT3_BATCH_JOB_EXECUTION_CONTEXT;");
		template.execute("DELETE FROM BOOT3_BATCH_JOB_EXECUTION");
	}

	@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
	@TestPropertySource(properties = { "spring.cloud.dataflow.task.executionDeleteChunkSize=5"})
	public static class DefaultTaskDeleteServiceWithChunksTests extends DefaultTaskDeleteServiceTests {

	}

	@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
	public static class DefaultTaskDeleteServiceNoChunkTests extends DefaultTaskDeleteServiceTests {

	}

	JobLauncher jobLauncher(JobRepository jobRepository) {
		SimpleJobLauncher launcher = new SimpleJobLauncher();
		launcher.setJobRepository(jobRepository);
		launcher.setTaskExecutor(task -> {
		});
		return launcher;
	}

	public JobLauncherTestUtils jobLauncherTestUtils() {
		JobLauncherTestUtils jobLauncherTestUtils = new JobLauncherTestUtils();
		JobRepository jobRepository = jobRepositoryContainer.get(SchemaVersionTarget.defaultTarget().getName());
		jobLauncherTestUtils.setJobRepository(jobRepository);
		jobLauncherTestUtils.setJobLauncher(jobLauncher(jobRepository));
		jobLauncherTestUtils.setJob(new Job() {
			@Override
			public String getName() {
				return JOB_NAME;
			}

			@Override
			public boolean isRestartable() {
				return true;
			}

			@Override
			public void execute(JobExecution execution) {

			}

			@Override
			public JobParametersIncrementer getJobParametersIncrementer() {
				return null;
			}

			@Override
			public JobParametersValidator getJobParametersValidator() {
				return parameters -> {
				};
			}
		});
		return jobLauncherTestUtils;
	}
}
