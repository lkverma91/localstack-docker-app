# Authentication & Authorization Documentation

This document explains the complete authentication and authorization system implemented in this Spring Boot application, including every file's purpose, the JWT + refresh token flow, and security design decisions.

**Focused guides (in the `docs/` folder):**

- [docs/authentication-and-authorization.md](docs/authentication-and-authorization.md) — authentication vs authorization, JWT flow, roles, and file map.
- [docs/forgot-password-implementation.md](docs/forgot-password-implementation.md) — step-by-step forgot/reset password implementation (OTP, SES, APIs).

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture Flow](#architecture-flow)
3. [File-by-File Breakdown](#file-by-file-breakdown)
4. [JWT Access Token](#jwt-access-token)
5. [Refresh Token](#refresh-token)
6. [Forgot Password / Password Reset](#forgot-password--password-reset)
7. [Token Lifecycle](#token-lifecycle)
8. [API Endpoints](#api-endpoints)
9. [Security Design Decisions](#security-design-decisions)

---

## Overview

This application uses **stateless JWT-based authentication** with **refresh token rotation**. The key concepts are:

| Concept | Description |
|---------|-------------|
| **Access Token** | Short-lived JWT (15 minutes) sent in the `Authorization: Bearer <token>` header. Used for accessing protected endpoints. |
| **Refresh Token** | Long-lived opaque UUID (24 hours) stored in the database. Used to get a new access token without re-entering credentials. |
| **Token Rotation** | Every time a refresh token is used, the old one is deleted and a new pair (access + refresh) is issued. This limits the window of a stolen refresh token. |
| **BCrypt Hashing** | Passwords are hashed with BCrypt (strength 12) before storage. Plain-text passwords never touch the database. |
| **Role-Based Access** | Each user has a role (`ROLE_USER` or `ROLE_ADMIN`). The `@PreAuthorize` annotation enforces role-based access on endpoints. |

---

## Architecture Flow

```
┌──────────┐     POST /api/auth/register      ┌────────────────┐
│          │ ──────────────────────────────────>│                │
│          │     201 Created + UserProfile      │                │
│          │ <──────────────────────────────────│                │
│          │                                    │                │
│          │     POST /api/auth/login           │                │       ┌──────────┐
│          │ ──────────────────────────────────>│  AuthController│──────>│  MySQL   │
│  Client  │     200 OK + {access, refresh}     │  + Services    │       │ (users,  │
│          │ <──────────────────────────────────│                │       │ refresh_ │
│          │                                    │                │       │ tokens)  │
│          │     GET /api/users/profile         │                │       └──────────┘
│          │     Authorization: Bearer <jwt>    │                │
│          │ ──────────────────────────────────>│ JwtAuthFilter  │
│          │     200 OK + UserProfile           │ (validates JWT)│
│          │ <──────────────────────────────────│                │
│          │                                    │                │
│          │     POST /api/auth/refresh         │                │
│          │     {refreshToken: "..."}          │                │
│          │ ──────────────────────────────────>│                │
│          │     200 OK + {new access, refresh} │                │
│          │ <──────────────────────────────────│                │
│          │                                    │                │
│          │     POST /api/auth/forgot-password │                │       ┌──────────┐
│          │     {email: "..."}                 │                │──────>│ AWS SES  │
│          │ ──────────────────────────────────>│ PasswordReset  │       │(LocalSt.)│
│          │     200 OK + OTP sent via email    │ Service        │       └──────────┘
│          │ <──────────────────────────────────│                │
│          │                                    │                │
│          │     POST /api/auth/reset-password  │                │
│          │     {email, token, newPassword}    │                │
│          │ ──────────────────────────────────>│                │
│          │     200 OK + password changed      │                │
│          │ <──────────────────────────────────│                │
└──────────┘                                    └────────────────┘
```

---

## File-by-File Breakdown

### Entity Layer

| File | Purpose |
|------|---------|
| `entity/User.java` | JPA entity mapped to `users` table. Stores `id`, `fullName`, `email` (unique), `password` (BCrypt hash), `role` (enum), `enabled` flag, and audit timestamps (`createdAt`, `updatedAt`). |
| `entity/Role.java` | Enum with values `ROLE_USER` and `ROLE_ADMIN`. The `ROLE_` prefix is a Spring Security convention so that `hasRole('ADMIN')` works correctly. |
| `entity/RefreshToken.java` | JPA entity mapped to `refresh_tokens` table. Stores `id`, `token` (UUID string, unique), `user` (ManyToOne FK to User), and `expiryDate` (Instant). |
| `entity/PasswordResetToken.java` | JPA entity mapped to `password_reset_tokens` table. Stores `id`, `token` (6-digit OTP, unique), `user` (ManyToOne FK to User), `expiryDate` (15 minutes), and `used` (boolean flag to prevent reuse). |

### Repository Layer

| File | Purpose |
|------|---------|
| `repository/UserRepository.java` | Spring Data JPA repository for `User`. Provides `findByEmail()` and `existsByEmail()` for lookup and duplicate checks. |
| `repository/RefreshTokenRepository.java` | Spring Data JPA repository for `RefreshToken`. Provides `findByToken()`, `deleteByUserId()` (clears all tokens for a user on logout/refresh), and `deleteAllExpiredTokens()` for cleanup. |
| `repository/PasswordResetTokenRepository.java` | Spring Data JPA repository for `PasswordResetToken`. Provides `findByTokenAndUsedFalse()` (finds an unused OTP), `deleteByUserId()` (clears old OTPs before generating a new one), and `deleteAllExpiredTokens()`. |

### Security Layer

| File | Purpose |
|------|---------|
| `security/JwtTokenProvider.java` | **The core JWT engine.** Responsible for: (1) generating access tokens signed with HMAC-SHA using jjwt library, (2) extracting the email (subject) from a token, and (3) validating tokens (signature, expiry, format). The signing key is loaded from `app.jwt.secret` (Base64-encoded) at startup via `@PostConstruct`. |
| `security/JwtAuthenticationFilter.java` | **Runs on every HTTP request** (extends `OncePerRequestFilter`). Extracts the Bearer token from the `Authorization` header, validates it via `JwtTokenProvider`, loads the `UserDetails` from the database, and sets the `SecurityContextHolder` so downstream code knows who the caller is. If no token or an invalid token is present, the filter simply passes through (unauthenticated). |
| `security/JwtAuthEntryPoint.java` | **Handles 401 errors.** Implements Spring Security's `AuthenticationEntryPoint`. When an unauthenticated request hits a protected endpoint, this class returns a structured JSON response with status 401, error message, request path, and timestamp. Without this, Spring would return an HTML error page. |

### Configuration Layer

| File | Purpose |
|------|---------|
| `config/SecurityConfig.java` | **Central security configuration.** Defines the `SecurityFilterChain` bean that: (1) disables CSRF (stateless APIs don't need it), (2) sets session policy to `STATELESS` (no HTTP sessions), (3) registers `JwtAuthenticationFilter` before Spring's `UsernamePasswordAuthenticationFilter`, (4) whitelists public URLs (`/api/auth/**`, Swagger, actuator health), and (5) requires authentication for everything else. Also provides `BCryptPasswordEncoder` and `AuthenticationManager` beans. |
| `config/AwsConfig.java` | Configures AWS SDK v2 clients (`S3Client`, `SesClient`, `SsmClient`). When `app.aws.endpoint` is set (LocalStack mode), clients point to the LocalStack endpoint with dummy credentials. In production, this would use IAM roles. |
| `config/OpenApiConfig.java` | Configures Swagger UI. Defines the `bearerAuth` security scheme so the Swagger "Authorize" button accepts JWT tokens. Sets API title, version, and description. |

### Service Layer

| File | Purpose |
|------|---------|
| `service/UserService.java` | Implements `UserDetailsService` (required by Spring Security). The `loadUserByUsername()` method loads a user by email and converts it to Spring Security's `UserDetails` with the user's role as a `GrantedAuthority`. Also handles user registration: checks for duplicate emails, encodes the password with BCrypt, saves the user, and returns a profile DTO. |
| `service/RefreshTokenService.java` | Manages refresh token lifecycle: (1) `createRefreshToken()` generates a UUID, ties it to a user, sets the expiry, and saves it (deleting any previous tokens for that user), (2) `verifyExpiration()` checks if a token is expired and deletes it if so, (3) `deleteByUserId()` removes all tokens for a user (used during logout). |
| `service/AuthService.java` | **Orchestrates the auth workflows.** `register()` delegates to UserService. `login()` authenticates credentials via `AuthenticationManager`, generates an access token via `JwtTokenProvider`, creates a refresh token via `RefreshTokenService`, and returns both. `refreshToken()` validates the refresh token, generates a new pair (token rotation). `logout()` deletes all refresh tokens for the user. |
| `service/PasswordResetService.java` | **Handles forgot/reset password.** `initiatePasswordReset()` generates a 6-digit OTP, stores it in the database with a 15-minute expiry, and sends it to the user's email via AWS SES (LocalStack in dev). `resetPassword()` validates the OTP (checks existence, ownership, expiry, and used flag), marks it as used, and updates the user's password with a BCrypt hash. If SES email delivery fails, the OTP is logged as a fallback for development. |

### Controller Layer

| File | Purpose |
|------|---------|
| `controller/AuthController.java` | REST controller at `/api/auth`. Exposes: `POST /register` (public), `POST /login` (public), `POST /refresh` (public), `POST /logout` (authenticated), `POST /forgot-password` (public), `POST /reset-password` (public). All request bodies are validated with Bean Validation annotations (`@Valid`). |
| `controller/UserController.java` | REST controller at `/api/users`. Exposes: `GET /profile` (authenticated, any role), `GET /admin` (authenticated, `ROLE_ADMIN` only via `@PreAuthorize`). The `Authentication` object is injected by Spring Security after the JWT filter validates the token. |

### DTO Layer

| File | Purpose |
|------|---------|
| `dto/RegisterRequest.java` | Request body for registration. Validates `fullName` (2-100 chars), `email` (valid format), `password` (8-100 chars). |
| `dto/LoginRequest.java` | Request body for login. Validates `email` and `password` as non-blank. |
| `dto/RefreshTokenRequest.java` | Request body for token refresh. Contains the `refreshToken` string. |
| `dto/TokenResponse.java` | Response body returned after login/refresh. Contains `access_token`, `refresh_token`, `token_type` ("Bearer"), and `expires_in` (seconds). |
| `dto/UserProfileResponse.java` | Response body for user profile. Contains `id`, `fullName`, `email`, `role`, `createdAt`. Never exposes the password. |
| `dto/ForgotPasswordRequest.java` | Request body for forgot password. Contains `email` (validated as non-blank and valid format). |
| `dto/ResetPasswordRequest.java` | Request body for password reset. Contains `email`, `token` (the 6-digit OTP), and `newPassword` (8-100 chars). |
| `dto/ChangeRoleRequest.java` | Request body for changing a user's role. Contains `role` (must be `ROLE_USER` or `ROLE_ADMIN`). |
| `dto/ApiResponse.java` | Generic wrapper for all API responses. Contains `status`, `message`, `data` (generic), and `timestamp`. Provides static factory methods `success()`, `created()`, and `error()`. |

### Exception Layer

| File | Purpose |
|------|---------|
| `exception/UserAlreadyExistsException.java` | Thrown when registering with an email that already exists. |
| `exception/TokenRefreshException.java` | Thrown when a refresh token is not found or has expired. |
| `exception/InvalidResetTokenException.java` | Thrown when a password reset OTP is invalid, expired, already used, or doesn't belong to the given user. |
| `exception/GlobalExceptionHandler.java` | `@RestControllerAdvice` that catches all exceptions and returns structured JSON responses. Handles: `UserAlreadyExistsException` (409), `TokenRefreshException` (403), `InvalidResetTokenException` (400), `BadCredentialsException` (401), `MethodArgumentNotValidException` (400 with field-level errors), and a generic fallback (500). |

---

## JWT Access Token

### Structure

A JWT access token has three Base64-encoded parts separated by dots:

```
HEADER.PAYLOAD.SIGNATURE
```

**Header:**
```json
{
  "alg": "HS512",
  "typ": "JWT"
}
```

**Payload:**
```json
{
  "sub": "user@example.com",
  "iat": 1711929600,
  "exp": 1711930500
}
```

- `sub` (subject): The user's email, used to identify them.
- `iat` (issued at): When the token was created.
- `exp` (expiration): When the token expires (15 minutes after `iat`).

**Signature:**
```
HMACSHA512(base64UrlEncode(header) + "." + base64UrlEncode(payload), secret)
```

### How It Works

1. **Generation**: After successful login, `JwtTokenProvider.generateAccessToken()` creates a JWT signed with the secret key.
2. **Transmission**: The client stores the token and sends it in the `Authorization` header: `Bearer eyJhbGciOiJIUzUxMiJ9...`
3. **Validation**: On every request, `JwtAuthenticationFilter` extracts the token, calls `JwtTokenProvider.validateToken()` to verify signature and expiry, then loads the user and sets the security context.
4. **Expiry**: After 15 minutes, the token is rejected. The client must use the refresh token to get a new one.

---

## Refresh Token

### Structure

The refresh token is **not a JWT**. It is an opaque UUID string (e.g., `550e8400-e29b-41d4-a716-446655440000`) stored in the `refresh_tokens` database table alongside the user ID and expiry date.

### Why Not a JWT?

- **Revocability**: Database-stored tokens can be instantly revoked (deleted). JWTs cannot be revoked before expiry without a blocklist.
- **Simplicity**: No need to parse/validate JWT claims for refresh tokens.
- **Security**: The refresh token value reveals nothing about the user even if intercepted.

### Token Rotation

Every time a refresh token is used:
1. The old refresh token is **deleted** from the database.
2. A **new** refresh token is created and returned alongside the new access token.

This means each refresh token is single-use. If an attacker steals and uses a refresh token, the legitimate user's next refresh will fail, alerting them to the compromise.

---

## Forgot Password / Password Reset

### How It Works

The forgot password flow uses a **6-digit OTP (One-Time Password)** sent via **AWS SES** email (routed through LocalStack in development).

```
Step 1: User forgets password
         │
         ▼
Step 2: POST /api/auth/forgot-password  { email: "user@example.com" }
         │
         ▼
Step 3: Server generates 6-digit OTP (SecureRandom)
        Server stores OTP in `password_reset_tokens` table (15-min expiry)
        Server deletes any previous OTPs for this user
        Server sends OTP to user's email via SES
         │
         ▼
Step 4: User receives email with OTP (e.g., "482917")
         │
         ▼
Step 5: POST /api/auth/reset-password  { email, token: "482917", newPassword }
         │
         ▼
Step 6: Server validates:
        ✓ OTP exists and is unused
        ✓ OTP belongs to the given email
        ✓ OTP has not expired (< 15 minutes)
         │
         ▼
Step 7: Server marks OTP as used
        Server updates user's password (BCrypt encoded)
         │
         ▼
Step 8: User can now login with the new password
```

### Security Design

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| **OTP format** | 6-digit numeric | Simple for users to copy from email; generated with `SecureRandom` (cryptographically secure). |
| **Expiry** | 15 minutes | Short enough to limit brute-force window; long enough for users to check email. |
| **Single-use** | `used` flag in database | Prevents replay attacks. Once used, the OTP cannot be reused even within the expiry window. |
| **One active OTP** | Old OTPs deleted on new request | If a user requests reset multiple times, only the latest OTP works. |
| **Email via SES** | AWS SES (LocalStack in dev) | Production-ready email delivery. In LocalStack, emails are simulated. |
| **Consistent response** | Same 200 message for valid/invalid emails | `"If the email exists..."` prevents email enumeration attacks. |
| **Fallback logging** | OTP logged if SES fails | During development, if SES email delivery fails, the OTP is logged to the console so testing can continue. |

### Files Involved

| File | Role in Password Reset |
|------|----------------------|
| `entity/PasswordResetToken.java` | Stores the OTP, user reference, expiry, and used flag |
| `repository/PasswordResetTokenRepository.java` | Queries for unused OTPs, deletes old/expired tokens |
| `service/PasswordResetService.java` | Orchestrates: generate OTP, store it, send email, validate & reset |
| `controller/AuthController.java` | Exposes `POST /forgot-password` and `POST /reset-password` |
| `dto/ForgotPasswordRequest.java` | Validates the email field |
| `dto/ResetPasswordRequest.java` | Validates email, token, and newPassword fields |
| `exception/InvalidResetTokenException.java` | Thrown for invalid/expired/used OTPs |
| `config/AwsConfig.java` | Provides the `SesClient` bean used to send emails |

---

## Token Lifecycle

```
User registers
       │
       ▼
User logs in ──────────────> Access Token (15 min) + Refresh Token (24 hr)
       │                              │                        │
       │                              ▼                        │
       │                     Client uses access token          │
       │                     for API requests                  │
       │                              │                        │
       │                              ▼                        │
       │                     Access token expires               │
       │                              │                        │
       │                              ▼                        │
       │                     Client sends refresh token ◄──────┘
       │                              │
       │                              ▼
       │                     Server validates refresh token
       │                     Server deletes old refresh token
       │                     Server issues new access + refresh tokens
       │                              │
       │                              ▼
       │                     Client continues with new tokens
       │
User logs out ─────────────> All refresh tokens for user deleted
                              Access token still valid until expiry
                              (stateless - can't revoke immediately)
```

---

## API Endpoints

### POST /api/auth/register

Register a new user account.

**Request:**
```json
{
  "fullName": "John Doe",
  "email": "john@example.com",
  "password": "securePassword123"
}
```

**Response (201 Created):**
```json
{
  "status": 201,
  "message": "User registered successfully",
  "data": {
    "id": 1,
    "fullName": "John Doe",
    "email": "john@example.com",
    "role": "ROLE_USER",
    "createdAt": "2026-04-01T10:00:00Z"
  },
  "timestamp": "2026-04-01T10:00:00Z"
}
```

**Error (409 Conflict):**
```json
{
  "status": 409,
  "message": "User already exists with email: john@example.com",
  "timestamp": "2026-04-01T10:00:00Z"
}
```

### POST /api/auth/login

Authenticate and receive tokens.

**Request:**
```json
{
  "email": "john@example.com",
  "password": "securePassword123"
}
```

**Response (200 OK):**
```json
{
  "status": 200,
  "message": "Login successful",
  "data": {
    "access_token": "eyJhbGciOiJIUzUxMiJ9...",
    "refresh_token": "550e8400-e29b-41d4-a716-446655440000",
    "token_type": "Bearer",
    "expires_in": 900
  },
  "timestamp": "2026-04-01T10:00:00Z"
}
```

**Error (401 Unauthorized):**
```json
{
  "status": 401,
  "message": "Invalid email or password",
  "timestamp": "2026-04-01T10:00:00Z"
}
```

### POST /api/auth/refresh

Get a new access token using a refresh token.

**Request:**
```json
{
  "refreshToken": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response (200 OK):**
```json
{
  "status": 200,
  "message": "Token refreshed successfully",
  "data": {
    "access_token": "eyJhbGciOiJIUzUxMiJ9...(new)",
    "refresh_token": "661f9511-f30c-52e5-b827-557766551111",
    "token_type": "Bearer",
    "expires_in": 900
  },
  "timestamp": "2026-04-01T10:00:05Z"
}
```

### POST /api/auth/forgot-password

Request a password reset OTP. The OTP is sent to the user's email via SES.

**Request:**
```json
{
  "email": "john@example.com"
}
```

**Response (200 OK):**
```json
{
  "status": 200,
  "message": "If the email exists, a password reset OTP has been sent",
  "timestamp": "2026-04-01T10:00:00Z"
}
```

> Note: The response is always 200 regardless of whether the email exists. This prevents email enumeration. Check the app logs or LocalStack SES output for the OTP during development.

### POST /api/auth/reset-password

Reset the password using the OTP received via email.

**Request:**
```json
{
  "email": "john@example.com",
  "token": "482917",
  "newPassword": "myNewSecurePassword"
}
```

**Response (200 OK):**
```json
{
  "status": 200,
  "message": "Password has been reset successfully. Please login with your new password.",
  "timestamp": "2026-04-01T10:02:00Z"
}
```

**Error (400 Bad Request) -- invalid/expired/used OTP:**
```json
{
  "status": 400,
  "message": "Reset token has expired. Please request a new one.",
  "timestamp": "2026-04-01T10:20:00Z"
}
```

### POST /api/auth/logout

Invalidate all refresh tokens for the current user. Requires a valid access token.

**Headers:**
```
Authorization: Bearer eyJhbGciOiJIUzUxMiJ9...
```

**Response (200 OK):**
```json
{
  "status": 200,
  "message": "Logged out successfully",
  "timestamp": "2026-04-01T10:05:00Z"
}
```

### GET /api/users/profile

Get the authenticated user's profile. Requires a valid access token.

**Headers:**
```
Authorization: Bearer eyJhbGciOiJIUzUxMiJ9...
```

**Response (200 OK):**
```json
{
  "status": 200,
  "message": "Profile retrieved successfully",
  "data": {
    "id": 1,
    "fullName": "John Doe",
    "email": "john@example.com",
    "role": "ROLE_USER",
    "createdAt": "2026-04-01T10:00:00Z"
  },
  "timestamp": "2026-04-01T10:01:00Z"
}
```

### GET /api/users/admin

Admin-only endpoint. Requires `ROLE_ADMIN`.

**Response (200 OK):**
```json
{
  "status": 200,
  "message": "Admin access granted",
  "data": "Welcome, Admin!",
  "timestamp": "2026-04-01T10:01:00Z"
}
```

**Response (403 Forbidden) if user has ROLE_USER:**
Spring Security returns a 403 before reaching the controller.

---

## Security Design Decisions

### 1. Why Stateless (No Sessions)?

- **Scalability**: No server-side session storage. Any instance can validate a JWT independently.
- **Microservice-friendly**: Tokens can be verified by any service that has the signing key.
- **Trade-off**: Cannot instantly revoke access tokens. Mitigated by short expiry (15 min).

### 2. Why BCrypt with Strength 12?

- BCrypt is an adaptive hashing algorithm that intentionally slows down brute-force attacks.
- Strength 12 means 2^12 = 4096 iterations, taking ~250ms per hash. This is slow enough to deter attackers but fast enough for login.

### 3. Why CSRF is Disabled?

- CSRF attacks exploit browser cookies. Since this API uses `Authorization` headers (not cookies), CSRF protection is unnecessary and would break API clients.

### 4. Why the Filter Runs Before UsernamePasswordAuthenticationFilter?

- Spring Security's filter chain processes filters in order. By placing `JwtAuthenticationFilter` before `UsernamePasswordAuthenticationFilter`, JWT-based auth is attempted first. If a valid JWT is found, the security context is set and no further authentication is needed.

### 5. Why Store Refresh Tokens in the Database?

- **Revocability**: Tokens can be deleted on logout or compromised account detection.
- **Single-device sessions**: Deleting old tokens when creating new ones ensures only one active session per user.
- **Audit trail**: The database provides a record of active sessions.

### 6. Why Token Rotation?

- If an attacker steals a refresh token and uses it, a new token is issued to the attacker. When the legitimate user tries to refresh, their old token is gone, and the refresh fails. This signals a potential compromise. Without rotation, both the attacker and user could use the same refresh token indefinitely.
