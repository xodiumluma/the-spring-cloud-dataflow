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

package org.springframework.cloud.dataflow.server.service.impl;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.dataflow.aggregate.task.AggregateTaskExplorer;
import org.springframework.cloud.dataflow.audit.service.AuditRecordService;
import org.springframework.cloud.dataflow.core.AuditActionType;
import org.springframework.cloud.dataflow.core.AuditOperationType;
import org.springframework.cloud.dataflow.core.Launcher;
import org.springframework.cloud.dataflow.core.TaskDefinition;
import org.springframework.cloud.dataflow.core.TaskDeployment;
import org.springframework.cloud.dataflow.core.database.support.DatabaseType;
import org.springframework.cloud.dataflow.core.dsl.TaskNode;
import org.springframework.cloud.dataflow.core.dsl.TaskParser;
import org.springframework.cloud.dataflow.rest.util.ArgumentSanitizer;
import org.springframework.cloud.dataflow.schema.AggregateTaskExecution;
import org.springframework.cloud.dataflow.schema.SchemaVersionTarget;
import org.springframework.cloud.dataflow.schema.service.SchemaService;
import org.springframework.cloud.dataflow.server.controller.support.TaskExecutionControllerDeleteAction;
import org.springframework.cloud.dataflow.server.job.LauncherRepository;
import org.springframework.cloud.dataflow.server.repository.DataflowJobExecutionDao;
import org.springframework.cloud.dataflow.server.repository.DataflowJobExecutionDaoContainer;
import org.springframework.cloud.dataflow.server.repository.DataflowTaskExecutionDao;
import org.springframework.cloud.dataflow.server.repository.DataflowTaskExecutionDaoContainer;
import org.springframework.cloud.dataflow.server.repository.DataflowTaskExecutionMetadataDao;
import org.springframework.cloud.dataflow.server.repository.DataflowTaskExecutionMetadataDaoContainer;
import org.springframework.cloud.dataflow.server.repository.NoSuchTaskDefinitionException;
import org.springframework.cloud.dataflow.server.repository.NoSuchTaskExecutionException;
import org.springframework.cloud.dataflow.server.repository.TaskDefinitionRepository;
import org.springframework.cloud.dataflow.server.repository.TaskDeploymentRepository;
import org.springframework.cloud.dataflow.server.service.SchedulerService;
import org.springframework.cloud.dataflow.server.service.TaskDeleteService;
import org.springframework.cloud.deployer.spi.task.TaskLauncher;
import org.springframework.jdbc.support.MetaDataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Default implementation of the {@link TaskDeleteService} interface. Provide service
 * methods for Task deletion.
 *
 * @author Michael Minella
 * @author Marius Bogoevici
 * @author Glenn Renfro
 * @author Mark Fisher
 * @author Janne Valkealahti
 * @author Gunnar Hillert
 * @author Thomas Risberg
 * @author Ilayaperumal Gopinathan
 * @author Michael Wirth
 * @author David Turanski
 * @author Daniel Serleg
 * @author Corneil du Plessis
 * @author Joe O'Brien
 */
public class DefaultTaskDeleteService implements TaskDeleteService {

	private static final Logger logger = LoggerFactory.getLogger(DefaultTaskDeleteService.class);

	private static final int SQL_SERVER_CHUNK_SIZE = 2098;

	private static final int ORACLE_SERVER_CHUNK_SIZE = 998;

	/**
	 * Used to read TaskExecutions.
	 */
	private final AggregateTaskExplorer taskExplorer;

	private final LauncherRepository launcherRepository;

	private final TaskDefinitionRepository taskDefinitionRepository;

	private final TaskDeploymentRepository taskDeploymentRepository;

	protected final AuditRecordService auditRecordService;

	protected final DataflowTaskExecutionDaoContainer dataflowTaskExecutionDaoContainer;

	protected final DataflowJobExecutionDaoContainer dataflowJobExecutionDaoContainer;

	protected final DataflowTaskExecutionMetadataDaoContainer dataflowTaskExecutionMetadataDaoContainer;

	private final SchedulerService schedulerService;

	private final ArgumentSanitizer argumentSanitizer = new ArgumentSanitizer();

	private final SchemaService schemaService;

	private final int taskDeleteChunkSize;

	private final DataSource dataSource;

