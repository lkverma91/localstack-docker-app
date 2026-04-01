# Forgot Password: Step-by-Step Implementation

This guide walks through **how we implemented** forgot-password and reset-password in this project: what we store, how the OTP is created and emailed, and how the password is updated safely.

---

## What the feature does

1. **Forgot password** — User sends their email. The server generates a **6-digit OTP**, saves it with an expiry, and sends it by **email (AWS SES)**. In Docker, SES is provided by **LocalStack**.
2. **Reset password** — User sends **email**, **OTP**, and **new password**. The server validates the OTP, marks it used, and updates the user’s password (BCrypt).

---

## Step 1: Database model

**File:** `src/main/java/com/app/entity/PasswordResetToken.java`

We added a JPA entity mapped to table `password_reset_tokens` with:

| Field | Purpose |
|-------|---------|
| `token` | 6-digit OTP string (unique while active) |
| `user` | Many-to-one link to `User` |
| `expiryDate` | When the OTP stops being valid (15 minutes after creation) |
| `used` | `false` until a successful reset; prevents replay |

**Why:** OTPs must be revocable, expirable, and single-use. Storing them in MySQL is simpler than putting secrets inside a JWT for this flow.

---

## Step 2: Repository

**File:** `src/main/java/com/app/repository/PasswordResetTokenRepository.java`

We use Spring Data JPA with:

- `findByTokenAndUsedFalse(String token)` — find a valid, unused OTP for reset.
- `deleteByUserId(Long userId)` — before issuing a **new** OTP, remove old rows for that user so only one active OTP exists.

---

## Step 3: Request DTOs and validation

| File | Role |
|------|------|
| `dto/ForgotPasswordRequest.java` | `email` — `@NotBlank`, `@Email` |
| `dto/ResetPasswordRequest.java` | `email`, `token` (OTP), `newPassword` — length 8–100 |

Controllers use `@Valid` so invalid input returns **400** with field errors (handled globally).

---

## Step 4: Email delivery (AWS SES)

**File:** `src/main/java/com/app/config/AwsConfig.java`

- Defines a `SesClient` bean.
- When `app.aws.endpoint` is set (e.g. `http://localstack:4566` in Docker), the client talks to **LocalStack** with test credentials.

**LocalStack setup:** `init-aws.sh` runs `awslocal ses verify-email-identity --email-address noreply@authapp.local` so SES can send from that address in the emulator.

**File:** `src/main/java/com/app/service/PasswordResetService.java`

- Sender address: `noreply@authapp.local` (must match verified identity in LocalStack).
- `sendResetEmail(...)` builds `SendEmailRequest` and calls `sesClient.sendEmail(...)`.
- If sending fails, the service **logs the OTP** so you can still test in development (see application logs).

---

## Step 5: Core business logic

**File:** `src/main/java/com/app/service/PasswordResetService.java`

### `initiatePasswordReset(email)`

1. Load `User` by email (`UserRepository.findByEmail`). If not found, a `UsernameNotFoundException` is thrown (ensure your global handler or controller matches your product rule: same 200 for unknown email vs explicit error).
2. `deleteByUserId(user.getId())` — remove previous OTP rows for this user.
3. Generate OTP with `SecureRandom` (six digits, 100000–999999).
4. Persist `PasswordResetToken` with `expiryDate = now + 15 minutes`, `used = false`.
5. Call `sendResetEmail(...)`.

### `resetPassword(email, token, newPassword)`

1. Load user by email.
2. Load `PasswordResetToken` with `findByTokenAndUsedFalse(token)`. If missing → `InvalidResetTokenException`.
3. Check token’s user matches the given email; else invalid.
4. If `expiryDate` is before now → delete token, throw `InvalidResetTokenException`.
5. Set `used = true`, save token.
6. `user.setPassword(passwordEncoder.encode(newPassword))`, save user.

**File:** `src/main/java/com/app/exception/InvalidResetTokenException.java`  
**File:** `src/main/java/com/app/exception/GlobalExceptionHandler.java` — map this to **HTTP 400** with the exception message.

---

## Step 6: REST API

**File:** `src/main/java/com/app/controller/AuthController.java`

| Method | Path | Body | Response |
|--------|------|------|----------|
| `POST` | `/api/auth/forgot-password` | `{ "email": "..." }` | 200 + generic success message |
| `POST` | `/api/auth/reset-password` | `{ "email", "token", "newPassword" }` | 200 on success |

These paths are **public** because they are under `/api/auth/**`, which `SecurityConfig` permits without JWT.

---

## Step 7: Security checklist

| Topic | What we did |
|-------|-------------|
| Password storage | BCrypt via existing `PasswordEncoder` bean (`PasswordEncoderConfig`). |
| OTP strength | `SecureRandom`, not `Random`. |
| OTP lifetime | 15 minutes. |
| Replay | `used` flag; second use of same OTP fails. |
| Enumeration | API message says “If the email exists…” — align service behavior if you want **no** leak of registered emails (catch missing user and still return 200). |

---

## Step 8: How to test (quick)

1. Register a user (`POST /api/auth/register`).
2. Call `POST /api/auth/forgot-password` with that email.
3. Read OTP from **app logs** (if SES fails) or from **LocalStack SES** behavior / DB for dev:

   ```sql
   SELECT token FROM password_reset_tokens WHERE used = 0 ORDER BY id DESC LIMIT 1;
   ```

4. Call `POST /api/auth/reset-password` with `email`, `token`, `newPassword`.
5. Login with the **new** password (`POST /api/auth/login`).

More examples: [RUNNING.md](../RUNNING.md) in the project root.

---

## File map (forgot password only)

| File | Responsibility |
|------|----------------|
| `entity/PasswordResetToken.java` | DB shape for OTP |
| `repository/PasswordResetTokenRepository.java` | Queries |
| `dto/ForgotPasswordRequest.java` | Forgot body |
| `dto/ResetPasswordRequest.java` | Reset body |
| `service/PasswordResetService.java` | OTP + email + password update |
| `controller/AuthController.java` | `/forgot-password`, `/reset-password` |
| `exception/InvalidResetTokenException.java` | Bad OTP |
| `exception/GlobalExceptionHandler.java` | HTTP mapping |
| `config/AwsConfig.java` | `SesClient` |

---

## Related reading

- [authentication-and-authorization.md](authentication-and-authorization.md) — JWT and roles (login is required after reset).
- [RUNNING.md](../RUNNING.md) — Docker, MySQL, LocalStack.
