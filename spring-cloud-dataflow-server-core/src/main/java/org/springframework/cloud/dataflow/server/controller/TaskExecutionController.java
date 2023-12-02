/*
 * Copyright 2016-2021 the original author or authors.
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

package org.springframework.cloud.dataflow.server.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.batch.core.launch.NoSuchJobExecutionException;
import org.springframework.cloud.dataflow.aggregate.task.AggregateExecutionSupport;
import org.springframework.cloud.dataflow.aggregate.task.AggregateTaskExplorer;
import org.springframework.cloud.dataflow.aggregate.task.TaskDefinitionReader;
import org.springframework.cloud.dataflow.core.LaunchResponse;
import org.springframework.cloud.dataflow.core.PlatformTaskExecutionInformation;
import org.springframework.cloud.dataflow.core.TaskDefinition;
import org.springframework.cloud.dataflow.core.TaskManifest;
import org.springframework.cloud.dataflow.core.dsl.TaskParser;
import org.springframework.cloud.dataflow.rest.job.TaskJobExecution;
import org.springframework.cloud.dataflow.rest.job.TaskJobExecutionRel;
import org.springframework.cloud.dataflow.rest.resource.CurrentTaskExecutionsResource;
import org.springframework.cloud.dataflow.rest.resource.LaunchResponseResource;
import org.springframework.cloud.dataflow.rest.resource.TaskExecutionResource;
import org.springframework.cloud.dataflow.rest.resource.TaskExecutionsInfoResource;
import org.springframework.cloud.dataflow.rest.util.ArgumentSanitizer;
import org.springframework.cloud.dataflow.rest.util.DeploymentPropertiesUtils;
import org.springframework.cloud.dataflow.rest.util.TaskSanitizer;
import org.springframework.cloud.dataflow.schema.AggregateTaskExecution;
import org.springframework.cloud.dataflow.schema.SchemaVersionTarget;
import org.springframework.cloud.dataflow.server.config.DataflowAsyncAutoConfiguration;
import org.springframework.cloud.dataflow.server.controller.support.TaskExecutionControllerDeleteAction;
import org.springframework.cloud.dataflow.server.repository.NoSuchTaskDefinitionException;
import org.springframework.cloud.dataflow.server.repository.NoSuchTaskExecutionException;
import org.springframework.cloud.dataflow.server.repository.TaskDefinitionRepository;
import org.springframework.cloud.dataflow.server.service.TaskDeleteService;
import org.springframework.cloud.dataflow.server.service.TaskExecutionInfoService;
import org.springframework.cloud.dataflow.server.service.TaskExecutionService;
import org.springframework.cloud.dataflow.server.service.TaskJobService;
import org.springframework.cloud.task.repository.TaskExecution;
import org.springframework.cloud.task.repository.TaskExplorer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.Link;
import org.springframework.hateoas.PagedModel;
import org.springframework.hateoas.server.ExposesResourceFor;
import org.springframework.hateoas.server.mvc.RepresentationModelAssemblerSupport;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

/**
 * Controller for operations on
 * {@link org.springframework.cloud.task.repository.TaskExecution}. This includes
 * obtaining task execution information from the task explorer.
 *
 * @author Glenn Renfro
 * @author Michael Minella
 * @author Ilayaperumal Gopinathan
 * @author Christian Tzolov
 * @author David Turanski
 * @author Gunnar Hillert
 * @author Corneil du Plessis
 */
@RestController
@RequestMapping("/tasks/executions")
@ExposesResourceFor(TaskExecutionResource.class)
public class TaskExecutionController {

	private final Assembler taskAssembler = new Assembler();
	private final LaunchResponseAssembler launcherResponseAssembler = new LaunchResponseAssembler();
	private final TaskExecutionService taskExecutionService;

	private final TaskExecutionInfoService taskExecutionInfoService;

	private final TaskDeleteService taskDeleteService;

	private final AggregateTaskExplorer explorer;