	public DefaultTaskDeleteService(
			AggregateTaskExplorer taskExplorer,
			LauncherRepository launcherRepository,
			TaskDefinitionRepository taskDefinitionRepository,
			TaskDeploymentRepository taskDeploymentRepository,
			AuditRecordService auditRecordService,
			DataflowTaskExecutionDaoContainer dataflowTaskExecutionDaoContainer,
			DataflowJobExecutionDaoContainer dataflowJobExecutionDaoContainer,
			DataflowTaskExecutionMetadataDaoContainer dataflowTaskExecutionMetadataDaoContainer,
			SchedulerService schedulerService,
			SchemaService schemaService,
			TaskConfigurationProperties taskConfigurationProperties,
			DataSource dataSource
	) {
		Assert.notNull(taskExplorer, "TaskExplorer must not be null");
		Assert.notNull(launcherRepository, "LauncherRepository must not be null");
		Assert.notNull(taskDefinitionRepository, "TaskDefinitionRepository must not be null");
		Assert.notNull(taskDeploymentRepository, "TaskDeploymentRepository must not be null");
		Assert.notNull(auditRecordService, "AuditRecordService must not be null");
		Assert.notNull(dataflowTaskExecutionDaoContainer, "DataflowTaskExecutionDaoContainer must not be null");
		Assert.notNull(dataflowJobExecutionDaoContainer, "DataflowJobExecutionDaoContainer must not be null");
		Assert.notNull(dataflowTaskExecutionMetadataDaoContainer, "DataflowTaskExecutionMetadataDaoContainer must not be null");
		Assert.notNull(taskConfigurationProperties, "TaskConfigurationProperties must not be null");
		Assert.notNull(dataSource, "DataSource must not be null");

		this.taskExplorer = taskExplorer;
		this.launcherRepository = launcherRepository;
		this.taskDefinitionRepository = taskDefinitionRepository;
		this.taskDeploymentRepository = taskDeploymentRepository;
		this.auditRecordService = auditRecordService;
		this.dataflowTaskExecutionDaoContainer = dataflowTaskExecutionDaoContainer;
		this.dataflowJobExecutionDaoContainer = dataflowJobExecutionDaoContainer;
		this.dataflowTaskExecutionMetadataDaoContainer = dataflowTaskExecutionMetadataDaoContainer;
		this.schedulerService = schedulerService;
		this.schemaService = schemaService;
		this.taskDeleteChunkSize = taskConfigurationProperties.getExecutionDeleteChunkSize();
		this.dataSource = dataSource;
	}

	@Override
	@Transactional
	public void cleanupExecution(long id, String schemaTarget) {
		performCleanupExecution(id, schemaTarget);
	}

	private void performCleanupExecution(long id, String schemaTarget) {
		AggregateTaskExecution taskExecution = taskExplorer.getTaskExecution(id, schemaTarget);
		Assert.notNull(taskExecution, "There was no task execution with id " + id);
		String launchId = taskExecution.getExternalExecutionId();
		if (!StringUtils.hasText(launchId)) {
			logger.warn("Did not find External execution ID for taskName = [{}], taskId = [{}].  Nothing to clean up.", taskExecution.getTaskName(), id);
			return;
		}
		TaskDeployment taskDeployment = this.taskDeploymentRepository.findByTaskDeploymentId(launchId);
		if (taskDeployment == null) {
			logger.warn("Did not find TaskDeployment for taskName = [{}], taskId = [{}].  Nothing to clean up.", taskExecution.getTaskName(), id);
			return;
		}
		Launcher launcher = launcherRepository.findByName(taskDeployment.getPlatformName());
		if (launcher != null) {
			TaskLauncher taskLauncher = launcher.getTaskLauncher();
			taskLauncher.cleanup(launchId);
		} else {
			logger.info("Could clean up execution for task id " + id + ". Did not find a task platform named " + taskDeployment.getPlatformName());
		}
	}

	@Override
	@Transactional
	public void cleanupExecutions(Set<TaskExecutionControllerDeleteAction> actionsAsSet, String taskName, boolean completed) {
		cleanupExecutions(actionsAsSet, taskName, completed, null);
	}

