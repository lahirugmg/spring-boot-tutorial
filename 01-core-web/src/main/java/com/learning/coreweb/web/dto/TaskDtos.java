package com.learning.coreweb.web.dto;

import com.learning.coreweb.domain.Priority;
import com.learning.coreweb.domain.Task;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * INTERVIEW: "Why not just return the entity from the controller?"
 *   - it leaks internal structure and makes the DB schema part of your public API
 *   - it invites over-posting: a client sets a field you never meant to be writable
 *   - with JPA it triggers lazy-loading during serialisation (LazyInitializationException,
 *     or worse, an accidental N+1 inside the Jackson writer)
 *   - request and response shapes diverge over time; one class cannot serve both
 *
 * Separate request/response records make the contract explicit and validate at the edge.
 */
public final class TaskDtos {

    private TaskDtos() {}

    public record CreateTaskRequest(
            @NotBlank(message = "title must not be blank")
            @Size(max = 120, message = "title must be at most 120 characters")
            String title,

            @Size(max = 2000)
            String description,

            @NotNull(message = "priority is required")
            Priority priority
    ) {}

    public record UpdateTaskRequest(
            @NotBlank @Size(max = 120) String title,
            @Size(max = 2000) String description,
            @NotNull Priority priority,
            boolean done
    ) {}

    public record TaskResponse(
            long id,
            String title,
            String description,
            Priority priority,
            boolean done,
            Instant createdAt
    ) {
        public static TaskResponse from(Task task) {
            return new TaskResponse(task.id(), task.title(), task.description(),
                    task.priority(), task.done(), task.createdAt());
        }
    }
}
