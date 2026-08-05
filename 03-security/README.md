# Module 03 — Security

Spring Security 6, stateless JWT, and method security. Needs Postgres on Colima.

```bash
make db
make run-03      # http://localhost:8083
```

Seeded users, all with password **`password123`**:

| user | roles | note |
|---|---|---|
| `alice` | USER | owns documents 2 and 3 |
| `bob` | USER | owns document 4 |
| `admin` | USER, ADMIN | |
| `locked` | USER | account locked — login always fails |

> This module owns the **`security` schema** in `appdb`; module 02 owns `public`. See
> [application.yml](src/main/resources/application.yml) for why that separation exists.

---

## Drive the whole flow

```bash
B=localhost:8083
tok() { curl -s -X POST $B/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"password123\"}" | jq -r .accessToken; }

A=$(tok alice); BOB=$(tok bob); ADM=$(tok admin)

# --- the three tiers ---
curl -s -o /dev/null -w '%{http_code}\n' $B/api/public/ping                       # 200, no token
curl -s $B/api/me | jq                                                            # 401
curl -s $B/api/me -H "Authorization: Bearer $A" | jq                              # 200
curl -s $B/api/admin/stats -H "Authorization: Bearer $A" | jq '.status, .title'   # 403
curl -s -o /dev/null -w '%{http_code}\n' $B/api/admin/stats -H "Authorization: Bearer $ADM"  # 200

# --- read the token: it is SIGNED, not ENCRYPTED ---
echo $A | cut -d. -f2 | base64 -d 2>/dev/null | jq
# { "iss": "...", "sub": "alice", "exp": ..., "iat": ..., "roles": ["USER"] }
```

`jwt.io` will decode it just as easily. **Never put anything secret in a JWT payload.**

### Method security

```bash
curl -s -o /dev/null -w '%{http_code}\n' $B/api/documents/owner/alice -H "Authorization: Bearer $A"  # 200
curl -s -o /dev/null -w '%{http_code}\n' $B/api/documents/owner/bob   -H "Authorization: Bearer $A"  # 403
curl -s -o /dev/null -w '%{http_code}\n' $B/api/documents/owner/bob   -H "Authorization: Bearer $ADM"# 200

curl -s -o /dev/null -w '%{http_code}\n' $B/api/documents/2 -H "Authorization: Bearer $BOB"          # 403 @PostAuthorize
curl -s -X PUT $B/api/documents/2 -H "Authorization: Bearer $BOB" \
  -H 'Content-Type: application/json' -d '{"content":"hacked"}' | jq '.status'                       # 403 @documentGuard
```

### The deliberate vulnerability

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE $B/api/documents/4 -H "Authorization: Bearer $BOB"
# 403 — @PreAuthorize("hasRole('ADMIN')") did its job

curl -s -X DELETE $B/api/documents/4/unsafe-delete -H "Authorization: Bearer $BOB" | jq
# 200 — DELETED. The service called this.delete(id) internally.
```

`@PreAuthorize` is AOP, exactly like `@Transactional`. A `this.` call never reaches the
proxy, so **the authorisation check simply does not run**. Here that is not a missing
transaction — it is a privilege-escalation hole. Being able to tell that story is worth a
lot in an interview.

---

## Topic → file map

| Topic | File |
|---|---|
| filter chain order, multiple chains, `@Order` | [SecurityConfig.java](src/main/java/com/learning/security/config/SecurityConfig.java) |
| CSRF: why disabling it is (conditionally) correct | [SecurityConfig.java](src/main/java/com/learning/security/config/SecurityConfig.java) |
| stateless sessions, deny-by-default, rule order | [SecurityConfig.java](src/main/java/com/learning/security/config/SecurityConfig.java) |
| CORS, preflight, the `allowCredentials` + `*` conflict | [SecurityConfig.java](src/main/java/com/learning/security/config/SecurityConfig.java) |
| roles vs authorities, the `ROLE_` prefix | [JpaUserDetailsService.java](src/main/java/com/learning/security/service/JpaUserDetailsService.java) |
| password hashing, `DelegatingPasswordEncoder` | [SecurityConfig.java](src/main/java/com/learning/security/config/SecurityConfig.java) |
| JWT anatomy, revocation, `alg:none`, storage | [TokenService.java](src/main/java/com/learning/security/service/TokenService.java) |
| 401 vs 403, why `@ControllerAdvice` misses them | [SecurityProblemHandlers.java](src/main/java/com/learning/security/web/SecurityProblemHandlers.java) |
| `@PreAuthorize` / `@PostAuthorize` / `@PreFilter` | [DocumentService.java](src/main/java/com/learning/security/service/DocumentService.java) |
| guard beans in SpEL, failing closed | [DocumentGuard.java](src/main/java/com/learning/security/service/DocumentGuard.java) |
| user enumeration, uniform error responses | [AuthController.java](src/main/java/com/learning/security/web/AuthController.java) |
| getting the current user 4 ways, ThreadLocal caveat | [DocumentController.java](src/main/java/com/learning/security/web/DocumentController.java) |

---

## Two bugs this module was built around

**1. `Failed to select a JWK signing key`**

`JwtEncoderParameters.from(claims)` with no header makes `NimbusJwtEncoder` default to
**RS256**. With a symmetric `ImmutableSecret` there is no RSA key, and the error message
never mentions the algorithm. Fix — state it explicitly:

```java
JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
jwtEncoder.encode(JwtEncoderParameters.from(header, claims));
```

**2. A 401 that should have been a 500**

Any unhandled controller exception triggers a servlet **ERROR dispatch to `/error`**, which
goes through the security chain again. If `/error` is not permitted, the real error is
replaced by a 401 from the authentication entry point — on a request that was properly
authenticated.

The tell is in the response body: `"instance": "/error"` instead of the endpoint you
called. Fix: `.requestMatchers("/error").permitAll()`.

---

## See the filter chain for yourself

Uncomment in [application.yml](src/main/resources/application.yml):

```yaml
logging:
  level:
    "[org.springframework.security]": DEBUG
```

Restart and Spring prints every filter in every chain, in order. Reading that list once is
the best possible preparation for "walk me through the Spring Security filter chain".

---

## Tests — 22, all green

```bash
./mvnw -f 03-security/pom.xml verify
```

| Class | Approach | What it can prove |
|---|---|---|
| [SecurityRulesIT](src/test/java/com/learning/security/SecurityRulesIT.java) | `@WithMockUser` + MockMvc | authorisation rules, fast, no tokens minted |
| [JwtFlowIT](src/test/java/com/learning/security/JwtFlowIT.java) | real HTTP + real tokens | the filters `@WithMockUser` skips: decoding, signature, `exp`, claim→authority mapping |

`JwtFlowIT` also forges an **expired but correctly signed** token and a **tampered
signature**, proving the decoder validates more than just "is this parseable".

---

Previous: [Module 02 — Data & JPA](../02-data-jpa/) · Next: [Module 04 — Caching & Resilience](../04-resilience/)
