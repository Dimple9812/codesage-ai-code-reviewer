package com.codesage.config;

import java.time.Clock;
import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.slf4j.MDC;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

@Configuration
public class CoreConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Bounded pool for review jobs so a burst of webhooks cannot exhaust the server. */
    @Bean(name = "reviewExecutor")
    Executor reviewExecutor(AppProperties props) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, props.review().workerThreads()));
        executor.setMaxPoolSize(Math.max(1, props.review().workerThreads()));
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("review-");
        executor.setTaskDecorator(mdcPropagating());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    private static TaskDecorator mdcPropagating() {
        return runnable -> {
            var context = MDC.getCopyOfContextMap();
            return () -> {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        };
    }

    @Bean
    OpenAPI codeSageOpenApi() {
        return new OpenAPI().info(new Info()
                .title("CodeSage AI API")
                .version("1.0.0")
                .description("AI-powered GitHub code review platform. Authentication uses a GitHub OAuth session cookie; "
                        + "state-changing requests require the X-XSRF-TOKEN header.")
                .license(new License().name("MIT")));
    }
}
