package com.learning.coreweb;

import com.learning.coreweb.domain.Priority;
import com.learning.coreweb.web.dto.TaskDtos.CreateTaskRequest;
import com.learning.coreweb.web.dto.TaskDtos.TaskResponse;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LEVEL 3: full integration. A real embedded Tomcat on a random port, the real context,
 * real HTTP over the loopback interface.
 *
 * INTERVIEW: "webEnvironment options for @SpringBootTest?"
 *   MOCK (default)  - no server; use with @AutoConfigureMockMvc. Fastest.
 *   RANDOM_PORT     - real server on a free port, injected via @LocalServerPort or
 *                     auto-configured TestRestTemplate. Use in CI to avoid port clashes.
 *   DEFINED_PORT    - real server on server.port. Avoid: flaky in parallel builds.
 *   NONE            - context only, no web environment at all.
 *
 * Also worth knowing: the Spring TestContext framework CACHES the ApplicationContext
 * across test classes, keyed on the full configuration (classes, profiles, properties,
 * @MockitoBean set). Keep those consistent and a 40-class suite loads one context; vary
 * them carelessly and you pay the startup cost over and over. @DirtiesContext evicts the
 * cache entry and should be a last resort.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class CoreWebApplicationIT {

    /** Reusable, type-safe target for JSON objects — avoids raw Map warnings. */
    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private TestRestTemplate restTemplate;

    private Map<String, Object> getJson(String path) {
        var response = restTemplate.exchange(path, HttpMethod.GET, null, JSON_OBJECT);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> source, String... keys) {
        Map<String, Object> current = source;
        for (String key : keys) {
            assertThat(current).containsKey(key);
            current = (Map<String, Object>) current.get(key);
        }
        return current;
    }

    @Test
    @DisplayName("full CRUD round trip over real HTTP")
    void crudRoundTrip() {
        var create = new CreateTaskRequest("Integration task", "created by the IT", Priority.MEDIUM);

        var createResponse = restTemplate.postForEntity("/api/tasks", create, TaskResponse.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getHeaders().getLocation()).isNotNull();

        TaskResponse created = createResponse.getBody();
        assertThat(created).isNotNull();
        assertThat(created.title()).isEqualTo("Integration task");
        assertThat(created.done()).isFalse();

        URI location = createResponse.getHeaders().getLocation();
        var fetched = restTemplate.getForEntity(location, TaskResponse.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().id()).isEqualTo(created.id());

        restTemplate.delete(location);

        var afterDelete = restTemplate.getForEntity(location, String.class);
        assertThat(afterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("actuator health includes the custom taskCapacity contributor")
    void actuatorExposesCustomHealthIndicator() {
        Map<String, Object> health = getJson("/actuator/health");

        assertThat(health).containsKey("status");
        assertThat(nested(health, "components")).containsKey("taskCapacity");
    }

    @Test
    @DisplayName("the dev profile lowers app.max-tasks to 25")
    void profileSpecificPropertyWins() {
        Map<String, Object> details = nested(getJson("/actuator/health"),
                "components", "taskCapacity", "details");

        // application.yml says 100, application-dev.yml says 25 -> the profile file wins.
        assertThat(details).containsEntry("capacity", 25);
    }

    @Test
    @DisplayName("the pirate bean is absent unless its @ConditionalOnProperty flag is set")
    void conditionalBeanIsAbsentByDefault() {
        Map<String, Object> greetings = getJson("/api/container/greetings");

        assertThat(greetings.get("allImplementations"))
                .asInstanceOf(InstanceOfAssertFactories.list(String.class))
                .containsExactly("english");
        assertThat(greetings).containsEntry("primaryPicked", "EnglishGreetingService");
    }

    @Test
    @DisplayName("a prototype injected into a singleton stays fixed; ObjectProvider gives fresh ones")
    void prototypeTrapIsVisibleOverHttp() {
        Map<String, Object> first = getJson("/api/container/scopes");
        Map<String, Object> second = getJson("/api/container/scopes");

        // Injected once at startup -> identical across requests.
        assertThat(first.get("injectedOncePrototype")).isEqualTo(second.get("injectedOncePrototype"));

        // ObjectProvider.getObject() -> a genuinely new instance every call.
        assertThat(first.get("freshFromProviderA")).isNotEqualTo(first.get("freshFromProviderB"));

        // Request scope: stable within one request, different between requests.
        assertThat(first.get("requestScopedFirstRead")).isEqualTo(first.get("requestScopedSecondRead"));
        assertThat(first.get("requestScopedFirstRead")).isNotEqualTo(second.get("requestScopedFirstRead"));
    }
}
