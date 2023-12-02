/*
 * Copyright 2019-2023 the original author or authors.
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

package org.springframework.cloud.dataflow.server.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameter;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.launch.NoSuchJobExecutionException;
import org.springframework.batch.core.repository.dao.JdbcJobExecutionDao;
import org.springframework.batch.core.repository.dao.StepExecutionDao;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.PagingQueryProvider;
import org.springframework.batch.item.database.support.SqlPagingQueryProviderFactoryBean;
import org.springframework.cloud.dataflow.rest.job.JobInstanceExecutions;
import org.springframework.cloud.dataflow.rest.job.TaskJobExecution;
import org.springframework.cloud.dataflow.schema.AppBootSchemaVersion;
import org.springframework.cloud.dataflow.schema.SchemaVersionTarget;
import org.springframework.cloud.dataflow.schema.service.SchemaService;
import org.springframework.cloud.dataflow.server.batch.JobService;
import org.springframework.cloud.dataflow.server.converter.DateToStringConverter;
import org.springframework.cloud.dataflow.server.converter.StringToDateConverter;
import org.springframework.cloud.dataflow.server.service.JobServiceContainer;
import org.springframework.cloud.dataflow.server.service.impl.OffsetOutOfBoundsException;
import org.springframework.core.convert.support.ConfigurableConversionService;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.data.convert.Jsr310Converters;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.util.Assert;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

/**
 * Stores job execution information to a JDBC DataSource. Mirrors the {@link JdbcJobExecutionDao}
 * but contains Spring Cloud Data Flow specific operations. This functionality might
 * be migrated to Spring Batch itself eventually.
 *
 * @author Gunnar Hillert
 * @author Corneil du Plessis
 */
public class JdbcAggregateJobQueryDao implements AggregateJobQueryDao {
	private final static Logger logger = LoggerFactory.getLogger(JdbcAggregateJobQueryDao.class);

	private static final String GET_COUNT = "SELECT COUNT(1) from AGGREGATE_JOB_EXECUTION";

	private static final String GET_COUNT_BY_DATE = "SELECT COUNT(1) from AGGREGATE_JOB_EXECUTION WHERE START_TIME BETWEEN ? AND ?";

	private static final String GET_COUNT_BY_JOB_NAME = "SELECT COUNT(E.JOB_EXECUTION_ID) from AGGREGATE_JOB_INSTANCE I" +
			" JOIN AGGREGATE_JOB_EXECUTION E ON I.JOB_INSTANCE_ID=E.JOB_INSTANCE_ID AND I.SCHEMA_TARGET=E.SCHEMA_TARGET" +
			" JOIN AGGREGATE_TASK_BATCH B ON E.JOB_EXECUTION_ID = B.JOB_EXECUTION_ID AND E.SCHEMA_TARGET = B.SCHEMA_TARGET" +
			" JOIN AGGREGATE_TASK_EXECUTION T ON B.TASK_EXECUTION_ID = T.TASK_EXECUTION_ID AND B.SCHEMA_TARGET = T.SCHEMA_TARGET" +
			" WHERE I.JOB_NAME LIKE ?";

	private static final String GET_COUNT_BY_STATUS = "SELECT COUNT(E.JOB_EXECUTION_ID) from AGGREGATE_JOB_INSTANCE I" +
			" JOIN AGGREGATE_JOB_EXECUTION E ON I.JOB_INSTANCE_ID=E.JOB_INSTANCE_ID AND I.SCHEMA_TARGET=E.SCHEMA_TARGET" +
			" JOIN AGGREGATE_TASK_BATCH B ON E.JOB_EXECUTION_ID = B.JOB_EXECUTION_ID AND E.SCHEMA_TARGET = B.SCHEMA_TARGET" +
			" JOIN AGGREGATE_TASK_EXECUTION T ON B.TASK_EXECUTION_ID = T.TASK_EXECUTION_ID AND B.SCHEMA_TARGET = T.SCHEMA_TARGET" +
			" WHERE E.STATUS = ?";

	private static final String GET_COUNT_BY_JOB_INSTANCE_ID = "SELECT COUNT(E.JOB_EXECUTION_ID) from AGGREGATE_JOB_INSTANCE I" +
			" JOIN AGGREGATE_JOB_EXECUTION E ON I.JOB_INSTANCE_ID=E.JOB_INSTANCE_ID AND I.SCHEMA_TARGET=E.SCHEMA_TARGET" +
			" WHERE I.JOB_INSTANCE_ID = ? AND I.SCHEMA_TARGET = ?";

	private static final String GET_COUNT_BY_TASK_EXECUTION_ID = "SELECT COUNT(T.TASK_EXECUTION_ID) FROM AGGREGATE_JOB_EXECUTION E" +
			" JOIN AGGREGATE_TASK_BATCH B ON E.JOB_EXECUTION_ID = B.JOB_EXECUTION_ID AND E.SCHEMA_TARGET = B.SCHEMA_TARGET" +
			" JOIN AGGREGATE_TASK_EXECUTION T ON B.TASK_EXECUTION_ID = T.TASK_EXECUTION_ID AND B.SCHEMA_TARGET = T.SCHEMA_TARGET" +
			" WHERE T.TASK_EXECUTION_ID = ? AND T.SCHEMA_TARGET = ?";

