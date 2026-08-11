# Phase 2: Global Error Handling

In a microservices architecture, consistency is key. This phase ensures that every service in the MBD ecosystem speaks the same "error language" when something goes wrong, improving both developer experience and user feedback.

## Goal
To implement a standardized error reporting mechanism across all backend services using Spring Boot's `@ControllerAdvice`.

## Implementation Details

### 1. Standardize Error Response
I will create a shared `ErrorResponse` DTO in the `shared` module:
```kotlin
data class ErrorResponse(
    val status: Int,
    val message: String,
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val errors: Map<String, String>? = null // For field-level validation errors
)
```

### 2. Global Exception Handler
I will implement a `GlobalExceptionHandler` class in each service. This class will use `@ControllerAdvice` to catch and transform exceptions:
- **`MethodArgumentNotValidException`**: Catches `@Valid` failures and returns a `400 Bad Request` with field-specific details.
- **`EntityNotFoundException`**: Returns a `404 Not Found`.
- **`AccessDeniedException`**: Returns a `403 Forbidden` (especially relevant for `admin-service`).
- **Generic `Exception`**: Returns a `500 Internal Server Error` with a sanitized message to avoid leaking stack traces.

### 3. Service Integration
Each service will be updated to throw specific exceptions instead of returning error codes manually. This separates business logic from HTTP response logic.

## Benefits
- **Frontend Consistency**: The React frontends can rely on a fixed JSON structure for all errors, making it easier to show helpful feedback to the user.
- **Auditing and Debugging**: Standardized errors with timestamps and clear messages make log analysis much faster.
- **Security**: By catching generic exceptions and returning a sanitized response, we prevent the exposure of internal system details (like database schema names or library versions) via stack traces.
