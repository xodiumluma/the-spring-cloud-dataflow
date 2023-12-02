/*
 * Copyright 2018 the original author or authors.
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

package org.springframework.cloud.dataflow.server.rest.documentation;

import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import org.springframework.cloud.dataflow.core.ApplicationType;

import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.restdocs.payload.PayloadDocumentation.subsectionWithPath;
import static org.springframework.restdocs.request.RequestDocumentation.parameterWithName;
import static org.springframework.restdocs.request.RequestDocumentation.pathParameters;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Documentation for the /tasks/validation endpoint.
 *
 * @author Glenn Renfro
 */
@SuppressWarnings("NewClassNamingConvention")
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class TaskValidationDocumentation extends BaseDocumentation {

    @Before
    public void setup() throws Exception {
        registerApp(ApplicationType.task, "timestamp", "1.2.0.RELEASE");
        createTaskDefinition("taskC");
    }

    @After
    public void tearDown() throws Exception {
        destroyTaskDefinition("taskC");
        unregisterApp(ApplicationType.task, "timestamp");
    }

    @Test
    public void validateTask() throws Exception {
        this.mockMvc.perform(
            get("/tasks/validation/{name}", "taskC"))
            .andExpect(status().isOk())
            .andDo(this.documentationHandler.document(
                pathParameters(
                    parameterWithName("name").description("The name of a task definition to be validated (required)")
                ),
                responseFields(
                    fieldWithPath("appName").description("The name of a task definition"),
                    fieldWithPath("dsl").description("The dsl of a task definition"),
                    fieldWithPath("description").description("The description of the task definition"),
                    subsectionWithPath("appStatuses").description("The status of the application instances")
                )
            ));
    }

    private void createTaskDefinition(String taskName) throws Exception {
        documentation.dontDocument(() -> this.mockMvc.perform(
                post("/tasks/definitions")
                        .param("name", taskName)
                        .param("definition", "timestamp --format='yyyy MM dd'"))
                .andExpect(status().isOk()));
    }

    private void destroyTaskDefinition(String taskName) throws Exception {
        documentation.dontDocument(() -> this.mockMvc.perform(
                delete("/tasks/definitions/{name}", taskName))
                .andExpect(status().isOk()));
    }
}