	private static final String GET_COUNT_BY_JOB_NAME_AND_STATUS = "SELECT COUNT(E.JOB_EXECUTION_ID) FROM AGGREGATE_JOB_INSTANCE I" +
			" JOIN AGGREGATE_JOB_EXECUTION E ON I.JOB_INSTANCE_ID = E.JOB_INSTANCE_ID AND I.SCHEMA_TARGET = E.SCHEMA_TARGET" +
			" JOIN AGGREGATE_TASK_BATCH B ON E.JOB_EXECUTION_ID = B.JOB_EXECUTION_ID AND E.SCHEMA_TARGET = B.SCHEMA_TARGET" +
			" JOIN AGGREGATE_TASK_EXECUTION T ON B.TASK_EXECUTION_ID = T.TASK_EXECUTION_ID AND B.SCHEMA_TARGET = T.SCHEMA_TARGET" +
			" WHERE I.JOB_NAME LIKE ? AND E.STATUS = ?";

	private static final String FIELDS = "E.JOB_EXECUTION_ID as JOB_EXECUTION_ID, E.START_TIME as START_TIME," +
			" E.END_TIME as END_TIME, E.STATUS as STATUS, E.EXIT_CODE as EXIT_CODE, E.EXIT_MESSAGE as EXIT_MESSAGE," +
			" E.CREATE_TIME as CREATE_TIME, E.LAST_UPDATED as LAST_UPDATED, E.VERSION as VERSION," +
			" I.JOB_INSTANCE_ID as JOB_INSTANCE_ID, I.JOB_NAME as JOB_NAME, T.TASK_EXECUTION_ID as TASK_EXECUTION_ID," +
			" E.SCHEMA_TARGET as SCHEMA_TARGET";

	private static final String FIELDS_WITH_STEP_COUNT = FIELDS +
			", (SELECT COUNT(*) FROM AGGREGATE_STEP_EXECUTION S WHERE S.JOB_EXECUTION_ID = E.JOB_EXECUTION_ID AND S.SCHEMA_TARGET = E.SCHEMA_TARGET) as STEP_COUNT";


	private static final String GET_RUNNING_EXECUTIONS = "SELECT " + FIELDS +
			" from AGGREGATE_JOB_EXECUTION E" +
			" join AGGREGATE_JOB_INSTANCE I ON E.JOB_INSTANCE_ID = I.JOB_INSTANCE_ID AND E.SCHEMA_TARGET = I.SCHEMA_TARGET" +
			" where and E.END_TIME is NULL";

	private static final String NAME_FILTER = "I.JOB_NAME LIKE ?";

	private static final String DATE_RANGE_FILTER = "E.START_TIME BETWEEN ? AND ?";

	private static final String JOB_INSTANCE_ID_FILTER = "I.JOB_INSTANCE_ID = ? AND I.SCHEMA_TARGET = ?";

	private static final String STATUS_FILTER = "E.STATUS = ?";

	private static final String NAME_AND_STATUS_FILTER = "I.JOB_NAME LIKE ? AND E.STATUS = ?";

	private static final String TASK_EXECUTION_ID_FILTER =
			"B.JOB_EXECUTION_ID = E.JOB_EXECUTION_ID AND B.SCHEMA_TARGET = E.SCHEMA_TARGET AND B.TASK_EXECUTION_ID = ? AND E.SCHEMA_TARGET = ?";


	private static final String FROM_CLAUSE_TASK_EXEC_BATCH = "JOIN AGGREGATE_TASK_BATCH B ON E.JOB_EXECUTION_ID = B.JOB_EXECUTION_ID AND E.SCHEMA_TARGET = B.SCHEMA_TARGET" +
			" JOIN AGGREGATE_TASK_EXECUTION T ON B.TASK_EXECUTION_ID = T.TASK_EXECUTION_ID AND B.SCHEMA_TARGET = T.SCHEMA_TARGET";

	private static final String FIND_PARAMS_FROM_ID2 = "SELECT JOB_EXECUTION_ID, KEY_NAME, TYPE_CD, "
			+ "STRING_VAL, DATE_VAL, LONG_VAL, DOUBLE_VAL, IDENTIFYING, 'boot2' as SCHEMA_TARGET from %PREFIX%JOB_EXECUTION_PARAMS where JOB_EXECUTION_ID = ?";

	private static final String FIND_PARAMS_FROM_ID3 = "SELECT JOB_EXECUTION_ID, PARAMETER_NAME, PARAMETER_TYPE, PARAMETER_VALUE, IDENTIFYING, 'boot3' as SCHEMA_TARGET" +
			" from %PREFIX%JOB_EXECUTION_PARAMS where JOB_EXECUTION_ID = ?";

	private static final String FIND_JOB_BY = "SELECT I.JOB_INSTANCE_ID as JOB_INSTANCE_ID, I.JOB_NAME as JOB_NAME, I.SCHEMA_TARGET as SCHEMA_TARGET," +
			" E.JOB_EXECUTION_ID as JOB_EXECUTION_ID, E.START_TIME as START_TIME, E.END_TIME as END_TIME, E.STATUS as STATUS, E.EXIT_CODE as EXIT_CODE, E.EXIT_MESSAGE as EXIT_MESSAGE, E.CREATE_TIME as CREATE_TIME," +
			" E.LAST_UPDATED as LAST_UPDATED, E.VERSION as VERSION, T.TASK_EXECUTION_ID as TASK_EXECUTION_ID," +
			" (SELECT COUNT(*) FROM AGGREGATE_STEP_EXECUTION S WHERE S.JOB_EXECUTION_ID = E.JOB_EXECUTION_ID AND S.SCHEMA_TARGET = E.SCHEMA_TARGET) as STEP_COUNT" +
			" from AGGREGATE_JOB_INSTANCE I" +
			" JOIN AGGREGATE_JOB_EXECUTION E ON I.JOB_INSTANCE_ID = E.JOB_INSTANCE_ID AND I.SCHEMA_TARGET = E.SCHEMA_TARGET" +
			" LEFT OUTER JOIN AGGREGATE_TASK_BATCH TT ON E.JOB_EXECUTION_ID = TT.JOB_EXECUTION_ID AND E.SCHEMA_TARGET = TT.SCHEMA_TARGET" +
			" LEFT OUTER JOIN AGGREGATE_TASK_EXECUTION T ON TT.TASK_EXECUTION_ID = T.TASK_EXECUTION_ID AND TT.SCHEMA_TARGET = T.SCHEMA_TARGET";

