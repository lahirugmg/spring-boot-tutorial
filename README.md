# Spring Boot Tutorial

A hands-on curriculum for learning Spring Boot — five small apps, each one runnable on its
own, each one teaching a layer of what real Spring Boot backends need: the IoC container and
web layer, then JPA, then security, then caching/resilience, then messaging. Every topic is
something you run and poke at with `curl`, not just read about.

Docker (via [Colima](https://github.com/abiosoft/colima)) backs the later lessons with real
Postgres, Redis, and Kafka — no mocks standing in for the pieces that actually cause
production incidents.

---

## Prerequisites

| Tool | Why | Check you have it |
|---|---|---|
| **Java 21** | All lessons target `java.version=21` | `java -version` |
| **Docker + Colima** (macOS) | Backing services for lessons 2–5 | `colima status` |
| **Maven** | Optional — the wrapper (`./mvnw`) is committed, so a system install isn't required | `./mvnw -v` |

If Colima isn't running yet:
```bash
make colima-up     # starts a 4 CPU / 8 GB VM
```

---

## Quick start

```bash
make run-01
```

Then in another terminal:
```bash
curl -s localhost:8081/api/tasks | jq
```

That's it — lesson 1 needs nothing but Java. No database, no Docker. Start there.

---

## The learning path

Go through these **in order** — each lesson assumes you're comfortable with the previous
one's ideas, and the docker-compose services layer on top of each other (lesson 3 reuses
lesson 2's Postgres, for example).

| # | Lesson | What it teaches | Needs | Run it |
|---|---|---|---|---|
| 1 | [01-core-web](lessons/01-core-web/) | IoC container, bean scopes & lifecycle, `@Conditional` auto-config, Spring MVC, validation, `ProblemDetail` error handling, Actuator health | Nothing | `make run-01` → `:8081` |
| 2 | [02-data-jpa](lessons/02-data-jpa/) | JPA/Hibernate, N+1 queries (measured, not just described), fetch strategies, Flyway migrations | Postgres | `make db && make run-02` → `:8082` |
| 3 | [03-security](lessons/03-security/) | Spring Security 6 filter chains, stateless JWT, method security (`@PreAuthorize`), password hashing | Postgres | `make db && make run-03` → `:8083` |
| 4 | [04-resilience](lessons/04-resilience/) | Redis caching (`@Cacheable`/`@CacheEvict`), `@Async`/`@Scheduled`, Resilience4j circuit breakers/retry/bulkhead | Redis | `make cache && make run-04` → `:8084` |
| 5 | [05-messaging](lessons/05-messaging/) | Kafka producer/consumer, delivery guarantees, error handling & DLT | Kafka | `make mq && make run-05` → `:8085` |

Every lesson's `README.md` opens with the **theory** behind the topic, then walks it through
hands-on with real requests against the running app.

---

## How to work through each lesson

Every lesson README follows the same pattern — use it the same way each time:

1. **Read the theory section first** — it's short by design, just enough to make sense of
   what you're about to see happen over HTTP.
2. **Run it**: the `make run-0N` command from the table above.
3. **Read the "what each file teaches" table** further down the README — it maps specific
   files to specific interview-style questions.
4. **Open the file, then run the matching `curl`** from the README, in the same terminal
   session. Watch the response while you have the code open — don't read code and commands
   as separate steps.
5. **Do the "Exercises" section** near the bottom of each README. These are designed to make
   you *see* a behavior (e.g. a prototype-scoped bean never changing inside a singleton,
   or an auto-configuration decision flipping when you change a property) rather than take
   it on faith.
6. **Run the tests**: `./mvnw -f lessons/0N-xxx/pom.xml verify`. Each lesson's tests are
   layered (unit → slice → integration) and the README explains which class is which layer
   and why.

---

## Makefile cheatsheet

Run `make help` for the full, current list. The ones you'll use constantly:

```bash
make colima-up      # start the Docker VM
make db             # Postgres + Adminer  (localhost:8090)   — lessons 2, 3
make cache          # Redis                                   — lesson 4
make mq             # Kafka + Kafka UI    (localhost:8091)   — lesson 5
make infra          # all of the above at once

make run-01 .. run-05   # run a given lesson (each on its own port, 8081-8085)

make test            # unit tests across all lessons
make verify           # full build + integration tests (needs Colima up)

make ps               # what's currently running
make down             # stop containers (data survives)
make clean             # stop containers AND wipe volumes (fresh DB next run)
```

---

## Repo layout

```
lessons/
  01-core-web/     no Docker — IoC container + Spring MVC
  02-data-jpa/     needs Postgres — JPA/Hibernate
  03-security/     needs Postgres — Spring Security 6 + JWT
  04-resilience/   needs Redis — caching + Resilience4j
  05-messaging/    needs Kafka — producer/consumer
docker-compose.yml   backing services, grouped by profile (db / cache / mq / all)
Makefile             convenience wrapper around Colima + Compose + Maven
pom.xml               multi-module parent (Spring Boot 3.5.3, Java 21)
```

Each lesson is an independent Spring Boot app with its own `pom.xml`, bound to its own port,
so you can run several side by side.
