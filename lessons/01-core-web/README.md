# Lesson 01 — Core Container & Web

No Docker needed. This lesson is pure Spring: the IoC container, configuration, and Spring MVC.

```bash
make run-01                     # http://localhost:8081
# or
./mvnw -pl lessons/01-core-web spring-boot:run
```

---

## Theory: the IoC container and Spring MVC, briefly

**Spring vs. Spring Boot.** Spring is a dependency-injection container: you declare objects
("beans") and their dependencies, and the container builds and wires the object graph for you.
Spring Boot doesn't replace that — it removes the setup tax around it: auto-configuration that
inspects the classpath and wires sensible defaults, an embedded servlet container (Tomcat by
default) so `java -jar` is a complete deployable, and starter POMs that pull in a curated,
version-aligned set of dependencies.

**Inversion of Control and Dependency Injection.** In plain Java, an object that needs a
collaborator constructs it directly (`new PostgresRepository()`) — it controls its own
dependencies. IoC flips that: something external (the container) decides which implementation
you get. DI is the mechanism — the container passes the dependency in, ideally through the
constructor. The payoff: code depending on an interface doesn't know or care which
implementation it received, so swapping implementations, mocking in tests, or picking a
different bean per environment costs nothing in the code that uses it.

**The `ApplicationContext`.** This is the container itself — it reads your configuration
(annotations, here), builds beans in dependency order, applies any post-processing, and hands
out fully-wired singletons for the app's lifetime. `@SpringBootApplication` folds three
annotations into one: `@Configuration` (this class can declare beans), `@ComponentScan` (find
`@Component`/`@Service`/`@Controller` classes under this package), and
`@EnableAutoConfiguration` (let Boot's classpath-driven defaults kick in).

**Bean scopes, and the prototype trap.** Most beans are **singleton** — one instance per
container, created once and reused everywhere. Other scopes exist (`prototype`: a new instance
per request for the bean; `request`/`session`: web-scoped), but they interact awkwardly with
singletons: wire a `prototype` bean into a singleton via plain constructor injection and you get
exactly **one** instance, created at startup, reused forever — that's the "prototype trap"
you'll reproduce in the exercises below.

**Spring MVC's request path.** Every request lands on `DispatcherServlet` — the one servlet in
front of everything — which asks a `HandlerMapping` which controller method owns the URL,
invokes it (converting the body via `HttpMessageConverter`s, validating with `@Valid`), and
either serializes the return value straight to the response body (`@RestController`) or resolves
a view. Filters run *before* the servlet, with no knowledge of Spring MVC; interceptors run
*around* the handler and know which controller method is about to execute — the distinction the
`RequestIdFilter` / `TimingInterceptor` pair below exists to make concrete.

---

## What each file teaches

| Topic | File | Asked as |
|---|---|---|
| `@SpringBootApplication` internals | [CoreWebApplication.java](src/main/java/com/learning/coreweb/CoreWebApplication.java) | "What does that annotation actually do?" |
| `@Primary` / `@Qualifier` resolution | [EnglishGreetingService.java](src/main/java/com/learning/coreweb/ioc/EnglishGreetingService.java) | "Two beans of the same type — what happens?" |
| `@ConditionalOnProperty` | [PirateGreetingService.java](src/main/java/com/learning/coreweb/ioc/PirateGreetingService.java) | "How does auto-configuration decide?" |
| Bean lifecycle order | [LifecycleBean.java](src/main/java/com/learning/coreweb/ioc/LifecycleBean.java) | "Walk me through the bean lifecycle" |
| `BeanPostProcessor` vs `BeanFactoryPostProcessor` | [AuditingBeanPostProcessor.java](src/main/java/com/learning/coreweb/ioc/AuditingBeanPostProcessor.java) | "Where do AOP proxies get created?" |
| Scopes + the prototype trap | [ScopeConfig.java](src/main/java/com/learning/coreweb/ioc/ScopeConfig.java) | "Prototype injected into a singleton — how many instances?" |
| Constructor injection rationale | [ContainerController.java](src/main/java/com/learning/coreweb/ioc/ContainerController.java) | "Why not field injection?" |
| `@ConfigurationProperties` + validation | [AppProperties.java](src/main/java/com/learning/coreweb/config/AppProperties.java) | "`@Value` vs `@ConfigurationProperties`?" |
| `WebMvcConfigurer` vs `@EnableWebMvc` | [WebConfig.java](src/main/java/com/learning/coreweb/config/WebConfig.java) | "How do you customise MVC safely?" |
| REST semantics, 201 + Location | [TaskController.java](src/main/java/com/learning/coreweb/web/TaskController.java) | "What status code for a create?" |
| `ProblemDetail`, advice ordering | [GlobalExceptionHandler.java](src/main/java/com/learning/coreweb/web/GlobalExceptionHandler.java) | "How do you handle errors globally?" |
| Filter vs Interceptor | [RequestIdFilter.java](src/main/java/com/learning/coreweb/web/RequestIdFilter.java), [TimingInterceptor.java](src/main/java/com/learning/coreweb/web/TimingInterceptor.java) | "Where would you put correlation IDs?" |
| Custom health, liveness vs readiness | [TaskCapacityHealthIndicator.java](src/main/java/com/learning/coreweb/observability/TaskCapacityHealthIndicator.java) | "How does `/actuator/health` decide UP/DOWN?" |

---

## Drive it from the terminal

```bash
# --- CRUD ---
curl -s localhost:8081/api/tasks | jq

curl -si -X POST localhost:8081/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"title":"Learn bean scopes","description":"lesson 01","priority":"HIGH"}' | head -20
#  -> 201 Created + Location: http://localhost:8081/api/tasks/1

curl -s localhost:8081/api/tasks/1 | jq
curl -s 'localhost:8081/api/tasks?priority=HIGH&done=false' | jq
curl -s -X DELETE -o /dev/null -w '%{http_code}\n' localhost:8081/api/tasks/1   # 204

# --- validation: RFC 7807 body with per-field errors ---
curl -s -X POST localhost:8081/api/tasks \
  -H 'Content-Type: application/json' \
  -d '{"title":"   ","priority":null}' | jq
# { "title": "Validation error", "status": 400,
#   "fieldErrors": { "title": "title must not be blank", "priority": "priority is required" } }

# --- 404 as ProblemDetail ---
curl -s localhost:8081/api/tasks/999 | jq

# --- container introspection ---
curl -s localhost:8081/api/container/greetings | jq
curl -s localhost:8081/api/container/lifecycle | jq
curl -s localhost:8081/api/container/scopes | jq   # run twice and compare

# --- correlation id echoes back and appears in the server log ---
curl -si localhost:8081/api/tasks -H 'X-Request-Id: my-trace-1' | grep -i x-request-id

# --- actuator ---
curl -s localhost:8081/actuator/health | jq
curl -s localhost:8081/actuator/configprops | jq '.contexts[].beans | keys'
curl -s localhost:8081/actuator/mappings | jq '.. | .predicate? // empty' | sort -u
```

---

## Exercises that mirror real questions

**1. Prove the prototype trap.**
```bash
curl -s localhost:8081/api/container/scopes | jq
curl -s localhost:8081/api/container/scopes | jq
```
`injectedOncePrototype` never changes — the singleton controller was wired once at startup.
`freshFromProviderA/B` change every call because they come from `ObjectProvider.getObject()`.
`requestScoped*` is stable *within* a response but differs *between* responses.

**2. Watch a `@Conditional` bean appear.**
```bash
./mvnw -pl lessons/01-core-web spring-boot:run \
  -Dspring-boot.run.arguments=--app.feature.pirate-mode-enabled=true
curl -s localhost:8081/api/container/greetings | jq .allImplementations
# ["english","pirate"]
```
Now the app has two `GreetingService` beans and still starts — because `@Primary` breaks the tie. Delete `@Primary` from `EnglishGreetingService` and restart to see `NoUniqueBeanDefinitionException` at **startup**, not at request time.

**3. Property precedence.** `application.yml` sets `app.max-tasks: 100`, `application-dev.yml` overrides to `25`. A command-line arg beats both:
```bash
./mvnw -pl lessons/01-core-web spring-boot:run -Dspring-boot.run.arguments=--app.max-tasks=2
curl -s localhost:8081/actuator/health | jq '.components.taskCapacity.details'
```
Create three tasks and the third returns **409 Conflict**, and the health indicator flips to `DOWN` (which makes `/actuator/health` return 503 — exactly how a bad indicator gets your pod restarted).

**4. Fail fast on bad config.** Set `app.max-tasks: 0` in `application.yml` and start the app. It refuses to boot with a binding validation error — that is `@Validated` on `AppProperties` earning its keep.

**5. See auto-configuration decide.**
```bash
./mvnw -pl lessons/01-core-web spring-boot:run -Dspring-boot.run.arguments=--debug 2>&1 | less
```
Search for `AUTO-CONFIGURATION REPORT`, then `Positive matches` / `Negative matches`. Every line names the `@Conditional` that fired and why.

---

## Tests

```bash
./mvnw -f lessons/01-core-web/pom.xml test      # 21 unit + slice tests, ~5s
./mvnw -f lessons/01-core-web/pom.xml verify    # + 5 integration tests on a real port
```

Three levels, deliberately:

| Level | Class | Loads | Speed |
|---|---|---|---|
| Unit | [TaskServiceTest](src/test/java/com/learning/coreweb/service/TaskServiceTest.java) | nothing — plain `new` | ms |
| Slice | [TaskControllerTest](src/test/java/com/learning/coreweb/web/TaskControllerTest.java) | `@WebMvcTest`: web layer only, service is `@MockitoBean` | ~1s |
| Integration | [CoreWebApplicationIT](src/test/java/com/learning/coreweb/CoreWebApplicationIT.java) | `@SpringBootTest(RANDOM_PORT)`: everything + Tomcat | ~1.5s |

Note the naming: `*Test` → surefire (`mvn test`), `*IT` → failsafe (`mvn verify`). Wired in the [parent pom](../pom.xml).

---

## The one bug this lesson was built around

The first version of `GlobalExceptionHandler` was a plain `@RestControllerAdvice` with
`@ExceptionHandler(MethodArgumentNotValidException.class)`. The test asserting a
`"Validation error"` title **failed** — the response said `"Bad Request"`.

Cause: with `spring.mvc.problemdetails.enabled=true`, Boot registers
`ProblemDetailsExceptionHandler` at `@Order(0)`, while an unannotated advice sits at
`Ordered.LOWEST_PRECEDENCE`. Spring's handler won.

The fix is *not* `@Order(HIGHEST_PRECEDENCE)` — that would make the catch-all
`@ExceptionHandler(Exception.class)` outrank the framework's 404/405/415 handlers and turn
them all into 500s. The fix is to **extend `ResponseEntityExceptionHandler`** and override
`handleMethodArgumentNotValid`. Within a single advice the most specific handler wins, so
the framework cases keep working.

If you can explain that trade-off out loud, you are ahead of most candidates.

---

Next: [Lesson 02 — Data & JPA](../02-data-jpa/) (needs Postgres on Colima).