	private static final String FIND_JOB_BY_NAME_INSTANCE_ID = FIND_JOB_BY +
			" where I.JOB_NAME = ? AND I.JOB_INSTANCE_ID = ?";

	private static final String FIND_JOB_BY_INSTANCE_ID_SCHEMA = FIND_JOB_BY +
			" where I.JOB_INSTANCE_ID = ? AND I.SCHEMA_TARGET = ?";

	private static final String FIND_JOBS_FIELDS = "I.JOB_INSTANCE_ID as JOB_INSTANCE_ID, I.JOB_NAME as JOB_NAME, I.SCHEMA_TARGET as SCHEMA_TARGET," +
			" E.JOB_EXECUTION_ID as JOB_EXECUTION_ID, E.START_TIME as START_TIME, E.END_TIME as END_TIME, E.STATUS as STATUS, E.EXIT_CODE as EXIT_CODE, E.EXIT_MESSAGE as EXIT_MESSAGE, E.CREATE_TIME as CREATE_TIME," +
			" E.LAST_UPDATED as LAST_UPDATED, E.VERSION as VERSION, T.TASK_EXECUTION_ID as TASK_EXECUTION_ID";

	private static final String FIND_JOBS_FIELDS_WITH_STEP_COUNT = FIND_JOBS_FIELDS +
			", (SELECT COUNT(*) FROM AGGREGATE_STEP_EXECUTION S WHERE S.JOB_EXECUTION_ID = E.JOB_EXECUTION_ID AND S.SCHEMA_TARGET = E.SCHEMA_TARGET) as STEP_COUNT";

	private static final String FIND_JOBS_FROM = "LEFT OUTER JOIN AGGREGATE_TASK_BATCH TT ON E.JOB_EXECUTION_ID = TT.JOB_EXECUTION_ID AND E.SCHEMA_TARGET = TT.SCHEMA_TARGET" +
			" LEFT OUTER JOIN AGGREGATE_TASK_EXECUTION T ON TT.TASK_EXECUTION_ID = T.TASK_EXECUTION_ID AND TT.SCHEMA_TARGET = T.SCHEMA_TARGET";

	private static final String FIND_JOBS_WHERE = "I.JOB_NAME LIKE ?";

	private static final String FIND_BY_ID_SCHEMA = "E.JOB_EXECUTION_ID = ? AND E.SCHEMA_TARGET = ?";

	private final PagingQueryProvider allExecutionsPagingQueryProvider;


	private final PagingQueryProvider byJobNameAndStatusPagingQueryProvider;

	private final PagingQueryProvider byStatusPagingQueryProvider;

	private final PagingQueryProvider byJobNameWithStepCountPagingQueryProvider;


	private final PagingQueryProvider executionsByDateRangeWithStepCountPagingQueryProvider;

	private final PagingQueryProvider byJobInstanceIdWithStepCountPagingQueryProvider;

	private final PagingQueryProvider byTaskExecutionIdWithStepCountPagingQueryProvider;

	private final PagingQueryProvider jobExecutionsPagingQueryProviderByName;

	private final PagingQueryProvider allExecutionsPagingQueryProviderNoStepCount;

	private final PagingQueryProvider byJobNamePagingQueryProvider;

	private final PagingQueryProvider byJobExecutionIdAndSchemaPagingQueryProvider;


	private final DataSource dataSource;

	private final JdbcTemplate jdbcTemplate;

	private final SchemaService schemaService;

	private final JobServiceContainer jobServiceContainer;

	private final ConfigurableConversionService conversionService = new DefaultConversionService();

	private final Map<String, StepExecutionDao> stepExecutionDaoContainer = new HashMap<>();