	@Override
	@Transactional
	public void cleanupExecutions(Set<TaskExecutionControllerDeleteAction> actionsAsSet, String taskName, boolean completed, Integer days) {
		List<AggregateTaskExecution> tasks;
		if (days != null) {
			tasks = this.taskExplorer.findTaskExecutionsBeforeEndTime(taskName, TaskServicesDateUtils.numDaysAgoFromLocalMidnightToday(days));
		} else {
			tasks = this.taskExplorer.findTaskExecutions(taskName, completed);
		}
		final Set<AggregateTaskExecution> parentExecutions = new HashSet<>();
		final Set<AggregateTaskExecution> childExecutions = new HashSet<>();
		boolean removeData = actionsAsSet.contains(TaskExecutionControllerDeleteAction.REMOVE_DATA);
		boolean cleanUp = actionsAsSet.contains(TaskExecutionControllerDeleteAction.CLEANUP);
		for (AggregateTaskExecution taskExecution : tasks) {
			if (taskExecution.getParentExecutionId() == null) {
				parentExecutions.add(taskExecution);
			} else {
				childExecutions.add(taskExecution);
			}
		}
		if (cleanUp) {
			for (AggregateTaskExecution taskExecution : tasks) {
				this.performCleanupExecution(taskExecution.getExecutionId(), taskExecution.getSchemaTarget());
			}
		}

		if (removeData) {
			if (!childExecutions.isEmpty()) {
				deleteTaskExecutions(childExecutions);
			}
			if (!parentExecutions.isEmpty()) {
				Map<String, List<AggregateTaskExecution>> parents = parentExecutions.stream()
						.collect(Collectors.groupingBy(AggregateTaskExecution::getSchemaTarget));
				for (String schemaTarget : parents.keySet()) {
					SortedSet<Long> parentIds = parents.get(schemaTarget)
							.stream()
							.map(AggregateTaskExecution::getExecutionId)
							.collect(Collectors.toCollection(TreeSet::new));
					Map<String, List<AggregateTaskExecution>> children = this.taskExplorer.findChildTaskExecutions(parentIds, schemaTarget)
							.stream()
							.collect(Collectors.groupingBy(AggregateTaskExecution::getSchemaTarget));
					for (String group : children.keySet()) {
						SortedSet<Long> childIds = children.get(group)
								.stream()
								.map(AggregateTaskExecution::getExecutionId)
								.collect(Collectors.toCollection(TreeSet::new));
						this.performDeleteTaskExecutions(childIds, group);

					}
					this.performDeleteTaskExecutions(parentIds, schemaTarget);
				}
			}
		}
	}

	private void deleteTaskExecutions(Collection<AggregateTaskExecution> taskExecutions) {
		Map<String, List<AggregateTaskExecution>> executions = taskExecutions.stream()
				.collect(Collectors.groupingBy(AggregateTaskExecution::getSchemaTarget));
		for (String schemaTarget : executions.keySet()) {
			SortedSet<Long> executionIds = executions.get(schemaTarget)
					.stream()
					.map(AggregateTaskExecution::getExecutionId)
					.collect(Collectors.toCollection(TreeSet::new));
			this.performDeleteTaskExecutions(executionIds, schemaTarget);
		}
	}

	@Override
	@Transactional
	public void cleanupExecutions(Set<TaskExecutionControllerDeleteAction> actionsAsSet, Set<Long> ids, String schemaTarget) {
		performCleanupExecutions(actionsAsSet, ids, schemaTarget);
	}

