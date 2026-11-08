# SOA-Rate_Limiting-Skill

## Build and run

```bash
./mvnw -q verify
./mvnw spring-boot:run
```

The app runs on `http://localhost:8080`.

## API documentation

Swagger UI: <http://localhost:8080/swagger-ui.html>

OpenAPI JSON: <http://localhost:8080/v3/api-docs>

## Endpoints

| Method and path | Behavior |
|---|---|
| `GET /api/public/ping` | Returns `200` with `{"status":"ok"}` |
| `POST /api/orders` | Accepts a non-blank item and quantity of at least 1; returns `201` with a UUID order ID, or `400` for invalid input |
| `POST /api/auth/login` | Returns `200` with `{"token":"demo-token"}` for the demo credentials; otherwise returns `401` |

The demo login uses hard-coded credentials and is not real authentication. Its token is not real. Use `user@test.com` with password `Passw0rd!` for the successful demo login; any other password (or email) returns `401 Unauthorized`.

## Rate limiting

`POST /api/auth/login` uses the `login` policy, keyed by the client IP address: capacity 5 requests (maximum burst), refilling 5 tokens per 1 minute. The sixth request from the same IP before enough tokens refill returns `429 Too Many Requests`, a `Retry-After` header, `RateLimit-*` headers, and an `application/problem+json` body. Send seven quick requests with a wrong password to see five `401` responses followed by two `429` responses. The in-memory buckets are for this single-instance demo and reset when the app restarts.