	private final AggregateExecutionSupport aggregateExecutionSupport;

	private final TaskDefinitionReader taskDefinitionReader;

	private final TaskJobService taskJobService;

	private final TaskDefinitionRepository taskDefinitionRepository;

	private final TaskSanitizer taskSanitizer = new TaskSanitizer();

	private final Logger logger = LoggerFactory.getLogger(TaskExecutionController.class);


	private final ArgumentSanitizer argumentSanitizer = new ArgumentSanitizer();

	private static final List<String> allowedSorts = Arrays.asList("TASK_EXECUTION_ID", "START_TIME", "END_TIME",
			"TASK_NAME", "EXIT_CODE", "EXIT_MESSAGE", "ERROR_MESSAGE", "LAST_UPDATED", "EXTERNAL_EXECUTION_ID",
			"PARENT_EXECUTION_ID");

	/**
	 * Creates a {@code TaskExecutionController} that retrieves Task Execution information
	 * from a the {@link TaskExplorer}
	 *
	 * @param explorer                  the explorer this controller will use for retrieving task execution
	 *                                  information.
	 * @param aggregateExecutionSupport provides schemaTarget for a task by name.
	 * @param taskExecutionService      used to launch tasks
	 * @param taskDefinitionRepository  the task definition repository
	 * @param taskDefinitionReader      uses task definition repository to provide Task Definition to aggregateExecutionSupport
	 * @param taskExecutionInfoService  the task execution information service
	 * @param taskDeleteService         the task deletion service
	 * @param taskJobService            the task job service
	 */
	public TaskExecutionController(AggregateTaskExplorer explorer,
								   AggregateExecutionSupport aggregateExecutionSupport,
								   TaskExecutionService taskExecutionService,
								   TaskDefinitionRepository taskDefinitionRepository,
								   TaskDefinitionReader taskDefinitionReader,
								   TaskExecutionInfoService taskExecutionInfoService,
								   TaskDeleteService taskDeleteService,
								   TaskJobService taskJobService) {
		this.taskDefinitionReader = taskDefinitionReader;
		Assert.notNull(explorer, "explorer must not be null");
		Assert.notNull(taskExecutionService, "taskExecutionService must not be null");
		Assert.notNull(taskDefinitionRepository, "taskDefinitionRepository must not be null");
		Assert.notNull(taskExecutionInfoService, "taskDefinitionRetriever must not be null");
		Assert.notNull(taskDeleteService, "taskDeleteService must not be null");
		Assert.notNull(taskJobService, "taskJobService must not be null");
		this.taskExecutionService = taskExecutionService;
		this.explorer = explorer;
		this.aggregateExecutionSupport = aggregateExecutionSupport;
		this.taskDefinitionRepository = taskDefinitionRepository;
		this.taskExecutionInfoService = taskExecutionInfoService;
		this.taskDeleteService = taskDeleteService;
		this.taskJobService = taskJobService;
	}