	private void performCleanupExecutions(Set<TaskExecutionControllerDeleteAction> actionsAsSet, Set<Long> ids, String schemaTarget) {
		final SortedSet<Long> nonExistingTaskExecutions = new TreeSet<>();
		final SortedSet<Long> parentExecutions = new TreeSet<>();
		final SortedSet<Long> childExecutions = new TreeSet<>();
		boolean removeData = actionsAsSet.contains(TaskExecutionControllerDeleteAction.REMOVE_DATA);
		boolean cleanUp = actionsAsSet.contains(TaskExecutionControllerDeleteAction.CLEANUP);
		for (Long id : ids) {
			final AggregateTaskExecution taskExecution = this.taskExplorer.getTaskExecution(id, schemaTarget);
			if (taskExecution == null) {
				nonExistingTaskExecutions.add(id);
			} else if (taskExecution.getParentExecutionId() == null) {
				parentExecutions.add(taskExecution.getExecutionId());
			} else {
				childExecutions.add(taskExecution.getExecutionId());
			}
		}
		if (!nonExistingTaskExecutions.isEmpty()) {
			if (nonExistingTaskExecutions.size() == 1) {
				throw new NoSuchTaskExecutionException(nonExistingTaskExecutions.first(), schemaTarget);
			} else {
				throw new NoSuchTaskExecutionException(nonExistingTaskExecutions, schemaTarget);
			}
		}

		if (cleanUp) {
			for (Long id : ids) {
				this.performCleanupExecution(id, schemaTarget);
			}
		}

		if (removeData) {
			if (!childExecutions.isEmpty()) {
				this.performDeleteTaskExecutions(childExecutions, schemaTarget);
			}
			if (!parentExecutions.isEmpty()) {
				List<AggregateTaskExecution> children = this.taskExplorer.findChildTaskExecutions(parentExecutions, schemaTarget);
				if (!children.isEmpty()) {
					this.deleteTaskExecutions(children);
				}
				this.performDeleteTaskExecutions(parentExecutions, schemaTarget);
			}
		}
	}

	@Override
	@Transactional
	public void deleteTaskExecutions(Set<Long> taskExecutionIds, String schemaTarget) {
		performDeleteTaskExecutions(taskExecutionIds, schemaTarget);
	}

	@Override
	public void deleteTaskExecutions(String taskName, boolean onlyCompleted) {
		Map<String, List<AggregateTaskExecution>> tasks = this.taskExplorer.findTaskExecutions(taskName, onlyCompleted)
				.stream().collect(Collectors.groupingBy(AggregateTaskExecution::getSchemaTarget));
		for (String schemaTarget : tasks.keySet()) {
			Set<Long> executionIds = tasks.get(schemaTarget)
					.stream()
					.map(AggregateTaskExecution::getExecutionId)
					.collect(Collectors.toSet());
			performDeleteTaskExecutions(executionIds, schemaTarget);
		}
	}

