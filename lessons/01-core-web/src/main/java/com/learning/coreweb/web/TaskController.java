package com.learning.coreweb.web;

import com.learning.coreweb.domain.Priority;
import com.learning.coreweb.service.TaskService;
import com.learning.coreweb.web.dto.TaskDtos.CreateTaskRequest;
import com.learning.coreweb.web.dto.TaskDtos.TaskResponse;
import com.learning.coreweb.web.dto.TaskDtos.UpdateTaskRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * A conventional REST resource. Points interviewers probe here:
 *
 *  - @RestController == @Controller + @ResponseBody (every method returns a body, not a
 *    view name). @Controller alone returns view names resolved by a ViewResolver.
 *
 *  - POST returns 201 Created with a Location header, not 200. GET is safe and idempotent,
 *    PUT is idempotent (same request twice = same state), POST is neither, DELETE is
 *    idempotent in effect (returning 204 both times is fine and usually preferred over 404).
 *
 *  - @Valid on the @RequestBody triggers JSR-380 validation. On failure Spring throws
 *    MethodArgumentNotValidException BEFORE your method body runs — handled centrally in
 *    GlobalExceptionHandler. (@Validated at class level is what you need for validating
 *    @RequestParam/@PathVariable, which uses a different exception type.)
 *
 *  - ResponseEntity<T> when you need to control status/headers; a plain T when 200 is
 *    always right. Do not wrap everything in ResponseEntity out of habit.
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public List<TaskResponse> list(@RequestParam Optional<Priority> priority,
                                   @RequestParam Optional<Boolean> done) {
        return taskService.findAll(priority, done).stream()
                .map(TaskResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public TaskResponse get(@PathVariable long id) {
        return TaskResponse.from(taskService.get(id));
    }

    @PostMapping
    public ResponseEntity<TaskResponse> create(@Valid @RequestBody CreateTaskRequest request,
                                               UriComponentsBuilder uriBuilder) {
        var created = taskService.create(request.title(), request.description(), request.priority());
        // Building the Location header from the injected UriComponentsBuilder keeps the
        // URL correct behind a proxy/context-path instead of hardcoding "/api/tasks/".
        URI location = uriBuilder.path("/api/tasks/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(TaskResponse.from(created));
    }

    @PutMapping("/{id}")
    public TaskResponse update(@PathVariable long id, @Valid @RequestBody UpdateTaskRequest request) {
        var updated = taskService.update(id, request.title(), request.description(),
                request.priority(), request.done());
        return TaskResponse.from(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        taskService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
