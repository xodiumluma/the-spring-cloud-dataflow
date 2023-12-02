/*
 * Copyright 2016 the original author or authors.
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
import java.util.List;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.NoSuchJobExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.dataflow.rest.job.TaskJobExecution;
import org.springframework.cloud.dataflow.rest.resource.StepExecutionResource;
import org.springframework.cloud.dataflow.schema.SchemaVersionTarget;
import org.springframework.cloud.dataflow.server.batch.JobService;
import org.springframework.cloud.dataflow.server.batch.NoSuchStepExecutionException;
import org.springframework.cloud.dataflow.server.job.support.StepExecutionResourceBuilder;
import org.springframework.cloud.dataflow.server.service.JobServiceContainer;
import org.springframework.cloud.dataflow.server.service.TaskJobService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PagedResourcesAssembler;
import org.springframework.hateoas.PagedModel;
import org.springframework.hateoas.server.ExposesResourceFor;
import org.springframework.hateoas.server.mvc.RepresentationModelAssemblerSupport;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;

/**
 * @author Glenn Renfro
 */
@RestController
@RequestMapping("/jobs/executions/{jobExecutionId}/steps")
@ExposesResourceFor(StepExecutionResource.class)
public class JobStepExecutionController {


	private final TaskJobService taskJobService;

	/**
	 * Creates a {@code JobStepExecutionsController} that retrieves Job Step Execution
	 * information from a the {@link JobServiceContainer}
	 *
	 * @param taskJobService TaskJobService can query all schemas.
	 */
	@Autowired
	public JobStepExecutionController(TaskJobService taskJobService) {
		Assert.notNull(taskJobService, "taskJobService required");
		this.taskJobService = taskJobService;
	}

	/**
	 * List all step executions.
	 *
	 * @param id the {@link JobExecution}.
	 * @param pageable the pagination information.
	 * @param assembler the resource assembler for step executions.
	 * @return Collection of {@link StepExecutionResource} for the given jobExecutionId.
	 * @throws NoSuchJobExecutionException if the job execution for the id specified does
	 * not exist.
	 */
	@RequestMapping(value = { "" }, method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	public PagedModel<StepExecutionResource> stepExecutions(
			@PathVariable("jobExecutionId") long id,
			@RequestParam(name = "schemaTarget", required = false) String schemaTarget,
			Pageable pageable,
			PagedResourcesAssembler<StepExecution> assembler
	) throws NoSuchJobExecutionException {
		if(!StringUtils.hasText(schemaTarget)) {
			schemaTarget = SchemaVersionTarget.defaultTarget().getName();
		}
		TaskJobExecution taskJobExecution = taskJobService.getJobExecution(id, schemaTarget);
		List<StepExecution> result = new ArrayList<>(taskJobExecution.getJobExecution().getStepExecutions());
		Page<StepExecution> page = new PageImpl<>(result, pageable, result.size());
		final Assembler stepAssembler = new Assembler(schemaTarget);
		return assembler.toModel(page, stepAssembler);
	}

	/**
	 * Retrieve a specific {@link StepExecutionResource}.
	 *
	 * @param id the {@link JobExecution} id.
	 * @param stepId the {@link StepExecution} id.
	 * @return Collection of {@link StepExecutionResource} for the given jobExecutionId.
	 * @throws NoSuchStepExecutionException if the stepId specified does not exist.
	 * @throws NoSuchJobExecutionException if the job execution for the id specified does
	 * not exist.
	 */
	@RequestMapping(value = { "/{stepExecutionId}" }, method = RequestMethod.GET)
	@ResponseStatus(HttpStatus.OK)
	public StepExecutionResource getStepExecution(
			@PathVariable("jobExecutionId") Long id,
			@PathVariable("stepExecutionId") Long stepId,
			@RequestParam(name = "schemaTarget", required = false) String schemaTarget)
			throws NoSuchStepExecutionException, NoSuchJobExecutionException {
		if(!StringUtils.hasText(schemaTarget)) {
			schemaTarget = SchemaVersionTarget.defaultTarget().getName();
		}
		TaskJobExecution taskJobExecution = taskJobService.getJobExecution(id, schemaTarget);
		final Assembler stepAssembler = new Assembler(schemaTarget);
		StepExecution stepExecution = taskJobExecution.getJobExecution().getStepExecutions()
				.stream()
				.filter(s -> s.getId().equals(stepId))
				.findFirst()
				.orElseThrow(() -> new NoSuchStepExecutionException("Step " + stepId + " in Job " + id + " not found"));
		return stepAssembler.toModel(stepExecution);
	}

	/**
	 * {@link org.springframework.hateoas.server.RepresentationModelAssembler} implementation that converts
	 * {@link StepExecution}s to {@link StepExecutionResource}s.
	 */
	private static class Assembler extends RepresentationModelAssemblerSupport<StepExecution, StepExecutionResource> {
		private final String schemaTarget;
		public Assembler(String schemaTarget) {
			super(JobStepExecutionController.class, StepExecutionResource.class);
			this.schemaTarget = schemaTarget;
		}

		@Override
		public StepExecutionResource toModel(StepExecution stepExecution) {
			return StepExecutionResourceBuilder.toModel(stepExecution, schemaTarget);
		}

		@Override
		public StepExecutionResource instantiateModel(StepExecution stepExecution) {
			return StepExecutionResourceBuilder.toModel(stepExecution, schemaTarget);
		}
	}
}