	private void performDeleteTaskExecutions(Set<Long> taskExecutionIds, String schemaTarget) {
		logger.info("performDeleteTaskExecutions:{}:{}", schemaTarget, taskExecutionIds);
		Assert.notEmpty(taskExecutionIds, "You must provide at least 1 task execution id.");

		final DataflowTaskExecutionDao dataflowTaskExecutionDao = dataflowTaskExecutionDaoContainer.get(schemaTarget);
		final DataflowTaskExecutionMetadataDao dataflowTaskExecutionMetadataDao = dataflowTaskExecutionMetadataDaoContainer.get(schemaTarget);

		final Set<Long> taskExecutionIdsWithChildren = new HashSet<>(taskExecutionIds);
		final Set<Long> childTaskExecutionIds = dataflowTaskExecutionDao.findChildTaskExecutionIds(taskExecutionIds);
		logger.info("Found {} child task execution ids: {}.",
				childTaskExecutionIds.size(),
				StringUtils.collectionToCommaDelimitedString(childTaskExecutionIds));
		taskExecutionIdsWithChildren.addAll(childTaskExecutionIds);

		final Map<String, Object> auditData = new LinkedHashMap<>();
		auditData.put("Deleted Task Executions", taskExecutionIdsWithChildren.size());

		logger.info("Deleting {} task executions.", taskExecutionIdsWithChildren.size());

		// Retrieve Related Job Executions

		final Set<Long> jobExecutionIds = new HashSet<>();

		for (Long taskExecutionId : taskExecutionIdsWithChildren) {
			jobExecutionIds.addAll(taskExplorer.getJobExecutionIdsByTaskExecutionId(taskExecutionId, schemaTarget));
		}

		logger.info("There are {} associated job executions.", jobExecutionIds.size());

		// Remove Batch Related Data if needed
		auditData.put("Deleted # of Job Executions", jobExecutionIds.size());
		auditData.put("Deleted Job Execution IDs", StringUtils.collectionToDelimitedString(jobExecutionIds, ", "));

		int chunkSize = getTaskExecutionDeleteChunkSize(this.dataSource);

		if (!jobExecutionIds.isEmpty()) {
			deleteRelatedJobAndStepExecutions(jobExecutionIds, auditData, chunkSize, schemaTarget);
		}

		// Delete Task Related Data

		auditData.put("Deleted # of Task Executions", taskExecutionIdsWithChildren.size());
		auditData.put("Deleted Task Execution IDs", StringUtils.collectionToDelimitedString(taskExecutionIdsWithChildren, ", "));

		final AtomicInteger numberOfDeletedTaskExecutionParamRows = new AtomicInteger(0);
		final AtomicInteger numberOfDeletedTaskTaskBatchRelationshipRows = new AtomicInteger(0);
		final AtomicInteger numberOfDeletedTaskManifestRows = new AtomicInteger(0);
		final AtomicInteger numberOfDeletedTaskExecutionRows = new AtomicInteger(0);

		if (chunkSize <= 0) {
			numberOfDeletedTaskExecutionParamRows.addAndGet(dataflowTaskExecutionDao.deleteTaskExecutionParamsByTaskExecutionIds(taskExecutionIdsWithChildren));
			numberOfDeletedTaskTaskBatchRelationshipRows.addAndGet(dataflowTaskExecutionDao.deleteTaskTaskBatchRelationshipsByTaskExecutionIds(
					taskExecutionIdsWithChildren));
			numberOfDeletedTaskManifestRows.addAndGet(dataflowTaskExecutionMetadataDao.deleteManifestsByTaskExecutionIds(taskExecutionIdsWithChildren));
			numberOfDeletedTaskExecutionRows.addAndGet(dataflowTaskExecutionDao.deleteTaskExecutionsByTaskExecutionIds(taskExecutionIdsWithChildren));
		} else {
			split(taskExecutionIdsWithChildren, chunkSize).forEach(taskExecutionIdSubsetList -> {
				Set<Long> taskExecutionIdSubset = new HashSet<>(taskExecutionIdSubsetList);
				numberOfDeletedTaskExecutionParamRows.addAndGet(dataflowTaskExecutionDao.deleteTaskExecutionParamsByTaskExecutionIds(taskExecutionIdSubset));
				numberOfDeletedTaskTaskBatchRelationshipRows.addAndGet(dataflowTaskExecutionDao.deleteTaskTaskBatchRelationshipsByTaskExecutionIds(
						taskExecutionIdSubset));
				numberOfDeletedTaskManifestRows.addAndGet(dataflowTaskExecutionMetadataDao.deleteManifestsByTaskExecutionIds(taskExecutionIdSubset));
				numberOfDeletedTaskExecutionRows.addAndGet(dataflowTaskExecutionDao.deleteTaskExecutionsByTaskExecutionIds(taskExecutionIdSubset));
			});
		}

		logger.info("Deleted the following Task Execution related data for {} Task Executions:\n" +
						"Task Execution Param Rows:    {}\n" +
						"Task Batch Relationship Rows: {}\n" +
						"Task Manifest Rows:           {}\n" +
						"Task Execution Rows:          {}.",
				taskExecutionIdsWithChildren.size(),
				numberOfDeletedTaskExecutionParamRows,
				numberOfDeletedTaskTaskBatchRelationshipRows,
				numberOfDeletedTaskManifestRows,
				numberOfDeletedTaskExecutionRows
		);

		// Populate Audit Record

		auditRecordService.populateAndSaveAuditRecordUsingMapData(AuditOperationType.TASK,
				AuditActionType.DELETE,
				taskExecutionIdsWithChildren.size() + " Task Execution Delete(s)",
				auditData,
				null);
	}

	private void deleteRelatedJobAndStepExecutions(Set<Long> jobExecutionIds, Map<String, Object> auditData, int chunkSize, String schemaTarget) {

		final Set<Long> stepExecutionIds = findStepExecutionIds(jobExecutionIds, chunkSize, schemaTarget);

		final AtomicInteger numberOfDeletedBatchStepExecutionContextRows = new AtomicInteger(0);
		if (!stepExecutionIds.isEmpty()) {
			deleteBatchStepExecutionContextByStepExecutionIds(stepExecutionIds, chunkSize, numberOfDeletedBatchStepExecutionContextRows, schemaTarget);
		}
		deleteStepAndJobExecutionsByJobExecutionId(jobExecutionIds, chunkSize, auditData, numberOfDeletedBatchStepExecutionContextRows, schemaTarget);

	}

