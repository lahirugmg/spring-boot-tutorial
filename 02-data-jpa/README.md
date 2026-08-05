# Module 02 — Data & JPA

The densest interview territory. Needs Postgres on Colima.

```bash
make db          # postgres on localhost:55432 + adminer on http://localhost:8090
make run-02      # http://localhost:8082
```

Flyway creates the schema and seeds 6 authors / 20 books. Hibernate runs with
`ddl-auto: validate`, so the app refuses to start if the entities and schema ever drift.

---

## The four demos, and what they prove

### 1. N+1, measured

```bash
curl -s localhost:8082/api/demo/fetch-strategies | jq
```

```
strategy                    rows  statements
1-naive-lazy                  20     7     <- 1 + one per distinct author
2-join-fetch                  20     1
3-entity-graph                20     1
4-dto-projection              20     1     <- and zero entities loaded
5-collection-naive             6     7     <- the @OneToMany side
6-collection-join-fetch        6     1
```

Note it is **7, not 21**. The first-level cache dedupes repeated authors inside one
persistence context — which is precisely why N+1 looks harmless on small test data and
falls over in production. The numbers come from Hibernate's own `Statistics`
([QueryCounter.java](src/main/java/com/learning/datajpa/support/QueryCounter.java)), and
[BookRepositoryIT](src/test/java/com/learning/datajpa/repository/BookRepositoryIT.java)
asserts them, so a regression fails the build.

**Fix 4** is global batch fetching — no code change, bounds the damage everywhere:

```bash
./mvnw -pl 02-data-jpa spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dspring.jpa.properties.hibernate.default_batch_fetch_size=25"
```

Re-run the endpoint and watch rows 1 and 5 collapse from 7 statements to 2.

### 2. The self-invocation trap

```bash
curl -s localhost:8082/api/demo/self-invocation | jq
# { "viaProxy": true, "viaSelfInvocation": false }
```

`@Transactional` is proxy-based. `this.method()` never reaches the interceptor. Same for
`@Cacheable`, `@Async`, `@Retryable`, `@PreAuthorize`.

### 3. Propagation: REQUIRES_NEW vs REQUIRED

```bash
curl -s -X POST localhost:8082/api/demo/propagation/1 | jq
```

Both purchases roll back. The audit row written with `REQUIRES_NEW` **survives**; the one
written with `REQUIRED` dies with the caller. This is how you build an audit trail or a
transactional outbox that records failed attempts.

The catch worth naming in an interview: `REQUIRES_NEW` **suspends** the outer transaction
and takes a *second* connection from the pool. A pool of 10 with nested `REQUIRES_NEW`
under load can deadlock against itself.

### 4. Rollback rules

```bash
curl -s -X POST localhost:8082/api/demo/rollback-rules/6 | jq
```

```
caseA_default        @Transactional                              delta +100  COMMITTED
caseB_rollbackFor    @Transactional(rollbackFor = ...)           delta    0  ROLLED BACK
```

The default rollback rule is **`RuntimeException` and `Error` only**. A checked exception
commits everything done before it was thrown. This surprises nearly everyone.

### Bonus: LazyInitializationException on demand

```bash
curl -s localhost:8082/api/demo/lazy-initialization/1 | jq
```

Note `authorIdFromProxy` succeeds while `authorName` throws — an uninitialised proxy
already knows its own id (it was built from the FK), so reading the id costs no query.
Every other property forces initialisation.

---

## Topic → file map

| Topic | File |
|---|---|
| IDENTITY vs SEQUENCE, allocationSize trap | [Author.java](src/main/java/com/learning/datajpa/entity/Author.java) |
| entity `equals`/`hashCode` with proxies | [Author.java](src/main/java/com/learning/datajpa/entity/Author.java) |
| `mappedBy`, cascade, orphanRemoval, Set vs List | [Author.java](src/main/java/com/learning/datajpa/entity/Author.java) |
| fetch defaults (`@ManyToOne` is EAGER!) | [Book.java](src/main/java/com/learning/datajpa/entity/Book.java) |
| `@Version` optimistic locking | [Book.java](src/main/java/com/learning/datajpa/entity/Book.java) |
| repository hierarchy, derived queries | [BookRepository.java](src/main/java/com/learning/datajpa/repository/BookRepository.java) |
| JOIN FETCH / `@EntityGraph` / DTO projection | [BookRepository.java](src/main/java/com/learning/datajpa/repository/BookRepository.java) |
| Page vs Slice vs keyset pagination | [BookRepository.java](src/main/java/com/learning/datajpa/repository/BookRepository.java) |
| pessimistic locking, `@Modifying` pitfalls | [BookRepository.java](src/main/java/com/learning/datajpa/repository/BookRepository.java) |
| closed vs open interface projections | [BookSummary.java](src/main/java/com/learning/datajpa/projection/BookSummary.java) |
| dynamic filters, count-query trap | [BookSpecifications.java](src/main/java/com/learning/datajpa/repository/BookSpecifications.java) |
| propagation levels | [AuditService.java](src/main/java/com/learning/datajpa/service/AuditService.java) |
| dirty checking, rollback rules, isolation | [InventoryService.java](src/main/java/com/learning/datajpa/service/InventoryService.java) |
| `findById` vs `getReferenceById` | [CatalogService.java](src/main/java/com/learning/datajpa/service/CatalogService.java) |
| open-session-in-view | [application.yml](src/main/resources/application.yml) |
| Flyway vs `ddl-auto`, FK indexing | [V1__init.sql](src/main/resources/db/migration/V1__init.sql) |
| exception translation, 409 vs 500 | [JpaExceptionHandler.java](src/main/java/com/learning/datajpa/web/JpaExceptionHandler.java) |

