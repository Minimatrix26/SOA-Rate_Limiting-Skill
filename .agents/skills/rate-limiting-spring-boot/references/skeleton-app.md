# Baseline skeleton app

Contents: purpose · pinned versions · pom.xml · layout · endpoints · baseline tests · build and run · acceptance criteria

This is the application the skill is designed to be applied to. It has **no rate limiting**: the skill's Implement mode adds that. Build the skeleton first, commit it, then apply the skill.

## Pinned versions and why

| Item | Version | Reason |
|---|---|---|
| Java | 21 | Required by the project |
| Build tool | Maven (use the Maven Wrapper, `mvnw`) | Required by the project; the wrapper pins the Maven version for reproducible builds |
| Spring Boot | **4.1.2** | Latest GA release when this skill was written (4.1.0 on 2026-06-10, 4.1.2 on 2026-10-23). Boot 4.0 free support ends 2026-12-31; 4.2.0 was at RC1 in late October 2026. Java 21 is supported |
| Bucket4j | `bucket4j_jdk17-core` 8.20.0 | Java 17+ artifact; added by the skill, not by the skeleton |
| Caffeine | managed by the Boot BOM | Added by the skill |

Re-check before building: if a newer 4.1.x patch exists, use it. Do not move to 4.2 or later without re-checking the starter and test-package names in SKILL.md.

## pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.2</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>ratelimit-demo</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>ratelimit-demo</name>
    <description>Skeleton REST API used to demonstrate rate limiting</description>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

## Layout

```
ratelimit-demo/
├── pom.xml
├── mvnw, mvnw.cmd, .mvn/            (Maven Wrapper; generate with `mvn wrapper:wrapper` if needed)
├── .agents/skills/rate-limiting-spring-boot/    (this skill)
└── src/
    ├── main/java/com/example/ratelimitdemo/
    │   ├── RateLimitDemoApplication.java
    │   └── web/
    │       ├── DemoController.java
    │       ├── OrderRequest.java        (record)
    │       ├── OrderResponse.java       (record)
    │       └── LoginRequest.java        (record)
    ├── main/resources/application.yml
    └── test/java/com/example/ratelimitdemo/
        ├── RateLimitDemoApplicationTests.java
        └── web/DemoControllerTest.java
```

## Endpoints

| Method and path | Behavior |
|---|---|
| `GET /api/public/ping` | `200` with `{"status":"ok"}` |
| `POST /api/orders` | Body `{"item": "<non-blank>", "quantity": <int >= 1>}`; `201` with `{"id": "<uuid>", "item": ..., "quantity": ...}`; `400` (ProblemDetail) on invalid input |
| `POST /api/auth/login` | Body `{"email": ..., "password": ...}`; `200` with `{"token":"demo-token"}` only for the demo credentials `user@test.com` / `Passw0rd!`, otherwise `401` with a generic "Invalid credentials". **Demo only: no real authentication, no real token.** It exists so brute-force limiting has something to protect |

All three are plain, unannotated handlers in `DemoController`. In-memory data only; no database.

Controller shape (validation imports are `jakarta.validation.*`):

```java
@RestController
@RequestMapping("/api")
public class DemoController {

    private static final String DEMO_EMAIL = "user@test.com";
    private static final String DEMO_PASSWORD = "Passw0rd!";

    @GetMapping("/public/ping")
    public Map<String, String> ping() {
        return Map.of("status", "ok");
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse createOrder(@Valid @RequestBody OrderRequest request) {
        return new OrderResponse(UUID.randomUUID(), request.item(), request.quantity());
    }

    @PostMapping("/auth/login")
    public Map<String, String> login(@Valid @RequestBody LoginRequest request) {
        if (!DEMO_EMAIL.equals(request.email()) || !DEMO_PASSWORD.equals(request.password())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        return Map.of("token", "demo-token");
    }
}

public record OrderRequest(@NotBlank String item, @Min(1) int quantity) {}
public record OrderResponse(UUID id, String item, int quantity) {}
public record LoginRequest(@NotBlank String email, @NotBlank String password) {}
```

## application.yml (baseline)

```yaml
spring:
  application:
    name: ratelimit-demo
server:
  port: 8080
```

Do not add `server.forward-headers-strategy` or any proxy setting to the baseline; the skill adds those only when a proxy is present.

## Baseline tests

`RateLimitDemoApplicationTests`: the context loads (`@SpringBootTest`).

`DemoControllerTest`: uses `@SpringBootTest` + `@AutoConfigureMockMvc` (import `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`) and covers: ping returns 200; a valid order returns 201 with an id; an order with `quantity` 0 returns 400; login with the demo credentials returns 200; login with a wrong password returns 401.

## Build and run

```bash
./mvnw -q verify               # compiles and runs the tests
./mvnw spring-boot:run         # starts on http://localhost:8080
curl localhost:8080/api/public/ping
```

## Acceptance criteria for the skeleton

- [ ] `./mvnw -q verify` succeeds with Java 21 (`java -version` reports 21)
- [ ] The application starts and all three endpoints behave as in the table
- [ ] `pom.xml` uses `spring-boot-starter-webmvc` (not `spring-boot-starter-web`) and Spring Boot 4.1.2
- [ ] No rate-limiting code, no Bucket4j, no Caffeine in the skeleton
- [ ] A `.gitignore` excludes `target/`, IDE folders, and `.env` files
- [ ] The repository has a README with the build and run commands above, and is public (the tutorial needs a public repository)