	private Set<Long> findStepExecutionIds(Set<Long> jobExecutionIds, int chunkSize, String schemaTarget) {
		final Set<Long> stepExecutionIds = ConcurrentHashMap.newKeySet();
		DataflowJobExecutionDao dataflowJobExecutionDao = dataflowJobExecutionDaoContainer.get(schemaTarget);
		if (chunkSize <= 0) {
			stepExecutionIds.addAll(dataflowJobExecutionDao.findStepExecutionIds(jobExecutionIds));
		} else {
			split(jobExecutionIds, chunkSize).forEach(jobExecutionIdSubsetList -> {
				Set<Long> jobExecutionIdSubset = new HashSet<>(jobExecutionIdSubsetList);
				stepExecutionIds.addAll(dataflowJobExecutionDao.findStepExecutionIds(jobExecutionIdSubset));
			});
		}

		return stepExecutionIds;
	}

	private void deleteBatchStepExecutionContextByStepExecutionIds(
			Set<Long> stepExecutionIds,
			int chunkSize,
			AtomicInteger numberOfDeletedBatchStepExecutionContextRows,
			String schemaTarget
	) {
		final DataflowJobExecutionDao dataflowJobExecutionDao = dataflowJobExecutionDaoContainer.get(schemaTarget);
		if (chunkSize <= 0) {
			numberOfDeletedBatchStepExecutionContextRows.addAndGet(dataflowJobExecutionDao.deleteBatchStepExecutionContextByStepExecutionIds(stepExecutionIds));

		} else {
			split(stepExecutionIds, chunkSize).forEach(stepExecutionIdSubsetList -> {
				Set<Long> stepExecutionIdSubset = new HashSet<>(stepExecutionIdSubsetList);
				numberOfDeletedBatchStepExecutionContextRows.addAndGet(dataflowJobExecutionDao.deleteBatchStepExecutionContextByStepExecutionIds(
						stepExecutionIdSubset));
			});
		}
	}

	private void deleteStepAndJobExecutionsByJobExecutionId(
			Set<Long> jobExecutionIds,
			int chunkSize,
			Map<String, Object> auditData,
			AtomicInteger numberOfDeletedBatchStepExecutionContextRows,
			String schemaTarget
	) {
		DataflowJobExecutionDao dataflowJobExecutionDao = dataflowJobExecutionDaoContainer.get(schemaTarget);
		final AtomicInteger numberOfDeletedBatchStepExecutionRows = new AtomicInteger(0);
		final AtomicInteger numberOfDeletedBatchJobExecutionContextRows = new AtomicInteger(0);
		final AtomicInteger numberOfDeletedBatchJobExecutionParamRows = new AtomicInteger(0);
		final AtomicInteger numberOfDeletedBatchJobExecutionRows = new AtomicInteger(0);

		if (chunkSize <= 0) {
			numberOfDeletedBatchStepExecutionRows.addAndGet(dataflowJobExecutionDao.deleteBatchStepExecutionsByJobExecutionIds(jobExecutionIds));
			numberOfDeletedBatchJobExecutionContextRows.addAndGet(dataflowJobExecutionDao.deleteBatchJobExecutionContextByJobExecutionIds(jobExecutionIds));
			numberOfDeletedBatchJobExecutionParamRows.addAndGet(dataflowJobExecutionDao.deleteBatchJobExecutionParamsByJobExecutionIds(jobExecutionIds));
			numberOfDeletedBatchJobExecutionRows.addAndGet(dataflowJobExecutionDao.deleteBatchJobExecutionByJobExecutionIds(jobExecutionIds));
		} else {
			split(jobExecutionIds, chunkSize).forEach(jobExecutionIdSubsetList -> {
				Set<Long> jobExecutionIdSubset = new HashSet<>(jobExecutionIdSubsetList);
				numberOfDeletedBatchStepExecutionRows.addAndGet(dataflowJobExecutionDao.deleteBatchStepExecutionsByJobExecutionIds(jobExecutionIdSubset));
				numberOfDeletedBatchJobExecutionContextRows.addAndGet(dataflowJobExecutionDao.deleteBatchJobExecutionContextByJobExecutionIds(
						jobExecutionIdSubset));
				numberOfDeletedBatchJobExecutionParamRows.addAndGet(dataflowJobExecutionDao.deleteBatchJobExecutionParamsByJobExecutionIds(jobExecutionIdSubset));
				numberOfDeletedBatchJobExecutionRows.addAndGet(dataflowJobExecutionDao.deleteBatchJobExecutionByJobExecutionIds(jobExecutionIdSubset));
			});
		}

		final int numberOfDeletedUnusedBatchJobInstanceRows = dataflowJobExecutionDao.deleteUnusedBatchJobInstances();

		logger.info("Deleted the following Batch Job Execution related data for {} Job Executions.\n" + "Batch Step Execution Context Rows: {}\n" + "Batch Step Executions Rows:        {}\n" + "Batch Job Execution Context Rows:  {}\n" + "Batch Job Execution Param Rows:    {}\n" + "Batch Job Execution Rows:          {}\n" + "Batch Job Instance Rows:           {}.",
				jobExecutionIds.size(),
				numberOfDeletedBatchStepExecutionContextRows,
				numberOfDeletedBatchStepExecutionRows,
				numberOfDeletedBatchJobExecutionContextRows,
				numberOfDeletedBatchJobExecutionParamRows,
				numberOfDeletedBatchJobExecutionRows,
				numberOfDeletedUnusedBatchJobInstanceRows);

		auditData.put("Batch Step Execution Context", numberOfDeletedBatchStepExecutionContextRows);
		auditData.put("Batch Step Executions", numberOfDeletedBatchStepExecutionRows);
		auditData.put("Batch Job Execution Context Rows", numberOfDeletedBatchJobExecutionContextRows);
		auditData.put("Batch Job Execution Params", numberOfDeletedBatchJobExecutionParamRows);
		auditData.put("Batch Job Executions", numberOfDeletedBatchJobExecutionRows);
		auditData.put("Batch Job Instance Rows", numberOfDeletedUnusedBatchJobInstanceRows);
	}

