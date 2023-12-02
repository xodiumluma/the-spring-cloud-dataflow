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

package org.springframework.cloud.dataflow.server.rest.documentation;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import org.springframework.http.MediaType;
import org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders;

import static org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath;
import static org.springframework.restdocs.payload.PayloadDocumentation.responseFields;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Creates asciidoc snippets for endpoints exposed by {@literal SchemaController}.

 * @author Corneil du Plessis
 */
@SuppressWarnings("NewClassNamingConvention")
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SchemaDocumentation extends BaseDocumentation {

	@Test
	public void schemaVersions() throws Exception {

		this.mockMvc.perform(RestDocumentationRequestBuilders
						.get("/schema/versions").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andDo(
						this.documentationHandler.document(
								responseFields(
										fieldWithPath("defaultSchemaVersion").description("The default version used when registering without a bootVersion"),
										fieldWithPath("versions").description("The list of versions supported")
								)
						)
				);
	}


	@Test
	public void schemaTargets() throws Exception {

		this.mockMvc.perform(RestDocumentationRequestBuilders
						.get("/schema/targets").accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andDo(this.documentationHandler.document());
	}
}