	public JdbcAggregateJobQueryDao(DataSource dataSource, SchemaService schemaService, JobServiceContainer jobServiceContainer) throws Exception {
		this.dataSource = dataSource;
		this.jdbcTemplate = new JdbcTemplate(dataSource);
		this.schemaService = schemaService;
		this.jobServiceContainer = jobServiceContainer;

		conversionService.addConverter(new DateToStringConverter());
		conversionService.addConverter(new StringToDateConverter());
		Jsr310Converters.getConvertersToRegister().forEach(conversionService::addConverter);

		allExecutionsPagingQueryProvider = getPagingQueryProvider(FIELDS_WITH_STEP_COUNT, FROM_CLAUSE_TASK_EXEC_BATCH, null);

		executionsByDateRangeWithStepCountPagingQueryProvider = getPagingQueryProvider(FIELDS_WITH_STEP_COUNT, FROM_CLAUSE_TASK_EXEC_BATCH, DATE_RANGE_FILTER);
		allExecutionsPagingQueryProviderNoStepCount = getPagingQueryProvider(FROM_CLAUSE_TASK_EXEC_BATCH, null);
		byStatusPagingQueryProvider = getPagingQueryProvider(FROM_CLAUSE_TASK_EXEC_BATCH, STATUS_FILTER);
		byJobNameAndStatusPagingQueryProvider = getPagingQueryProvider(FROM_CLAUSE_TASK_EXEC_BATCH, NAME_AND_STATUS_FILTER);
		byJobNamePagingQueryProvider = getPagingQueryProvider(FROM_CLAUSE_TASK_EXEC_BATCH, NAME_FILTER);
		byJobNameWithStepCountPagingQueryProvider = getPagingQueryProvider(FIELDS_WITH_STEP_COUNT, FROM_CLAUSE_TASK_EXEC_BATCH, NAME_FILTER);

		byJobInstanceIdWithStepCountPagingQueryProvider = getPagingQueryProvider(FIELDS_WITH_STEP_COUNT, FROM_CLAUSE_TASK_EXEC_BATCH, JOB_INSTANCE_ID_FILTER);
		byTaskExecutionIdWithStepCountPagingQueryProvider = getPagingQueryProvider(FIELDS_WITH_STEP_COUNT, FROM_CLAUSE_TASK_EXEC_BATCH, TASK_EXECUTION_ID_FILTER);
		jobExecutionsPagingQueryProviderByName = getPagingQueryProvider(FIND_JOBS_FIELDS, FIND_JOBS_FROM, FIND_JOBS_WHERE, Collections.singletonMap("E.JOB_EXECUTION_ID", Order.DESCENDING));
		byJobExecutionIdAndSchemaPagingQueryProvider = getPagingQueryProvider(FIELDS_WITH_STEP_COUNT, FROM_CLAUSE_TASK_EXEC_BATCH, FIND_BY_ID_SCHEMA);

	}

	@Override
	public Page<JobInstanceExecutions> listJobInstances(String jobName, Pageable pageable) throws NoSuchJobException {
		int total = countJobExecutions(jobName);
		if (total == 0) {
			throw new NoSuchJobException("No Job with that name either current or historic: [" + jobName + "]");
		}
		List<JobInstanceExecutions> taskJobInstancesForJobName = getTaskJobInstancesForJobName(jobName, pageable);
		return new PageImpl<>(taskJobInstancesForJobName, pageable, total);

	}

	@Override
	public JobInstanceExecutions getJobInstanceExecution(String jobName, long instanceId) {
		logger.debug("getJobInstanceExecution:{}:{}:{}", jobName, instanceId, FIND_JOB_BY_NAME_INSTANCE_ID);
		List<JobInstanceExecutions> executions = jdbcTemplate.query(FIND_JOB_BY_NAME_INSTANCE_ID, new JobInstanceExecutionsExtractor(true), jobName, instanceId);
		if (executions == null || executions.isEmpty()) {
			return null;
		} else if (executions.size() > 1) {
			throw new RuntimeException("Expected a single JobInstanceExecutions not " + executions.size());
		}
		return executions.get(0);
	}

	@Override
	public JobInstanceExecutions getJobInstanceExecutions(long jobInstanceId, String schemaTarget) {
		List<JobInstanceExecutions> executions = jdbcTemplate.query(FIND_JOB_BY_INSTANCE_ID_SCHEMA, new JobInstanceExecutionsExtractor(true), jobInstanceId, schemaTarget);
		if (executions == null || executions.isEmpty()) {
			return null;
		} else if (executions.size() > 1) {
			throw new RuntimeException("Expected a single JobInstanceExecutions not " + executions.size());
		}
		JobInstanceExecutions jobInstanceExecution = executions.get(0);
		if (!ObjectUtils.isEmpty(jobInstanceExecution.getTaskJobExecutions())) {
			jobInstanceExecution.getTaskJobExecutions().forEach((execution) ->
				jobServiceContainer.get(execution.getSchemaTarget()).addStepExecutions(execution.getJobExecution())
			);
		}
		return jobInstanceExecution;
	}

	@Override
	public Page<TaskJobExecution> listJobExecutions(String jobName, BatchStatus status, Pageable pageable) throws NoSuchJobExecutionException {
		int total = countJobExecutions(jobName, status);
		List<TaskJobExecution> executions = getJobExecutions(jobName, status, getPageOffset(pageable), pageable.getPageSize());
		Assert.isTrue(total >= executions.size(), () -> "Expected total at least " + executions.size() + " not " + total);
		return new PageImpl<>(executions, pageable, total);
	}

	@Override
	public Page<TaskJobExecution> listJobExecutionsBetween(Date fromDate, Date toDate, Pageable pageable) {
		int total = countJobExecutionsByDate(fromDate, toDate);
		List<TaskJobExecution> executions = total > 0
				? getTaskJobExecutionsByDate(fromDate, toDate, getPageOffset(pageable), pageable.getPageSize())
				: Collections.emptyList();
		return new PageImpl<>(executions, pageable, total);
	}


	@Override
	public Page<TaskJobExecution> listJobExecutionsWithSteps(Pageable pageable) {
		int total = countJobExecutions();
		List<TaskJobExecution> jobExecutions = total > 0
				? getJobExecutionsWithStepCount(getPageOffset(pageable), pageable.getPageSize())
				: Collections.emptyList();
		return new PageImpl<>(jobExecutions, pageable, total);
	}

	@Override
	public Page<TaskJobExecution> listJobExecutionsWithStepCount(Pageable pageable) {
		int total = countJobExecutions();
		List<TaskJobExecution> jobExecutions = total > 0
				? getJobExecutionsWithStepCount(getPageOffset(pageable), pageable.getPageSize())
				: Collections.emptyList();
		return new PageImpl<>(jobExecutions, pageable, total);
	}

