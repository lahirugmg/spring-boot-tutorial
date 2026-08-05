package com.learning.coreweb.observability;

import com.learning.coreweb.service.TaskService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * INTERVIEW: "How does Actuator health work, and how do you add your own check?"
 *
 * Implement HealthIndicator. The bean name minus the "HealthIndicator" suffix becomes the
 * key in /actuator/health — this class shows up as "taskCapacity".
 *
 * The overall status is the WORST of all contributors, using the configured status order
 * (DOWN < OUT_OF_SERVICE < UP < UNKNOWN by default). Any DOWN contributor makes the whole
 * endpoint return 503 — which is exactly why you must be careful about what you register:
 * a flaky third-party API marked DOWN will get your pod killed by Kubernetes.
 *
 * That is what liveness vs readiness probes are for:
 *   /actuator/health/liveness  - "is the JVM broken?" -> restart me
 *   /actuator/health/readiness - "can I serve traffic?" -> take me out of the load balancer
 * Enable with management.endpoint.health.probes.enabled=true (automatic on Kubernetes).
 */
@Component
public class TaskCapacityHealthIndicator implements HealthIndicator {

    private final TaskService taskService;

    public TaskCapacityHealthIndicator(TaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public Health health() {
        int used = taskService.count();
        int capacity = taskService.capacity();
        double utilisation = capacity == 0 ? 0 : (double) used / capacity;

        Health.Builder builder = utilisation >= 1.0 ? Health.down()
                : utilisation >= 0.8 ? Health.status("DEGRADED")
                : Health.up();

        return builder
                .withDetail("used", used)
                .withDetail("capacity", capacity)
                .withDetail("utilisation", Math.round(utilisation * 100) + "%")
                .build();
    }
}