	/**
	 * Determines the maximum chunk size for a given database type.  If {@code taskDeleteChunkSize} is
	 * greater than zero this overrides the chunk size for the specific database type.
	 * If the database type has no fixed number of maximum elements allowed in the {@code IN} clause
	 * then zero is returned.
	 *
	 * @param dataSource the datasource used by data flow.
	 * @return the chunk size to be used for deleting task executions.
	 */
	private int getTaskExecutionDeleteChunkSize(DataSource dataSource) {
		int result = this.taskDeleteChunkSize;
		if (this.taskDeleteChunkSize < 1) {
			try {
				DatabaseType databaseType = DatabaseType.fromMetaData(dataSource);
				String name = databaseType.name();
				if (name.equals("SQLSERVER")) {
					result = SQL_SERVER_CHUNK_SIZE;
				}
				if (name.startsWith("ORACLE")) {
					result = ORACLE_SERVER_CHUNK_SIZE;
				}
			} catch (MetaDataAccessException mdae) {
				logger.warn("Unable to retrieve metadata for database when deleting task executions", mdae);
			}
		}
		return result;
	}

	static <T> Collection<List<T>> split(Collection<T> input, int max) {
		final AtomicInteger count = new AtomicInteger(0);
		return input.stream().collect(Collectors.groupingBy(s -> count.getAndIncrement() / max)).values();
	}

	@Override
	public void deleteTaskDefinition(String name) {
		TaskDefinition taskDefinition = this.taskDefinitionRepository.findById(name).orElseThrow(() -> new NoSuchTaskDefinitionException(name));

		deleteTaskDefinition(taskDefinition);

		auditRecordService.populateAndSaveAuditRecord(AuditOperationType.TASK,
				AuditActionType.DELETE,
				taskDefinition.getTaskName(),
				this.argumentSanitizer.sanitizeTaskDsl(taskDefinition),
				null);
	}

