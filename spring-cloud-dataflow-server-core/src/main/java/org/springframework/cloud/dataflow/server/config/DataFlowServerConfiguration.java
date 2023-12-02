/*
 * Copyright 2015-2023 the original author or authors.
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

package org.springframework.cloud.dataflow.server.config;

import javax.persistence.EntityManager;
import javax.servlet.Filter;
import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.batch.BatchProperties;
import org.springframework.boot.autoconfigure.transaction.TransactionManagerCustomizers;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.dataflow.aggregate.task.AggregateTaskConfiguration;
import org.springframework.cloud.dataflow.aggregate.task.TaskRepositoryContainer;
import org.springframework.cloud.dataflow.aggregate.task.impl.DefaultTaskRepositoryContainer;
import org.springframework.cloud.dataflow.audit.service.AuditRecordService;
import org.springframework.cloud.dataflow.completion.CompletionConfiguration;
import org.springframework.cloud.dataflow.registry.repository.AppRegistrationRepositoryCustom;
import org.springframework.cloud.dataflow.registry.repository.AppRegistrationRepositoryImpl;
import org.springframework.cloud.dataflow.schema.service.SchemaService;
import org.springframework.cloud.dataflow.schema.service.SchemaServiceConfiguration;
import org.springframework.cloud.dataflow.server.config.apps.CommonApplicationProperties;
import org.springframework.cloud.dataflow.server.config.features.FeaturesConfiguration;
import org.springframework.cloud.dataflow.server.config.web.WebConfiguration;
import org.springframework.cloud.dataflow.server.db.migration.DataFlowFlywayConfigurationCustomizer;
import org.springframework.cloud.dataflow.server.support.AuthenticationSuccessEventListener;
import org.springframework.cloud.task.configuration.TaskProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Configuration for the Data Flow Server application context. This includes support for
 * the REST API framework configuration.
 *
 * @author Mark Fisher
 * @author Marius Bogoevici
 * @author Patrick Peralta
 * @author Thomas Risberg
 * @author Janne Valkealahti
 * @author Glenn Renfro
 * @author Josh Long
 * @author Michael Minella
 * @author Gunnar Hillert
 * @author Michael Wirth
 * @author Corneil du Plessis
 */
@EnableSpringDataWebSupport
@Configuration
@Import({
		CompletionConfiguration.class,
		FeaturesConfiguration.class,
		WebConfiguration.class,
		H2ServerConfiguration.class,
		SchemaServiceConfiguration.class,
		AggregateTaskConfiguration.class,
		AggregateDataFlowTaskConfiguration.class
})
@EnableConfigurationProperties({ BatchProperties.class, CommonApplicationProperties.class })
@ComponentScan(basePackages = {"org.springframework.cloud.dataflow.schema.service", "org.springframework.cloud.dataflow.aggregate.task"})
public class DataFlowServerConfiguration {

	@Bean
	public DataFlowFlywayConfigurationCustomizer dataFlowFlywayConfigurationCustomizer() {
		return new DataFlowFlywayConfigurationCustomizer();
	}

	@Bean
	public Filter forwardedHeaderFilter() {
		return new ForwardedHeaderFilter();
	}

	@Bean
	@Primary
	public PlatformTransactionManager transactionManager(
			ObjectProvider<TransactionManagerCustomizers> transactionManagerCustomizers) {
		JpaTransactionManager transactionManager = new JpaTransactionManager();
		transactionManagerCustomizers.ifAvailable((customizers) -> customizers.customize(transactionManager));
		return transactionManager;
	}


	@Bean
	public TaskProperties taskProperties() {
		return new TaskProperties();
	}


	@Bean
	public AuthenticationSuccessEventListener authenticationSuccessEventListener(
			AuditRecordService auditRecordService) {
		return new AuthenticationSuccessEventListener(auditRecordService);
	}

	@Bean
	public AppRegistrationRepositoryCustom appRegistrationRepositoryCustom(EntityManager entityManager) {
		return new AppRegistrationRepositoryImpl(entityManager);
	}
}
