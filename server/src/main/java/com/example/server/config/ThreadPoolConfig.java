package com.example.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync //开启异步注解支持
public class ThreadPoolConfig {

    @Bean("aiTaskExecutor") //给线程池起个名
    public Executor aiTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);

        // 3. 队列容量：如果 8 个人都忙，新的任务在门口排队，最多排 100 个
        executor.setQueueCapacity(100);

        // 4. 线程名称前缀：方便在日志里看是谁干的活
        executor.setThreadNamePrefix("AI-Thread-");

        // 5. 拒绝策略：如果队伍排满了(100个)，还有新任务咋办？
        // CallerRunsPolicy: 让发任务的老板(主线程)自己去干，别把任务扔了。
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.initialize();
        return executor;
    }
}