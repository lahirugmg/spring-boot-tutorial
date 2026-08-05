package com.learning.coreweb.service;

import com.learning.coreweb.config.AppProperties;
import com.learning.coreweb.domain.Priority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LEVEL 1 of the pyramid: a plain JUnit test. No @SpringBootTest, no context, no Docker.
 * Runs in milliseconds.
 *
 * INTERVIEW: "How do you decide what kind of test to write?"
 * Most of your tests should look like this. If a class needs the Spring container to be
 * testable, that is usually a design smell — constructor injection means you can just
 * `new` it. Reserve context-loading tests for wiring and integration concerns.
 *
 * Note the fixed Clock: the assertion on createdAt is exact, not "roughly now".
 */
class TaskServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-04T10:15:30Z");

    private TaskService taskService;

    @BeforeEach
    void setUp() {
        var properties = new AppProperties("english", 3, null,
                new AppProperties.Feature(false, true));
        taskService = new TaskService(properties, Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("create assigns an incrementing id and the injected clock's instant")
    void createAssignsIdAndTimestamp() {
        var first = taskService.create("Learn DI", "constructor injection", Priority.HIGH);
        var second = taskService.create("Learn AOP", null, Priority.LOW);

        assertThat(first.id()).isEqualTo(1L);
        assertThat(second.id()).isEqualTo(2L);
        assertThat(first.createdAt()).isEqualTo(FIXED_NOW);
        assertThat(first.done()).isFalse();
    }

    @Test
    @DisplayName("get throws TaskNotFoundException for an unknown id")
    void getUnknownIdThrows() {
        assertThatThrownBy(() -> taskService.get(404L))
                .isInstanceOf(TaskNotFoundException.class)
                .hasMessageContaining("404");
    }

    @Test
    @DisplayName("create beyond app.max-tasks throws CapacityExceededException")
    void capacityIsEnforced() {
        taskService.create("a", null, Priority.LOW);
        taskService.create("b", null, Priority.LOW);
        taskService.create("c", null, Priority.LOW);

        assertThatThrownBy(() -> taskService.create("d", null, Priority.LOW))
                .isInstanceOf(CapacityExceededException.class);
    }

    @Test
    @DisplayName("findAll applies priority and done filters")
    void filtersAreApplied() {
        taskService.create("high", null, Priority.HIGH);
        var low = taskService.create("low", null, Priority.LOW);
        taskService.update(low.id(), "low", null, Priority.LOW, true);

        assertThat(taskService.findAll(Optional.of(Priority.HIGH), Optional.empty())).hasSize(1);
        assertThat(taskService.findAll(Optional.empty(), Optional.of(true)))
                .singleElement()
                .satisfies(t -> assertThat(t.title()).isEqualTo("low"));
        assertThat(taskService.findAll(Optional.empty(), Optional.empty())).hasSize(2);
    }

    @Test
    @DisplayName("update preserves id and createdAt")
    void updatePreservesIdentity() {
        var created = taskService.create("old", "d", Priority.LOW);
        var updated = taskService.update(created.id(), "new", "d2", Priority.CRITICAL, true);

        assertThat(updated.id()).isEqualTo(created.id());
        assertThat(updated.createdAt()).isEqualTo(created.createdAt());
        assertThat(updated.title()).isEqualTo("new");
        assertThat(updated.done()).isTrue();
    }

    @Test
    void deleteUnknownIdThrows() {
        assertThatThrownBy(() -> taskService.delete(99L))
                .isInstanceOf(TaskNotFoundException.class);
    }
}
