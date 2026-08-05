package com.learning.coreweb.service;

import com.learning.coreweb.config.AppProperties;
import com.learning.coreweb.domain.Priority;
import com.learning.coreweb.domain.Task;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deliberately framework-light: no web types, no persistence types. It takes a Clock
 * rather than calling Instant.now() so tests are deterministic — a detail interviewers
 * notice when they ask "how would you test this?".
 *
 * INTERVIEW: "@Component vs @Service vs @Repository vs @Controller?"
 * Technically @Service and @Component are identical to the scanner — the stereotype is
 * documentation. The two that actually DO something:
 *   @Repository - adds PersistenceExceptionTranslationPostProcessor, converting vendor
 *                 SQL exceptions into Spring's DataAccessException hierarchy.
 *   @Controller - makes the class a handler-mapping candidate for MVC.
 */
@Service
public class TaskService {

    private final Map<Long, Task> store = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AppProperties properties;
    private final Clock clock;

    public TaskService(AppProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Task create(String title, String description, Priority priority) {
        if (store.size() >= properties.maxTasks()) {
            throw new CapacityExceededException(properties.maxTasks());
        }
        long id = sequence.incrementAndGet();
        Task task = new Task(id, title, description, priority, false, clock.instant());
        store.put(id, task);
        return task;
    }

    public Task get(long id) {
        return findById(id).orElseThrow(() -> new TaskNotFoundException(id));
    }

    public Optional<Task> findById(long id) {
        return Optional.ofNullable(store.get(id));
    }

    /** Filtering in memory here; module 02 shows the same thing pushed down to SQL. */
    public List<Task> findAll(Optional<Priority> priority, Optional<Boolean> done) {
        return store.values().stream()
                .filter(t -> priority.map(p -> t.priority() == p).orElse(true))
                .filter(t -> done.map(d -> t.done() == d).orElse(true))
                .sorted(Comparator.comparingLong(Task::id))
                .toList();
    }

    public Task update(long id, String title, String description, Priority priority, boolean done) {
        Task existing = get(id);
        Task updated = existing.withUpdates(title, description, priority, done);
        store.put(id, updated);
        return updated;
    }

    public void delete(long id) {
        if (store.remove(id) == null) {
            throw new TaskNotFoundException(id);
        }
    }

    public int count() {
        return store.size();
    }

    public int capacity() {
        return properties.maxTasks();
    }
}