	@Override
	public Page<TaskJobExecution> listJobExecutionsForJobWithStepCountFilteredByJobInstanceId(int jobInstanceId, String schemaTarget, Pageable pageable) {
		int total = countJobExecutionsByInstanceId(jobInstanceId, schemaTarget);
		List<TaskJobExecution> jobExecutions = total > 0
				? getJobExecutionsWithStepCountFilteredByJobInstanceId(jobInstanceId, schemaTarget, getPageOffset(pageable), pageable.getPageSize())
				: Collections.emptyList();
		return new PageImpl<>(jobExecutions, pageable, total);
	}


	@Override
	public Page<TaskJobExecution> listJobExecutionsForJobWithStepCountFilteredByTaskExecutionId(int taskExecutionId, String schemaTarget, Pageable pageable) {
		int total = countJobExecutionsByTaskExecutionId(taskExecutionId, schemaTarget);
		List<TaskJobExecution> jobExecutions = total > 0
				? getJobExecutionsWithStepCountFilteredByTaskExecutionId(taskExecutionId, schemaTarget, getPageOffset(pageable), pageable.getPageSize())
				: Collections.emptyList();
		return new PageImpl<>(jobExecutions, pageable, total);
	}

	@Override
	public Page<TaskJobExecution> listJobExecutionsForJobWithStepCount(String jobName, Pageable pageable) throws NoSuchJobException {
		int total = countJobExecutions(jobName);
		if(total == 0) {
			throw new NoSuchJobException("No Job with that name either current or historic: [" + jobName + "]");
		}
		List<TaskJobExecution> jobExecutions = total > 0
				? getJobExecutionsWithStepCount(jobName, getPageOffset(pageable), pageable.getPageSize())
				: Collections.emptyList();
		return new PageImpl<>(jobExecutions, pageable, total);
	}

	@Override
	public TaskJobExecution getJobExecution(long jobExecutionId, String schemaTarget) throws NoSuchJobExecutionException {
		List<TaskJobExecution> jobExecutions = getJobExecutionPage(jobExecutionId, schemaTarget);
		if (jobExecutions.isEmpty()) {
			throw new NoSuchJobExecutionException(String.format("Job id %s for schema target %s not found", jobExecutionId, schemaTarget));
		}
		if (jobExecutions.size() > 1) {
			logger.debug("Too many job executions:{}", jobExecutions);
			logger.warn("Expected only 1 job for {}: not {}", jobExecutionId, jobExecutions.size());
		}

		TaskJobExecution taskJobExecution = jobExecutions.get(0);
		JobService jobService = jobServiceContainer.get(taskJobExecution.getSchemaTarget());
		jobService.addStepExecutions(taskJobExecution.getJobExecution());
		return taskJobExecution;
	}

	private List<TaskJobExecution> getJobExecutionPage(long jobExecutionId, String schemaTarget) {
		return queryForProvider(
				byJobExecutionIdAndSchemaPagingQueryProvider,
				new JobExecutionRowMapper(true),
				0,
				2,
				jobExecutionId,
				schemaTarget
		);
	}

	private int countJobExecutions() {
		logger.debug("countJobExecutions:{}", GET_COUNT);
		Integer count = jdbcTemplate.queryForObject(GET_COUNT, Integer.class);
		return count != null ? count : 0;
	}

	private int countJobExecutionsByDate(Date fromDate, Date toDate) {
		Assert.notNull(fromDate, "fromDate must not be null");
		Assert.notNull(toDate, "toDate must not be null");
		logger.debug("countJobExecutionsByDate:{}:{}:{}", fromDate, toDate, GET_COUNT_BY_DATE);
		Integer count = jdbcTemplate.queryForObject(GET_COUNT_BY_DATE, Integer.class, fromDate, toDate);
		return count != null ? count : 0;
	}

	private int countJobExecutions(String jobName) {
		logger.debug("countJobExecutions:{}:{}", jobName, GET_COUNT_BY_JOB_NAME);
		Integer count = jdbcTemplate.queryForObject(GET_COUNT_BY_JOB_NAME, Integer.class, jobName);
		return count != null ? count : 0;
	}

	private int countJobExecutions(BatchStatus status) {
		logger.debug("countJobExecutions:{}:{}", status, GET_COUNT_BY_STATUS);
		Integer count = jdbcTemplate.queryForObject(GET_COUNT_BY_STATUS, Integer.class, status.name());
		return count != null ? count : 0;
	}

	private int countJobExecutions(String jobName, BatchStatus status) {
		logger.debug("countJobExecutions:{}:{}", jobName, status);
		Integer count;
		if (StringUtils.hasText(jobName) && status != null) {
			logger.debug("countJobExecutions:{}:{}:{}", jobName, status, GET_COUNT_BY_JOB_NAME_AND_STATUS);
			count = jdbcTemplate.queryForObject(GET_COUNT_BY_JOB_NAME_AND_STATUS, Integer.class, jobName, status.name());
		} else if (status != null) {
			logger.debug("countJobExecutions:{}:{}", status, GET_COUNT_BY_STATUS);
			count = jdbcTemplate.queryForObject(GET_COUNT_BY_STATUS, Integer.class, status.name());
		} else if (StringUtils.hasText(jobName)) {
			logger.debug("countJobExecutions:{}:{}", jobName, GET_COUNT_BY_JOB_NAME);
			count = jdbcTemplate.queryForObject(GET_COUNT_BY_JOB_NAME, Integer.class, jobName);
		} else {
			count = jdbcTemplate.queryForObject(GET_COUNT, Integer.class);
		}
		return count != null ? count : 0;
	}

