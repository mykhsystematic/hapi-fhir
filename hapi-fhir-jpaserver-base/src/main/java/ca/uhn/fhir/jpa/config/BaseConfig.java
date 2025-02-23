package ca.uhn.fhir.jpa.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.i18n.HapiLocalizer;
import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.interceptor.executor.InterceptorService;
import ca.uhn.fhir.jpa.binstore.BinaryAccessProvider;
import ca.uhn.fhir.jpa.binstore.BinaryStorageInterceptor;
import ca.uhn.fhir.jpa.bulk.BulkDataExportProvider;
import ca.uhn.fhir.jpa.bulk.BulkDataExportSvcImpl;
import ca.uhn.fhir.jpa.bulk.IBulkDataExportSvc;
import ca.uhn.fhir.jpa.dao.DaoRegistry;
import ca.uhn.fhir.jpa.delete.DeleteConflictService;
import ca.uhn.fhir.jpa.graphql.JpaStorageServices;
import ca.uhn.fhir.jpa.interceptor.JpaConsentContextServices;
import ca.uhn.fhir.jpa.model.sched.ISchedulerService;
import ca.uhn.fhir.jpa.provider.SubscriptionTriggeringProvider;
import ca.uhn.fhir.jpa.provider.TerminologyUploaderProvider;
import ca.uhn.fhir.jpa.sched.AutowiringSpringBeanJobFactory;
import ca.uhn.fhir.jpa.sched.SchedulerServiceImpl;
import ca.uhn.fhir.jpa.search.DatabaseBackedPagingProvider;
import ca.uhn.fhir.jpa.search.IStaleSearchDeletingSvc;
import ca.uhn.fhir.jpa.search.StaleSearchDeletingSvcImpl;
import ca.uhn.fhir.jpa.search.cache.DatabaseSearchCacheSvcImpl;
import ca.uhn.fhir.jpa.search.cache.DatabaseSearchResultCacheSvcImpl;
import ca.uhn.fhir.jpa.search.cache.ISearchCacheSvc;
import ca.uhn.fhir.jpa.search.cache.ISearchResultCacheSvc;
import ca.uhn.fhir.jpa.search.reindex.IResourceReindexingSvc;
import ca.uhn.fhir.jpa.search.reindex.ResourceReindexingSvcImpl;
import ca.uhn.fhir.jpa.searchparam.registry.ISearchParamRegistry;
import ca.uhn.fhir.jpa.searchparam.registry.SearchParamRegistryImpl;
import ca.uhn.fhir.jpa.subscription.SubscriptionActivatingInterceptor;
import ca.uhn.fhir.jpa.subscription.dbmatcher.CompositeInMemoryDaoSubscriptionMatcher;
import ca.uhn.fhir.jpa.subscription.dbmatcher.DaoSubscriptionMatcher;
import ca.uhn.fhir.jpa.subscription.module.cache.LinkedBlockingQueueSubscribableChannelFactory;
import ca.uhn.fhir.jpa.subscription.module.channel.ISubscribableChannelFactory;
import ca.uhn.fhir.jpa.subscription.module.matcher.ISubscriptionMatcher;
import ca.uhn.fhir.jpa.subscription.module.matcher.InMemorySubscriptionMatcher;
import ca.uhn.fhir.jpa.term.TermCodeSystemStorageSvcImpl;
import ca.uhn.fhir.jpa.term.TermDeferredStorageSvcImpl;
import ca.uhn.fhir.jpa.term.api.ITermVersionAdapterSvc;
import ca.uhn.fhir.jpa.term.TermReindexingSvcImpl;
import ca.uhn.fhir.jpa.term.api.ITermCodeSystemStorageSvc;
import ca.uhn.fhir.jpa.term.api.ITermDeferredStorageSvc;
import ca.uhn.fhir.jpa.term.api.ITermReindexingSvc;
import ca.uhn.fhir.rest.server.interceptor.consent.IConsentContextServices;
import org.hibernate.jpa.HibernatePersistenceProvider;
import org.hl7.fhir.utilities.graphql.IGraphQLStorageServices;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.scheduling.concurrent.ScheduledExecutorFactoryBean;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;

/*
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2019 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */


@Configuration
@EnableJpaRepositories(basePackages = "ca.uhn.fhir.jpa.dao.data")
@ComponentScan(basePackages = "ca.uhn.fhir.jpa", excludeFilters = {
	@ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, value = BaseConfig.class),
	@ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, value = WebSocketConfigurer.class),
	@ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.test\\..*"),
	@ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*Test.*"),
	@ComponentScan.Filter(type = FilterType.REGEX, pattern = "ca.uhn.fhir.jpa.subscription.module.standalone.*")})
public abstract class BaseConfig {

	public static final String TASK_EXECUTOR_NAME = "hapiJpaTaskExecutor";
	public static final String GRAPHQL_PROVIDER_NAME = "myGraphQLProvider";

	@Autowired
	protected Environment myEnv;

	@Bean("myDaoRegistry")
	public DaoRegistry daoRegistry() {
		return new DaoRegistry();
	}

	@Bean
	public DatabaseBackedPagingProvider databaseBackedPagingProvider() {
		return new DatabaseBackedPagingProvider();
	}

	/**
	 * This method should be overridden to provide an actual completed
	 * bean, but it provides a partially completed entity manager
	 * factory with HAPI FHIR customizations
	 */
	protected LocalContainerEntityManagerFactoryBean entityManagerFactory() {
		LocalContainerEntityManagerFactoryBean retVal = new HapiFhirLocalContainerEntityManagerFactoryBean();
		configureEntityManagerFactory(retVal, fhirContext());
		return retVal;
	}

