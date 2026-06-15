package com.milan.liquidation_engine.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic auditLogsTopic() {
        return TopicBuilder.name("audit-logs")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic marketDataTopic() {
        return TopicBuilder.name("market-data")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic liquidationEventsTopic() {
        return TopicBuilder.name("liquidation-events")
                .partitions(3)
                .replicas(1)
                .build();
    }
}
