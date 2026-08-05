package com.learning.coreweb.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.coreweb.domain.Priority;
import com.learning.coreweb.domain.Task;
import com.learning.coreweb.service.CapacityExceededException;
import com.learning.coreweb.service.TaskNotFoundException;
import com.learning.coreweb.service.TaskService;
import com.learning.coreweb.web.dto.TaskDtos.CreateTaskRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LEVEL 2: a SLICE test.
 *
 * INTERVIEW: "@SpringBootTest vs @WebMvcTest?"
 *   @SpringBootTest - loads the ENTIRE context (every bean, every auto-configuration).
 *                     Slow. Use it for end-to-end wiring checks.
 *   @WebMvcTest     - loads only the web layer: @Controller, @ControllerAdvice, filters,
 *                     converters, WebMvcConfigurer, Jackson. Services and repositories
 *                     are NOT loaded, so you supply them with @MockitoBean.
 *
 * This is the right level to assert on HTTP semantics: status codes, headers, JSON shape,
 * validation behaviour, and error mapping. It exercises the real DispatcherServlet,
 * real argument resolvers and the real Jackson config — but no server socket is opened.
 *
 * Note @MockitoBean, not @MockBean: @MockBean is deprecated since Boot 3.4 in favour of
 * the framework-level bean override support in spring-test.
 */
@WebMvcTest(TaskController.class)
class TaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private TaskService taskService;

    private static Task sampleTask() {
        return new Task(1L, "Learn Spring", "for interviews", Priority.HIGH, false,
                Instant.parse("2026-08-04T10:15:30Z"));
    }

    @Test
    @DisplayName("GET /api/tasks returns the serialised list")
    void listReturnsTasks() throws Exception {
        given(taskService.findAll(any(), any())).willReturn(List.of(sampleTask()));

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].title").value("Learn Spring"))
                .andExpect(jsonPath("$[0].priority").value("HIGH"));
    }

    @Test
    @DisplayName("GET /api/tasks?priority=HIGH binds the enum query param")
    void listBindsEnumQueryParam() throws Exception {
        given(taskService.findAll(eq(Optional.of(Priority.HIGH)), eq(Optional.empty())))
                .willReturn(List.of(sampleTask()));

        mockMvc.perform(get("/api/tasks").param("priority", "HIGH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].priority").value("HIGH"));
    }

    @Test
    @DisplayName("POST /api/tasks returns 201 with a Location header")
    void createReturns201AndLocation() throws Exception {
        given(taskService.create(any(), any(), any())).willReturn(sampleTask());
        var request = new CreateTaskRequest("Learn Spring", "for interviews", Priority.HIGH);

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/tasks/1")))
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    @DisplayName("POST with a blank title returns 400 with per-field errors")
    void validationFailureReturnsProblemDetail() throws Exception {
        String body = """
                {"title": "  ", "description": "x", "priority": null}
                """;

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation error"))
                .andExpect(jsonPath("$.fieldErrors.title").exists())
                .andExpect(jsonPath("$.fieldErrors.priority").exists());
    }

    @Test
    @DisplayName("an unknown id maps to a 404 ProblemDetail")
    void notFoundMapsTo404() throws Exception {
        given(taskService.get(anyLong())).willThrow(new TaskNotFoundException(42L));

        mockMvc.perform(get("/api/tasks/42"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Task not found"))
                .andExpect(jsonPath("$.taskId").value(42))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("exceeding capacity maps to 409 Conflict")
    void capacityMapsTo409() throws Exception {
        given(taskService.create(any(), any(), any())).willThrow(new CapacityExceededException(25));
        var request = new CreateTaskRequest("x", null, Priority.LOW);

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.limit").value(25));
    }

    @Test
    @DisplayName("DELETE returns 204 No Content")
    void deleteReturns204() throws Exception {
        doNothing().when(taskService).delete(1L);

        mockMvc.perform(delete("/api/tasks/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("the RequestIdFilter echoes an X-Request-Id header back")
    void requestIdFilterIsApplied() throws Exception {
        given(taskService.findAll(any(), any())).willReturn(List.of());

        mockMvc.perform(get("/api/tasks").header(RequestIdFilter.HEADER, "abc-123"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.HEADER, "abc-123"));
    }

    @Test
    @DisplayName("a service error becomes a generic 500, never a leaked stack trace")
    void unexpectedErrorMapsTo500() throws Exception {
        willThrow(new IllegalStateException("db exploded")).given(taskService).delete(7L);

        mockMvc.perform(delete("/api/tasks/7"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Unexpected error, see server logs"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("db exploded"))));
    }
}