	private int countJobExecutionsByInstanceId(int jobInstanceId, String schemaTarget) {
		if (!StringUtils.hasText(schemaTarget)) {
			schemaTarget = SchemaVersionTarget.defaultTarget().getName();
		}
		logger.debug("countJobExecutionsByInstanceId:{}:{}:{}", jobInstanceId, schemaTarget, GET_COUNT_BY_JOB_INSTANCE_ID);
		Integer count = jdbcTemplate.queryForObject(GET_COUNT_BY_JOB_INSTANCE_ID, Integer.class, jobInstanceId, schemaTarget);
		return count != null ? count : 0;
	}

	private int countJobExecutionsByTaskExecutionId(int taskExecutionId, String schemaTarget) {
		if (!StringUtils.hasText(schemaTarget)) {
			schemaTarget = SchemaVersionTarget.defaultTarget().getName();
		}
		logger.debug("countJobExecutionsByTaskExecutionId:{}:{}:{}", taskExecutionId, schemaTarget, GET_COUNT_BY_TASK_EXECUTION_ID);
		Integer count = jdbcTemplate.queryForObject(GET_COUNT_BY_TASK_EXECUTION_ID, Integer.class, taskExecutionId, schemaTarget);
		return count != null ? count : 0;
	}

	private List<TaskJobExecution> getJobExecutionsWithStepCountFilteredByJobInstanceId(
			int jobInstanceId,
			String schemaTarget,
			int start,
			int count
	) {
		if (!StringUtils.hasText(schemaTarget)) {
			schemaTarget = SchemaVersionTarget.defaultTarget().getName();
		}
		return queryForProvider(
				byJobInstanceIdWithStepCountPagingQueryProvider,
				new JobExecutionRowMapper(true),
				start,
				count,
				jobInstanceId,
				schemaTarget
		);
	}

	private List<TaskJobExecution> getJobExecutionsWithStepCountFilteredByTaskExecutionId(
			int taskExecutionId,
			String schemaTarget,
			int start,
			int count
	) {
		if (!StringUtils.hasText(schemaTarget)) {
			schemaTarget = SchemaVersionTarget.defaultTarget().getName();
		}
		return queryForProvider(
				byTaskExecutionIdWithStepCountPagingQueryProvider,
				new JobExecutionRowMapper(true),
				start,
				count,
				taskExecutionId,
				schemaTarget
		);
	}

	private List<TaskJobExecution> getJobExecutions(String jobName, BatchStatus status, int start, int count) throws NoSuchJobExecutionException {
		if (StringUtils.hasText(jobName) && status != null) {
			return queryForProvider(byJobNameAndStatusPagingQueryProvider, new JobExecutionRowMapper(false), start, count, jobName, status.name());
		} else if (status != null) {
			return queryForProvider(byStatusPagingQueryProvider, new JobExecutionRowMapper(false), start, count, status.name());
		} else if (StringUtils.hasText(jobName)) {
			return queryForProvider(byJobNamePagingQueryProvider, new JobExecutionRowMapper(false), start, count, jobName);
		}
		return queryForProvider(allExecutionsPagingQueryProviderNoStepCount, new JobExecutionRowMapper(false), start, count);
	}

	private List<TaskJobExecution> getJobExecutionsWithStepCount(String jobName, int start, int count) {
		return queryForProvider(byJobNameWithStepCountPagingQueryProvider, new JobExecutionRowMapper(true), start, count, jobName);
	}

	public List<TaskJobExecution> getJobExecutionsWithStepCount(int start, int count) {
		return queryForProvider(allExecutionsPagingQueryProvider, new JobExecutionRowMapper(true), start, count);
	}

	protected JobParameters getJobParameters(Long executionId, String schemaTarget) {
		final Map<String, JobParameter> map = new HashMap<>();
		final SchemaVersionTarget schemaVersionTarget = schemaService.getTarget(schemaTarget);
		boolean boot2 = AppBootSchemaVersion.BOOT2 == schemaVersionTarget.getSchemaVersion();
		RowCallbackHandler handler;
		if (boot2) {
			handler = rs -> {
				String keyName = rs.getString("KEY_NAME");
				JobParameter.ParameterType type = JobParameter.ParameterType.valueOf(rs.getString("TYPE_CD"));
				boolean identifying = rs.getString("IDENTIFYING").equalsIgnoreCase("Y");
				JobParameter value;
				switch (type) {
					case STRING:
						value = new JobParameter(rs.getString("STRING_VAL"), identifying);
						break;
					case LONG:
						long longValue = rs.getLong("LONG_VAL");
						value = new JobParameter(rs.wasNull() ? null : longValue, identifying);
						break;
					case DOUBLE:
						double doubleValue = rs.getDouble("DOUBLE_VAL");
						value = new JobParameter(rs.wasNull() ? null : doubleValue, identifying);
						break;
					case DATE:
						value = new JobParameter(rs.getTimestamp("DATE_VAL"), identifying);
						break;
					default:
						logger.error("Unknown type:{} for {}", type, keyName);
						return;
				}
				map.put(keyName, value);
			};
		} else {
			handler = rs -> {
				String parameterName = rs.getString("PARAMETER_NAME");
				Class<?> parameterType = null;
				try {
					parameterType = Class.forName(rs.getString("PARAMETER_TYPE"));
				} catch (ClassNotFoundException e) {
					throw new RuntimeException(e);
				}
				String stringValue = rs.getString("PARAMETER_VALUE");
				boolean identifying = rs.getString("IDENTIFYING").equalsIgnoreCase("Y");
				Object typedValue = conversionService.convert(stringValue, parameterType);
				JobParameter value;
				if (typedValue instanceof String) {
					value = new JobParameter((String) typedValue, identifying);
				} else if (typedValue instanceof Date) {
					value = new JobParameter((Date) typedValue, identifying);
				} else if (typedValue instanceof Double) {
					value = new JobParameter((Double) typedValue, identifying);
				} else if (typedValue instanceof Number) {
					value = new JobParameter(((Number) typedValue).doubleValue(), identifying);
				} else if (typedValue instanceof Instant) {
					value = new JobParameter(new Date(((Instant) typedValue).toEpochMilli()), identifying);
				} else {

					value = new JobParameter(typedValue != null ? typedValue.toString() : null, identifying);
				}
				map.put(parameterName, value);
			};
		}

		jdbcTemplate.query(
				getQuery(
						boot2 ? FIND_PARAMS_FROM_ID2 : FIND_PARAMS_FROM_ID3,
						schemaVersionTarget.getBatchPrefix()
				),
				handler,
				executionId
		);
		return new JobParameters(map);
	}

