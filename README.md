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

The demo login uses hard-coded credentials and is not real authentication. Its token is not real. This is an in-memory baseline demo with no rate limiting.
