# AGENTS.md

## Project
Demo REST API: Java 21, Maven, Spring Boot 4.1.x, Spring MVC.
Base package: `com.example.ratelimitdemo`.

## Commands
- Build and test: `./mvnw -q verify` (use the wrapper once it exists)
- Run: `./mvnw spring-boot:run` (port 8080)

## Rules
- Do not modify anything under `.agents/`.
- Do not commit or push; leave git to me.
- Only report a build or test as passing if you ran it, and quote the real output.
- Keep changes limited to what was asked.

## Skills
- For anything about rate limiting, throttling, or request quotas, use the
  `rate-limiting-spring-boot` skill. Requests to explain or propose ideas must
  not change any files.