	public abstract FhirContext fhirContext();

	@Bean
	@Lazy
	public IGraphQLStorageServices graphqlStorageServices() {
		return new JpaStorageServices();
	}

	@Bean
	public ScheduledExecutorFactoryBean scheduledExecutorService() {
		ScheduledExecutorFactoryBean b = new ScheduledExecutorFactoryBean();
		b.setPoolSize(5);
		b.afterPropertiesSet();
		return b;
	}

	@Bean
	public ISearchParamRegistry searchParamRegistry() {
		return new SearchParamRegistryImpl();
	}


	@Bean(name = "mySubscriptionTriggeringProvider")
	@Lazy
	public SubscriptionTriggeringProvider subscriptionTriggeringProvider() {
		return new SubscriptionTriggeringProvider();
	}

	@Bean
	public SubscriptionActivatingInterceptor subscriptionActivatingInterceptor() {
		return new SubscriptionActivatingInterceptor();
	}

	@Bean(name = "myAttachmentBinaryAccessProvider")
	@Lazy
	public BinaryAccessProvider binaryAccessProvider() {
		return new BinaryAccessProvider();
	}

	@Bean(name = "myBinaryStorageInterceptor")
	@Lazy
	public BinaryStorageInterceptor binaryStorageInterceptor() {
		return new BinaryStorageInterceptor();
	}

	@Bean
	public ISearchCacheSvc searchCacheSvc() {
		return new DatabaseSearchCacheSvcImpl();
	}

	@Bean
	public ISearchResultCacheSvc searchResultCacheSvc() {
		return new DatabaseSearchResultCacheSvcImpl();
	}

	@Bean
	public TaskScheduler taskScheduler() {
		ConcurrentTaskScheduler retVal = new ConcurrentTaskScheduler();
		retVal.setConcurrentExecutor(scheduledExecutorService().getObject());
		retVal.setScheduledExecutor(scheduledExecutorService().getObject());
		return retVal;
	}

	@Bean(name = TASK_EXECUTOR_NAME)
	public AsyncTaskExecutor taskExecutor() {
		ConcurrentTaskScheduler retVal = new ConcurrentTaskScheduler();
		retVal.setConcurrentExecutor(scheduledExecutorService().getObject());
		retVal.setScheduledExecutor(scheduledExecutorService().getObject());
		return retVal;
	}

	@Bean
	public IResourceReindexingSvc resourceReindexingSvc() {
		return new ResourceReindexingSvcImpl();
	}

	@Bean
	public IStaleSearchDeletingSvc staleSearchDeletingSvc() {
		return new StaleSearchDeletingSvcImpl();
	}

	@Bean
	public InMemorySubscriptionMatcher inMemorySubscriptionMatcher() {
		return new InMemorySubscriptionMatcher();
	}

	@Bean
	public DaoSubscriptionMatcher daoSubscriptionMatcher() {
		return new DaoSubscriptionMatcher();
	}

	/**
	 * Create a @Primary @Bean if you need a different implementation
	 */
	@Bean
	public ISubscribableChannelFactory linkedBlockingQueueSubscribableChannelFactory() {
		return new LinkedBlockingQueueSubscribableChannelFactory();
	}

	@Bean
	@Primary
	public ISubscriptionMatcher subscriptionMatcherCompositeInMemoryDatabase() {
		return new CompositeInMemoryDaoSubscriptionMatcher(daoSubscriptionMatcher(), inMemorySubscriptionMatcher());
	}

	@Bean
	public HapiFhirHibernateJpaDialect hibernateJpaDialect() {
		return new HapiFhirHibernateJpaDialect(fhirContext().getLocalizer());
	}

	@Bean
	public PersistenceExceptionTranslationPostProcessor persistenceExceptionTranslationPostProcessor() {
		return new PersistenceExceptionTranslationPostProcessor();
	}

	@Bean
	public IInterceptorService jpaInterceptorService() {
		return new InterceptorService();
	}

	/**
	 * Subclasses may override
	 */
	protected boolean isSupported(String theResourceType) {
		return daoRegistry().getResourceDaoOrNull(theResourceType) != null;
	}

	@Bean
	public IConsentContextServices consentContextServices() {
		return new JpaConsentContextServices();
	}

	@Bean
	@Lazy
	public TerminologyUploaderProvider terminologyUploaderProvider() {
		TerminologyUploaderProvider retVal = new TerminologyUploaderProvider();
		return retVal;
	}

	@Bean
	public ISchedulerService schedulerService() {
		return new SchedulerServiceImpl();
	}

	@Bean
	public AutowiringSpringBeanJobFactory schedulerJobFactory() {
		return new AutowiringSpringBeanJobFactory();
	}

	@Bean
	@Lazy
	public IBulkDataExportSvc bulkDataExportSvc() {
		return new BulkDataExportSvcImpl();
	}

	@Bean
	@Lazy
	public BulkDataExportProvider bulkDataExportProvider() {
		return new BulkDataExportProvider();
	}


	public static void configureEntityManagerFactory(LocalContainerEntityManagerFactoryBean theFactory, FhirContext theCtx) {
		theFactory.setJpaDialect(hibernateJpaDialect(theCtx.getLocalizer()));
		theFactory.setPackagesToScan("ca.uhn.fhir.jpa.model.entity", "ca.uhn.fhir.jpa.entity");
		theFactory.setPersistenceProvider(new HibernatePersistenceProvider());
	}

	private static HapiFhirHibernateJpaDialect hibernateJpaDialect(HapiLocalizer theLocalizer) {
		return new HapiFhirHibernateJpaDialect(theLocalizer);
	}


}