	@Override
	public void deleteTaskDefinition(String name, boolean cleanup) {
		if (cleanup) {
			for (SchemaVersionTarget target : schemaService.getTargets().getSchemas()) {
				DataflowTaskExecutionDao dataflowTaskExecutionDao = dataflowTaskExecutionDaoContainer.get(target.getName());
				Set<Long> taskExecutionIds = dataflowTaskExecutionDao.getTaskExecutionIdsByTaskName(name);
				final Set<TaskExecutionControllerDeleteAction> actionsAsSet = new HashSet<>();
				actionsAsSet.add(TaskExecutionControllerDeleteAction.CLEANUP);
				actionsAsSet.add(TaskExecutionControllerDeleteAction.REMOVE_DATA);
				if (!taskExecutionIds.isEmpty()) {
					performCleanupExecutions(actionsAsSet, taskExecutionIds, target.getName());
				}
			}
		}
		this.deleteTaskDefinition(name);
	}

	@Override
	public void deleteAll() {
		Iterable<TaskDefinition> allTaskDefinition = this.taskDefinitionRepository.findAll();

		for (TaskDefinition taskDefinition : allTaskDefinition) {
			deleteTaskDefinition(taskDefinition);

			auditRecordService.populateAndSaveAuditRecord(AuditOperationType.TASK,
					AuditActionType.DELETE,
					taskDefinition.getTaskName(),
					this.argumentSanitizer.sanitizeTaskDsl(taskDefinition),
					null);
		}
	}

	private void deleteTaskDefinition(TaskDefinition taskDefinition) {
		TaskParser taskParser = new TaskParser(taskDefinition.getName(), taskDefinition.getDslText(), true, true);
		if (this.schedulerService != null) {
			schedulerService.unscheduleForTaskDefinition(taskDefinition.getTaskName());
		}
		TaskNode taskNode = taskParser.parse();
		// if composed-task-runner definition then destroy all child tasks associated with it.
		if (taskNode.isComposed()) {
			String childTaskPrefix = TaskNode.getTaskPrefix(taskDefinition.getTaskName());
			// destroy composed child tasks
			taskNode.getTaskApps().forEach(task -> {
				String childName = task.getName();
				if (task.getLabel() != null) {
					childName = task.getLabel();
				}
				try {
					destroyChildTask(childTaskPrefix + childName);
				} catch (ObjectOptimisticLockingFailureException e) {
					logger.warn("Attempted delete on a child task that is currently being deleted");
				}
			});
		}
		// destroy normal task or composed parent task
		try {
			destroyPrimaryTask(taskDefinition.getTaskName());
		} catch (ObjectOptimisticLockingFailureException e) {
			logger.warn("Attempted delete on task {} that is currently being deleted", taskDefinition.getTaskName());
		}
	}

	private void destroyPrimaryTask(String name) {
		TaskDefinition taskDefinition = taskDefinitionRepository.findById(name).orElseThrow(() -> new NoSuchTaskDefinitionException(name));
		destroyTask(taskDefinition);
	}

	private void destroyChildTask(String name) {
		Optional<TaskDefinition> taskDefinition = taskDefinitionRepository.findById(name);
		taskDefinition.ifPresent(this::destroyTask);
	}

	private void destroyTask(TaskDefinition taskDefinition) {
		taskDefinitionRepository.deleteById(taskDefinition.getName());
		TaskDeployment taskDeployment = this.taskDeploymentRepository.findTopByTaskDefinitionNameOrderByCreatedOnAsc(taskDefinition.getTaskName());
		if (taskDeployment != null) {
			Launcher launcher = launcherRepository.findByName(taskDeployment.getPlatformName());
			if (launcher != null) {
				TaskLauncher taskLauncher = launcher.getTaskLauncher();
				taskLauncher.destroy(taskDefinition.getName());
			}
		} else {
			if (!findAndDeleteTaskResourcesAcrossPlatforms(taskDefinition)) {
				logger.info("TaskLauncher.destroy not invoked for task " + taskDefinition.getTaskName() + ". Did not find a previously launched task to destroy.");
			}
		}
	}

	private boolean findAndDeleteTaskResourcesAcrossPlatforms(TaskDefinition taskDefinition) {
		boolean result = false;
		for (Launcher launcher : launcherRepository.findAll()) {
			try {
				launcher.getTaskLauncher().destroy(taskDefinition.getName());
				logger.info("Deleted task app resources for {} in platform {}", taskDefinition.getName(), launcher.getName());
				result = true;
			} catch (Exception ex) {
				logger.info("Attempted delete of app resources for {} but none found on platform {}.", taskDefinition.getName(), launcher.getName());
			}
		}
		return result;
	}
}