	private <T, P extends PagingQueryProvider, M extends RowMapper<T>> List<T> queryForProvider(P pagingQueryProvider, M mapper, int start, int count, Object... arguments) {
		if (start <= 0) {
			String sql = pagingQueryProvider.generateFirstPageQuery(count);
			if (logger.isDebugEnabled()) {
				logger.debug("queryFirstPage:{}:{}:{}:{}", sql, start, count, Arrays.asList(arguments));
			}
			return jdbcTemplate.query(sql, mapper, arguments);
		} else {
			try {
				String sqlJump = pagingQueryProvider.generateJumpToItemQuery(start, count);
				if (logger.isDebugEnabled()) {
					logger.debug("queryJumpToItem:{}:{}:{}:{}", sqlJump, start, count, Arrays.asList(arguments));
				}
				Long startValue;
				startValue = jdbcTemplate.queryForObject(sqlJump, Long.class, arguments);
				List<Object> args = new ArrayList<>(Arrays.asList(arguments));
				args.add(startValue);
				String sql = pagingQueryProvider.generateRemainingPagesQuery(count);
				if (logger.isDebugEnabled()) {
					logger.debug("queryRemaining:{}:{}:{}:{}", sql, start, count, args);
				}
				return jdbcTemplate.query(sql, mapper, args.toArray());
			} catch (IncorrectResultSizeDataAccessException x) {
				return Collections.emptyList();
			}
		}
	}

	private <T, P extends PagingQueryProvider, R extends ResultSetExtractor<List<T>>> List<T> queryForProvider(P pagingQueryProvider, R extractor, int start, int count, Object... arguments) {
		if (start <= 0) {
			String sql = pagingQueryProvider.generateFirstPageQuery(count);
			if (logger.isDebugEnabled()) {
				logger.debug("queryFirstPage:{}:{}:{}:{}", sql, start, count, Arrays.asList(arguments));
			}
			return jdbcTemplate.query(sql, extractor, arguments);
		} else {
			String sqlJump = pagingQueryProvider.generateJumpToItemQuery(start, count);
			if (logger.isDebugEnabled()) {
				logger.debug("queryJumpToItem:{}:{}:{}:{}", sqlJump, start, count, Arrays.asList(arguments));
			}
			Long startValue = jdbcTemplate.queryForObject(sqlJump, Long.class, arguments);
			List<Object> args = new ArrayList<>(Arrays.asList(arguments));
			args.add(startValue);
			String sql = pagingQueryProvider.generateRemainingPagesQuery(count);
			if (logger.isDebugEnabled()) {
				logger.debug("queryRemaining:{}:{}:{}:{}", sql, start, count, args);
			}
			return jdbcTemplate.query(sql, extractor, args.toArray());
		}
	}

	private List<JobInstanceExecutions> getTaskJobInstancesForJobName(String jobName, Pageable pageable) {
		Assert.notNull(pageable, "pageable must not be null");
		Assert.notNull(jobName, "jobName must not be null");
		int start = getPageOffset(pageable);
		int count = pageable.getPageSize();
		return queryForProvider(jobExecutionsPagingQueryProviderByName, new JobInstanceExecutionsExtractor(false), start, count, jobName);
	}

	private TaskJobExecution createJobExecutionFromResultSet(ResultSet rs, int row, boolean readStepCount) throws SQLException {
		long taskExecutionId = rs.getLong("TASK_EXECUTION_ID");
		Long jobExecutionId = rs.getLong("JOB_EXECUTION_ID");
		JobExecution jobExecution;
		String schemaTarget = rs.getString("SCHEMA_TARGET");
		JobParameters jobParameters = getJobParameters(jobExecutionId, schemaTarget);

		JobInstance jobInstance = new JobInstance(rs.getLong("JOB_INSTANCE_ID"), rs.getString("JOB_NAME"));
		jobExecution = new JobExecution(jobInstance, jobParameters);
		jobExecution.setId(jobExecutionId);

		jobExecution.setStartTime(rs.getTimestamp("START_TIME"));
		jobExecution.setEndTime(rs.getTimestamp("END_TIME"));
		jobExecution.setStatus(BatchStatus.valueOf(rs.getString("STATUS")));
		jobExecution.setExitStatus(new ExitStatus(rs.getString("EXIT_CODE"), rs.getString("EXIT_MESSAGE")));
		jobExecution.setCreateTime(rs.getTimestamp("CREATE_TIME"));
		jobExecution.setLastUpdated(rs.getTimestamp("LAST_UPDATED"));
		jobExecution.setVersion(rs.getInt("VERSION"));

		return readStepCount ?
				new TaskJobExecution(taskExecutionId, jobExecution, true, rs.getInt("STEP_COUNT"), schemaTarget) :
				new TaskJobExecution(taskExecutionId, jobExecution, true, schemaTarget);
	}

