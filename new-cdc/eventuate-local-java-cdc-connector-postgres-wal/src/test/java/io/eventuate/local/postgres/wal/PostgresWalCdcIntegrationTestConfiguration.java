package io.eventuate.local.postgres.wal;

import io.eventuate.javaclient.driver.EventuateDriverConfiguration;
import io.eventuate.javaclient.spring.jdbc.EventuateSchema;
import io.eventuate.local.common.*;
import io.eventuate.local.db.log.common.DatabaseOffsetKafkaStore;
import io.eventuate.local.db.log.common.DbLogBasedCdcDataPublisher;
import io.eventuate.local.db.log.common.OffsetStore;
import io.eventuate.local.java.common.broker.DataProducerFactory;
import io.eventuate.local.java.kafka.EventuateKafkaConfigurationProperties;
import io.eventuate.local.java.kafka.producer.EventuateKafkaProducer;
import io.eventuate.local.testutil.SqlScriptEditor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.*;

import java.util.stream.Collectors;

@Configuration
@EnableAutoConfiguration
@Import(EventuateDriverConfiguration.class)
public class PostgresWalCdcIntegrationTestConfiguration {

  @Bean
  public EventuateConfigurationProperties eventuateConfigurationProperties() {
    return new EventuateConfigurationProperties();
  }

  @Bean
  public EventuateSchema eventuateSchema(@Value("${eventuate.database.schema:#{null}}") String eventuateDatabaseSchema) {
    return new EventuateSchema(eventuateDatabaseSchema);
  }

  @Bean
  @Profile("PostgresWal")
  public PostgresWalClient<PublishedEvent> postgresWalClient(@Value("${spring.datasource.url}") String dbUrl,
                                                             @Value("${spring.datasource.username}") String dbUserName,
                                                             @Value("${spring.datasource.password}") String dbPassword,
                                                             EventuateConfigurationProperties eventuateConfigurationProperties,
                                                             PostgresWalMessageParser<PublishedEvent> postgresWalMessageParser) {

    return new PostgresWalClient<>(postgresWalMessageParser,
            dbUrl,
            dbUserName,
            dbPassword,
            eventuateConfigurationProperties.getBinlogConnectionTimeoutInMilliseconds(),
            eventuateConfigurationProperties.getMaxAttemptsForBinlogConnection(),
            eventuateConfigurationProperties.getPostgresWalIntervalInMilliseconds(),
            eventuateConfigurationProperties.getPostgresReplicationStatusIntervalInMilliseconds(),
            eventuateConfigurationProperties.getPostgresReplicationSlotName());
  }

  @Bean
  @Profile("PostgresWal")
  public PostgresWalMessageParser<PublishedEvent> postgresWalMessageParser() {
    return new PostgresWalJsonMessageParser();
  }

  @Bean
  @Profile("PostgresWal")
  @Primary
  public OffsetStore databaseOffsetKafkaStore(EventuateConfigurationProperties eventuateConfigurationProperties,
                                                           EventuateKafkaConfigurationProperties eventuateKafkaConfigurationProperties,
                                                           EventuateKafkaProducer eventuateKafkaProducer) {

    return new DatabaseOffsetKafkaStore(eventuateConfigurationProperties.getDbHistoryTopicName(),
            eventuateConfigurationProperties.getMySqlBinLogClientName(),
            eventuateKafkaProducer,
            eventuateKafkaConfigurationProperties
    );
  }

  @Bean
  @Profile("PostgresWal")
  public CdcProcessor<PublishedEvent> cdcProcessor(PostgresWalClient<PublishedEvent> postgresWalClient,
                                                   OffsetStore offsetStore) {

    return new PostgresWalCdcProcessor<>(postgresWalClient, offsetStore);
  }

  @Bean
  @Profile("PostgresWal")
  public DbLogBasedCdcDataPublisher<PublishedEvent> dbLogBasedCdcKafkaPublisher(DataProducerFactory dataProducerFactory,
                                                                                EventuateKafkaConfigurationProperties eventuateKafkaConfigurationProperties,
                                                                                OffsetStore offsetStore,
                                                                                PublishingStrategy<PublishedEvent> publishingStrategy) {

    return new DbLogBasedCdcDataPublisher<>(dataProducerFactory,
            offsetStore,
            eventuateKafkaConfigurationProperties.getBootstrapServers(),
            publishingStrategy);
  }

  @Bean
  public EventuateKafkaProducer eventuateKafkaProducer(EventuateKafkaConfigurationProperties eventuateKafkaConfigurationProperties) {
    return new EventuateKafkaProducer(eventuateKafkaConfigurationProperties.getBootstrapServers());
  }

  @Bean
  public DataProducerFactory dataProducerFactory(EventuateKafkaConfigurationProperties eventuateKafkaConfigurationProperties) {
    return () -> new EventuateKafkaProducer(eventuateKafkaConfigurationProperties.getBootstrapServers());
  }

  @Bean
  public PublishingStrategy<PublishedEvent> publishingStrategy() {
    return new PublishedEventPublishingStrategy();
  }

  @Bean
  public SqlScriptEditor sqlScriptEditor() {
    return sqlList -> {
      sqlList.set(0, sqlList.get(0).replace("CREATE SCHEMA", "CREATE SCHEMA IF NOT EXISTS"));
      return sqlList.stream().map(s -> s.replace("eventuate", "custom")).collect(Collectors.toList());
    };
  }
}
