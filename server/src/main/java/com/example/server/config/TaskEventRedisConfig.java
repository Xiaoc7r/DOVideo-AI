package com.example.server.config;

import com.example.server.service.TaskEventService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class TaskEventRedisConfig {

    @Bean
    public RedisMessageListenerContainer taskEventListenerContainer(
            RedisConnectionFactory connectionFactory,
            TaskEventService taskEventService) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(taskEventService, new ChannelTopic(TaskEventService.REDIS_CHANNEL));
        return container;
    }
}