	private List<TaskJobExecution> getTaskJobExecutionsByDate(Date startDate, Date endDate, int start, int count) {
		return queryForProvider(
				executionsByDateRangeWithStepCountPagingQueryProvider,
				new JobExecutionRowMapper(true),
				start,
				count,
				startDate,
				endDate
		);
	}

	private class JobInstanceExecutionsExtractor implements ResultSetExtractor<List<JobInstanceExecutions>> {
		final boolean readStepCount;

		public JobInstanceExecutionsExtractor(boolean readStepCount) {
			this.readStepCount = readStepCount;
		}

		@Override
		public List<JobInstanceExecutions> extractData(ResultSet rs) throws SQLException,
				DataAccessException {
			final Map<Long, List<TaskJobExecution>> taskJobExecutions = new HashMap<>();
			final Map<Long, JobInstance> jobInstances = new TreeMap<>();

			while (rs.next()) {
				Long id = rs.getLong("JOB_INSTANCE_ID");
				JobInstance jobInstance;
				if (!jobInstances.containsKey(id)) {
					jobInstance = new JobInstance(id, rs.getString("JOB_NAME"));
					jobInstances.put(id, jobInstance);
				} else {
					jobInstance = jobInstances.get(id);
				}
				long taskId = rs.getLong("TASK_EXECUTION_ID");
				if (!rs.wasNull()) {
					String schemaTarget = rs.getString("SCHEMA_TARGET");
					List<TaskJobExecution> executions = taskJobExecutions.computeIfAbsent(id, k -> new ArrayList<>());
					long jobExecutionId = rs.getLong("JOB_EXECUTION_ID");
					JobParameters jobParameters = getJobParameters(jobExecutionId, schemaTarget);
					JobExecution jobExecution = new JobExecution(jobInstance, jobExecutionId, jobParameters, null);

					int stepCount = readStepCount ? rs.getInt("STEP_COUNT") : 0;
					TaskJobExecution execution = new TaskJobExecution(taskId, jobExecution, true, stepCount, schemaTarget);
					executions.add(execution);
				}
			}
			return jobInstances.values()
					.stream()
					.map(jobInstance -> new JobInstanceExecutions(jobInstance, taskJobExecutions.get(jobInstance.getInstanceId())))
					.collect(Collectors.toList());
		}

	}

	class JobExecutionRowMapper implements RowMapper<TaskJobExecution> {
		boolean readStepCount;

		JobExecutionRowMapper(boolean readStepCount) {
			this.readStepCount = readStepCount;
		}

		@Override
		public TaskJobExecution mapRow(ResultSet rs, int rowNum) throws SQLException {
			return createJobExecutionFromResultSet(rs, rowNum, readStepCount);
		}

	}

	protected String getQuery(String base, String tablePrefix) {
		return StringUtils.replace(base, "%PREFIX%", tablePrefix);
	}

	private int getPageOffset(Pageable pageable) {
		if (pageable.getOffset() > (long) Integer.MAX_VALUE) {
			throw new OffsetOutOfBoundsException("The pageable offset requested for this query is greater than MAX_INT.");
		}
		return (int) pageable.getOffset();
	}

	/**
	 * @return a {@link PagingQueryProvider} for all job executions
	 * @throws Exception if page provider is not created.
	 */
	private PagingQueryProvider getPagingQueryProvider() throws Exception {
		return getPagingQueryProvider(null, null, null, Collections.emptyMap());
	}

	/**
	 * @return a {@link PagingQueryProvider} for all job executions with the
	 * provided where clause
	 * @throws Exception if page provider is not created.
	 */
	private PagingQueryProvider getPagingQueryProvider(String whereClause) throws Exception {
		return getPagingQueryProvider(null, null, whereClause, Collections.emptyMap());
	}

	/**
	 * @return a {@link PagingQueryProvider} with a where clause to narrow the
	 * query
	 * @throws Exception if page provider is not created.
	 */
	private PagingQueryProvider getPagingQueryProvider(String fromClause, String whereClause) throws Exception {
		return getPagingQueryProvider(null, fromClause, whereClause, Collections.emptyMap());
	}

	private PagingQueryProvider getPagingQueryProvider(String fields, String fromClause, String whereClause) throws Exception {
		return getPagingQueryProvider(fields, fromClause, whereClause, Collections.emptyMap());
	}

	/**
	 * @return a {@link PagingQueryProvider} with a where clause to narrow the
	 * query
	 * @throws Exception if page provider is not created.
	 */
	private PagingQueryProvider getPagingQueryProvider(String fields, String fromClause, String whereClause, Map<String, Order> sortKeys) throws Exception {
		SqlPagingQueryProviderFactoryBean factory = new SqlPagingQueryProviderFactoryBean();
		factory.setDataSource(dataSource);
		fromClause = "AGGREGATE_JOB_INSTANCE I JOIN AGGREGATE_JOB_EXECUTION E ON I.JOB_INSTANCE_ID=E.JOB_INSTANCE_ID AND I.SCHEMA_TARGET=E.SCHEMA_TARGET" + (fromClause == null ? "" : " " + fromClause);
		factory.setFromClause(fromClause);
		if (fields == null) {
			fields = FIELDS;
		}
		factory.setSelectClause(fields);
		if (sortKeys.isEmpty()) {
			sortKeys = Collections.singletonMap("E.JOB_EXECUTION_ID", Order.DESCENDING);
		}
		factory.setSortKeys(sortKeys);
		factory.setWhereClause(whereClause);

		return factory.getObject();
	}
}