	/**
	 * Return a page-able list of {@link TaskExecutionResource} defined tasks.
	 *
	 * @param pageable  page-able collection of {@code TaskExecution}s.
	 * @param assembler for the {@link TaskExecution}s
	 * @return a list of task executions
	 */
	@RequestMapping(value = "", method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	public PagedModel<TaskExecutionResource> list(Pageable pageable,
												  PagedResourcesAssembler<TaskJobExecutionRel> assembler) {
		validatePageable(pageable);
		Page<AggregateTaskExecution> taskExecutions = this.explorer.findAll(pageable);
		Page<TaskJobExecutionRel> result = getPageableRelationships(taskExecutions, pageable);
		return assembler.toModel(result, this.taskAssembler);
	}

	/**
	 * Retrieve all task executions with the task name specified
	 *
	 * @param taskName  name of the task
	 * @param pageable  page-able collection of {@code TaskExecution}s.
	 * @param assembler for the {@link TaskExecution}s
	 * @return the paged list of task executions
	 */
	@RequestMapping(value = "", method = RequestMethod.GET, params = "name")
	@ResponseStatus(HttpStatus.OK)
	public PagedModel<TaskExecutionResource> retrieveTasksByName(
			@RequestParam("name") String taskName,
			Pageable pageable,
			PagedResourcesAssembler<TaskJobExecutionRel> assembler
	) {
		validatePageable(pageable);
		this.taskDefinitionRepository.findById(taskName)
				.orElseThrow(() -> new NoSuchTaskDefinitionException(taskName));
		Page<AggregateTaskExecution> taskExecutions = this.explorer.findTaskExecutionsByName(taskName, pageable);
		Page<TaskJobExecutionRel> result = getPageableRelationships(taskExecutions, pageable);
		return assembler.toModel(result, this.taskAssembler);
	}

	/**
	 * Request the launching of an existing task definition. The task definition will be created from a registered task application
	 * if `spring.cloud.dataflow.task.auto-create-task-definitions` is true.
	 * The name must be included in the path.
	 *
	 * @param taskName   the name of the task to be executed (required)
	 * @param properties the runtime properties for the task, as a comma-delimited list of
	 *                   key=value pairs
	 * @param arguments  the runtime commandline arguments
	 * @return the taskExecutionId for the executed task
	 */
	@RequestMapping(value = "", method = RequestMethod.POST, params = "name")
	@ResponseStatus(HttpStatus.CREATED)
	public long launch(
			@RequestParam("name") String taskName,
			@RequestParam(required = false) String properties,
			@RequestParam(required = false) String arguments
	) {
		SchemaVersionTarget schemaVersionTarget = aggregateExecutionSupport.findSchemaVersionTarget(taskName, taskDefinitionReader);
		if(!schemaVersionTarget.equals(SchemaVersionTarget.defaultTarget())) {
			Link link = linkTo(methodOn(TaskExecutionController.class).launchBoot3(taskName, properties, arguments)).withRel("launch");
			throw new ApiNotSupportedException(String.format("Task: %s cannot be launched for %s. Use %s", taskName, SchemaVersionTarget.defaultTarget().getName(), link.getHref()));
		}
		Map<String, String> propertiesToUse = DeploymentPropertiesUtils.parse(properties);
		List<String> argumentsToUse = DeploymentPropertiesUtils.parseArgumentList(arguments, " ");
		LaunchResponse launchResponse = this.taskExecutionService.executeTask(taskName, propertiesToUse, argumentsToUse);
		return launchResponse.getExecutionId();
	}
	@RequestMapping(value = "/launch", method = RequestMethod.POST, params = "name")
	@ResponseStatus(HttpStatus.CREATED)
	public LaunchResponseResource launchBoot3(
			@RequestParam("name") String taskName,
			@RequestParam(required = false) String properties,
			@RequestParam(required = false) String arguments
	) {
		// TODO update docs and root
		Map<String, String> propertiesToUse = DeploymentPropertiesUtils.parse(properties);
		List<String> argumentsToUse = DeploymentPropertiesUtils.parseArgumentList(arguments, " ");
		LaunchResponse launchResponse = this.taskExecutionService.executeTask(taskName, propertiesToUse, argumentsToUse);
		return this.launcherResponseAssembler.toModel(launchResponse);
	}

	/**
	 * View the details of a single task execution, specified by id.
	 *
	 * @param id the id of the requested {@link TaskExecution}
	 * @return the {@link TaskExecution}
	 */
	@RequestMapping(value = "/{id}", method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	public TaskExecutionResource view(
			@PathVariable(name = "id") Long id,
			@RequestParam(name = "schemaTarget", required = false) String schemaTarget) {
		if(!StringUtils.hasText(schemaTarget)) {
			schemaTarget = SchemaVersionTarget.defaultTarget().getName();
		}
		AggregateTaskExecution taskExecution = sanitizeTaskExecutionArguments(this.explorer.getTaskExecution(id, schemaTarget));
		if (taskExecution == null) {
			throw new NoSuchTaskExecutionException(id, schemaTarget);
		}
		TaskManifest taskManifest = this.taskExecutionService.findTaskManifestById(id, schemaTarget);
		taskManifest = this.taskSanitizer.sanitizeTaskManifest(taskManifest);
		List<Long> jobExecutionIds = new ArrayList<>(this.explorer.getJobExecutionIdsByTaskExecutionId(taskExecution.getExecutionId(), schemaTarget));
		TaskJobExecutionRel taskJobExecutionRel = new TaskJobExecutionRel(taskExecution,
				jobExecutionIds,
				taskManifest,
				getCtrTaskJobExecution(taskExecution, jobExecutionIds)
		);
		return this.taskAssembler.toModel(taskJobExecutionRel);
	}
	@RequestMapping(value = "/external/{externalExecutionId}", method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	public TaskExecutionResource viewByExternal(
			@PathVariable(name = "externalExecutionId") String externalExecutionId,
			@RequestParam(name = "platform", required = false) String platform
	) {
		AggregateTaskExecution taskExecution = sanitizeTaskExecutionArguments(this.explorer.getTaskExecutionByExternalExecutionId(externalExecutionId, platform));
		if (taskExecution == null) {
			throw new NoSuchTaskExecutionException(externalExecutionId, platform);
		}
		TaskManifest taskManifest = this.taskExecutionService.findTaskManifestById(taskExecution.getExecutionId(), taskExecution.getSchemaTarget());
		taskManifest = this.taskSanitizer.sanitizeTaskManifest(taskManifest);
		List<Long> jobExecutionIds = new ArrayList<>(this.explorer.getJobExecutionIdsByTaskExecutionId(taskExecution.getExecutionId(), taskExecution.getSchemaTarget()));
		TaskJobExecutionRel taskJobExecutionRel = new TaskJobExecutionRel(
				taskExecution,
				jobExecutionIds,
				taskManifest,
				getCtrTaskJobExecution(taskExecution, jobExecutionIds)
		);
		return this.taskAssembler.toModel(taskJobExecutionRel);
	}

	@RequestMapping(value = "/current", method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	public Collection<CurrentTaskExecutionsResource> getCurrentTaskExecutionsInfo() {
		List<PlatformTaskExecutionInformation> executionInformation = taskExecutionInfoService
				.findAllPlatformTaskExecutionInformation().getTaskExecutionInformation();
		List<CurrentTaskExecutionsResource> resources = new ArrayList<>();

		executionInformation.forEach(platformTaskExecutionInformation -> {
			CurrentTaskExecutionsResource currentTaskExecutionsResource =
					CurrentTaskExecutionsResource.fromTaskExecutionInformation(platformTaskExecutionInformation);
			resources.add(currentTaskExecutionsResource);
		});

		return resources;
	}

	/**
	 * Cleanup resources associated with one or more task executions, specified by id(s). The
	 * optional {@code actions} parameter can be used to not only clean up task execution resources,
	 * but can also trigger the deletion of task execution and job data in the persistence store.
	 *
	 * @param ids     The id of the {@link TaskExecution}s to clean up
	 * @param actions Defaults to "CLEANUP" if not specified
	 */
	@RequestMapping(value = "/{id}", method = RequestMethod.DELETE)
	@ResponseStatus(HttpStatus.OK)
	public void cleanup(
			@PathVariable("id") Set<Long> ids,
			@RequestParam(defaultValue = "CLEANUP", name = "action") TaskExecutionControllerDeleteAction[] actions,
			@RequestParam(name = "schemaTarget", required = false) String schemaTarget
	) {
		final Set<TaskExecutionControllerDeleteAction> actionsAsSet = new HashSet<>(Arrays.asList(actions));
		this.taskDeleteService.cleanupExecutions(actionsAsSet, ids, schemaTarget);
	}

	/**
	 * Cleanup resources associated with one or more task executions. The
	 * optional {@code actions} and {@code completed} parameters can be used to not only clean up task execution resources,
	 * but can also trigger the deletion of task execution and job data in the persistence store.
	 * <p>
	 * When the {@code spring.cloud.dataflow.async.enabled} property is set to {@code true} the cleanup will happen
	 * asynchronously.
	 *
	 * @param actions the actions to perform (default 'CLEANUP')
	 * @param completed whether to include only completed task executions (default false)
	 * @param taskName name of the task (default '')
	 * @param days only include tasks that have ended at least this many days ago (default null)
	 */
	@RequestMapping(method = RequestMethod.DELETE)
	@ResponseStatus(HttpStatus.OK)
	@Async(DataflowAsyncAutoConfiguration.DATAFLOW_ASYNC_EXECUTOR)
	public void cleanupAll(
			@RequestParam(defaultValue = "CLEANUP", name = "action") TaskExecutionControllerDeleteAction[] actions,
			@RequestParam(defaultValue = "false", name = "completed") boolean completed,
			@RequestParam(defaultValue = "", name = "name") String taskName,
			@RequestParam(name="days", required = false) Integer days
	) {
		this.taskDeleteService.cleanupExecutions(new HashSet<>(Arrays.asList(actions)), taskName, completed, days);
	}

	/**
	 * Stop a set of task executions.
	 *
	 * @param ids      the ids of the {@link TaskExecution}s to stop
	 * @param platform the platform name
	 */
	@RequestMapping(value = "/{id}", method = RequestMethod.POST)
	@ResponseStatus(HttpStatus.OK)
	public void stop(
			@PathVariable("id") Set<Long> ids,
			@RequestParam(defaultValue = "", name = "platform") String platform,
			@RequestParam(name = "schemaTarget", required = false) String schemaTarget
	) {
		this.taskExecutionService.stopTaskExecution(ids, schemaTarget, platform);
	}

	private Page<TaskJobExecutionRel> getPageableRelationships(Page<AggregateTaskExecution> taskExecutions, Pageable pageable) {
		List<TaskJobExecutionRel> taskJobExecutionRels = new ArrayList<>();
		for (AggregateTaskExecution taskExecution : taskExecutions.getContent()) {
			TaskManifest taskManifest = this.taskExecutionService.findTaskManifestById(taskExecution.getExecutionId(), taskExecution.getSchemaTarget());
			taskManifest = this.taskSanitizer.sanitizeTaskManifest(taskManifest);
			List<Long> jobExecutionIds = new ArrayList<>(
					this.explorer.getJobExecutionIdsByTaskExecutionId(taskExecution.getExecutionId(), taskExecution.getSchemaTarget()));
			taskJobExecutionRels
					.add(new TaskJobExecutionRel(sanitizeTaskExecutionArguments(taskExecution),
							jobExecutionIds,
							taskManifest, getCtrTaskJobExecution(taskExecution, jobExecutionIds)));
		}
		return new PageImpl<>(taskJobExecutionRels, pageable, taskExecutions.getTotalElements());
	}


	private AggregateTaskExecution sanitizeTaskExecutionArguments(AggregateTaskExecution taskExecution) {
		if (taskExecution != null) {
			List<String> args = taskExecution.getArguments().stream()
					.map(this.argumentSanitizer::sanitize).collect(Collectors.toList());
			taskExecution.setArguments(args);
		}
		return taskExecution;
	}

	private TaskJobExecution getCtrTaskJobExecution(AggregateTaskExecution taskExecution, List<Long> jobExecutionIds) {
		TaskJobExecution taskJobExecution = null;
		TaskDefinition taskDefinition = this.taskDefinitionRepository.findByTaskName(taskExecution.getTaskName());
		if (taskDefinition != null) {
			TaskParser parser = new TaskParser(taskExecution.getTaskName(), taskDefinition.getDslText(), true, false);
			if (jobExecutionIds.size() > 0 && parser.parse().isComposed()) {
				try {
					taskJobExecution = this.taskJobService.getJobExecution(jobExecutionIds.toArray(new Long[0])[0], taskExecution.getSchemaTarget());
				} catch (NoSuchJobExecutionException noSuchJobExecutionException) {
					this.logger.warn("Job Execution for Task Execution {} could not be found.",
							taskExecution.getExecutionId());
				}
			}
		}
		return taskJobExecution;
	}

	private static void validatePageable(Pageable pageable) {
		if (pageable != null) {
			Sort sort = pageable.getSort();
			if (sort != null) {
				for (Sort.Order order : sort) {
					String property = order.getProperty();
					if (property != null && !allowedSorts.contains(property.toUpperCase())) {
						throw new IllegalArgumentException("Sorting column " + order.getProperty() + " not allowed");
					}
				}
			}
		}
	}

	/**
	 * {@link org.springframework.hateoas.server.RepresentationModelAssembler} implementation that converts
	 * {@link TaskJobExecutionRel}s to {@link TaskExecutionResource}s.
	 */
	private static class Assembler extends RepresentationModelAssemblerSupport<TaskJobExecutionRel, TaskExecutionResource> {

		public Assembler() {
			super(TaskExecutionController.class, TaskExecutionResource.class);
		}

		@Override
		public TaskExecutionResource toModel(TaskJobExecutionRel taskJobExecutionRel) {

			TaskExecutionResource resource = new TaskExecutionResource(taskJobExecutionRel);
			resource.add(
					linkTo(
							methodOn(TaskLogsController.class)
									.getLog(resource.getExternalExecutionId(), resource.getPlatformName(), resource.getSchemaTarget())
					).withRel("tasks/logs")
			);

			resource.add(
					linkTo(
							methodOn(TaskExecutionController.class)
									.view(taskJobExecutionRel.getTaskExecution().getExecutionId(), taskJobExecutionRel.getTaskExecution().getSchemaTarget())
					).withSelfRel());
			return resource;
		}

		@Override
		public TaskExecutionResource instantiateModel(TaskJobExecutionRel taskJobExecutionRel) {
			return toModel(taskJobExecutionRel);
		}
	}

	/**
	 * {@link org.springframework.hateoas.server.RepresentationModelAssembler} implementation to assembler TaskExecutionsResource.
	 */
	private static class TaskExecutionsAssembler extends RepresentationModelAssemblerSupport<Integer, TaskExecutionsInfoResource> {

		public TaskExecutionsAssembler() {
			super(TaskExecutionController.class, TaskExecutionsInfoResource.class);
		}

		@Override
		public TaskExecutionsInfoResource toModel(Integer totalExecutions) {
			TaskExecutionsInfoResource taskExecutionsInfoResource = new TaskExecutionsInfoResource();
			taskExecutionsInfoResource.setTotalExecutions(totalExecutions);
			return createModelWithId(taskExecutionsInfoResource, totalExecutions);
		}

		@Override
		public TaskExecutionsInfoResource instantiateModel(Integer totalExecutions) {
			TaskExecutionsInfoResource taskExecutionsInfoResource = new TaskExecutionsInfoResource();
			taskExecutionsInfoResource.setTotalExecutions(totalExecutions);
			return taskExecutionsInfoResource;
		}
	}
	private static class LaunchResponseAssembler extends RepresentationModelAssemblerSupport<LaunchResponse, LaunchResponseResource> {
		public LaunchResponseAssembler() {
			super(TaskExecutionController.class, LaunchResponseResource.class);
		}

		@Override
		public LaunchResponseResource toModel(LaunchResponse entity) {
			LaunchResponseResource resource = new LaunchResponseResource(entity.getExecutionId(), entity.getSchemaTarget());
			resource.add(linkTo(methodOn(TaskExecutionController.class).view(entity.getExecutionId(), entity.getSchemaTarget())).withSelfRel());
			return resource;
		}
	}
}