---

## REST endpoints

```bash
# paginated + sorted + filtered (all filters optional, composed as a Specification)
curl -s 'localhost:8082/api/books?title=clean&onlyInStock=true&page=0&size=3&sort=price,desc' | jq
curl -s 'localhost:8082/api/books?country=Serbia' | jq '.totalElements'

curl -s localhost:8082/api/books/dto | jq '.[0]'          # DTO projection, 1 query
curl -s 'localhost:8082/api/books/summaries?authorId=3' | jq  # interface projection

# optimistic locking: watch `version` increment
curl -s -X PATCH localhost:8082/api/books/9/price \
  -H 'Content-Type: application/json' -d '{"price": 49.99}' | jq

# 409 Conflict with a proper ProblemDetail
curl -s -X POST localhost:8082/api/books/20/purchase \
  -H 'Content-Type: application/json' -d '{"quantity": 5000}' | jq

# unique-constraint violation -> translated DataIntegrityViolationException -> 409
curl -s -X POST localhost:8082/api/books \
  -H 'Content-Type: application/json' \
  -d '{"authorId":1,"title":"Dup","isbn":"978-0132350884","price":10.00,"stock":1}' | jq
```

Browse the data at **http://localhost:8090** (Adminer): server `postgres`, user/password
`app`, database `appdb`.

---

## Watch the SQL

Uncomment in [application.yml](src/main/resources/application.yml):

```yaml
logging:
  level:
    "[org.hibernate.SQL]": DEBUG           # every statement
    "[org.hibernate.orm.jdbc.bind]": TRACE # with bound parameters
```

Then hit `/api/demo/fetch-strategies` and literally count the `select` lines.

---

## Tests

```bash
./mvnw -f 02-data-jpa/pom.xml verify     # 21 tests against a real Postgres container
```

| Class | Kind | Why it is shaped that way |
|---|---|---|
| [BookRepositoryIT](src/test/java/com/learning/datajpa/repository/BookRepositoryIT.java) | `@DataJpaTest` | persistence slice; rolls back per test; asserts exact query counts |
| [TransactionBehaviourIT](src/test/java/com/learning/datajpa/service/TransactionBehaviourIT.java) | `@SpringBootTest`, **not** `@Transactional` | propagation and rollback can only be observed with real commits |
| [OptimisticLockingIT](src/test/java/com/learning/datajpa/service/OptimisticLockingIT.java) | `@SpringBootTest` + 2 `EntityManager`s | two persistence contexts are required to create a real conflict |

Containers come from [TestcontainersConfiguration](src/test/java/com/learning/datajpa/TestcontainersConfiguration.java)
— declared as a `@Bean` with `@ServiceConnection` so the cached Spring context shares **one**
container across all test classes.

### The Colima problem this repo already solves for you

Testcontainers fails out of the box against Colima's Docker 29.x with a misleading error:

```
Could not find a valid Docker environment
  EnvironmentAndSystemPropertyClientProviderStrategy: failed with BadRequestException
  (Status 400: client version 1.32 is too old. Minimum supported API version is 1.44)
```

Two fixes, both already applied in the [parent pom](../pom.xml) so `./mvnw verify` works
with no shell setup:

1. **`-Dapi.version=1.44`** — docker-java defaults to API 1.32, which Docker 29 rejects.
   It reads the `api.version` *system property*; the `DOCKER_API_VERSION` env var is **not**
   consulted.
2. **`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`** — Ryuk (the reaper
   sidecar) runs inside the VM and must bind-mount the socket at its *in-VM* path, not the
   host path in `DOCKER_HOST`.

Testcontainers is also pinned to **1.21.3** (Boot 3.5.3 manages 1.20.6).

---

Previous: [Module 01 — Core & Web](../01-core-web/) · Next: [Module 03 — Security](../03-security/)
