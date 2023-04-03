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

package org.springframework.cloud.dataflow.server.single;

import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.dataflow.core.TaskPlatform;
import org.springframework.cloud.dataflow.server.service.SchedulerService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.web.AnnotationConfigWebContextLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author David Turanski
 **/
@ActiveProfiles("kubernetes")
@SpringBootTest(
		classes = {DataFlowServerApplication.class},
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.cloud.dataflow.task.platform.kubernetes.accounts[k8s].namespace=default",
				"spring.cloud.kubernetes.client.namespace=default",
				"kubernetes_service_host=foo",
				"spring.cloud.dataflow.features.schedules-enabled=true"
		})
@RunWith(SpringRunner.class)
public class KubernetesSchedulerTests {

	@Autowired
	List<TaskPlatform> taskPlatforms;

	@Autowired
	SchedulerService schedulerService;

	@Test
	public void schedulerServiceCreated() {
		for (TaskPlatform taskPlatform : taskPlatforms) {
			if (taskPlatform.isPrimary()) {
				assertThat(taskPlatform.getName()).isEqualTo("Kubernetes");
			}
		}
		assertThat(schedulerService).isNotNull();
	}

}
