# How to Run the Project

**Primary path:** run the full stack with **Docker Compose** — Spring Boot (`app`), **MySQL**, and **LocalStack** on one network. The app uses Spring profile **`docker`** and [`application-docker.yml`](src/main/resources/application-docker.yml), which points AWS clients to **`http://localstack:4566`** (service name inside Compose, not `localhost`).

**Optional:** run only the JVM on your machine with profile **`local`** — [`application-local.yml`](src/main/resources/application-local.yml) uses **`http://localhost:4566`** for LocalStack and **`localhost:3307`** for MySQL; that file documents (in comments) why docker-style hostnames must not be used on the host.

Details: [docs/localstack-docker.md](docs/localstack-docker.md).

**Production (real AWS + RDS):** use Spring profile **`prod`**, environment variables as in [`.env.example`](.env.example), and follow [docs/aws-production.md](docs/aws-production.md).

This guide also covers running without Docker, hybrid setups, and troubleshooting.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Option 1: Run with Docker Compose (Recommended)](#option-1-run-with-docker-compose-recommended)
3. [Option 2: Run Locally without Docker](#option-2-run-locally-without-docker)
4. [Option 3: Run App Locally + MySQL/LocalStack in Docker](#option-3-run-app-locally--mysqlLocalStack-in-docker)
5. [Testing the API](#testing-the-api)
6. [Swagger UI](#swagger-ui)
7. [LocalStack Verification](#localstack-verification)
8. [Troubleshooting](#troubleshooting)
9. [Production on AWS](#production-on-aws)

---

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| **Docker** | 20.10+ | Container runtime |
| **Docker Compose** | v2.0+ | Multi-container orchestration |
| **Java** | 17+ | Only needed if running without Docker |
| **Maven** | 3.9+ | Only needed if running without Docker |
| **curl** or **Postman** | Any | API testing |

---

## Option 1: Run with Docker Compose (Recommended)

This is the simplest approach. Docker Compose starts MySQL, LocalStack, and the Spring Boot app together.

### Steps

**1. Clone and navigate to the project:**
```bash
cd localstack-docker-app
```

**2. Start all services:**
```bash
docker-compose up --build
```

**Change the port on your machine (host only):** the app still listens on **8080 inside the container**, but you can map it to another port on `localhost` using `APP_HOST_PORT` (or a `.env` file next to `docker-compose.yml`):

```bash
# Examples — Linux / macOS / Git Bash
APP_HOST_PORT=9090 docker compose up --build -d
# then open http://localhost:9090
```

```powershell
# Windows PowerShell
$env:APP_HOST_PORT = "9090"
docker compose up --build -d
```

**Run the JVM on a different port (not Docker):** set `SERVER_PORT` before starting Spring Boot (matches `application.yml`):

```bash
SERVER_PORT=9090 mvn spring-boot:run -Dspring-boot.run.profiles=local
```

This will:
- Build the Spring Boot application using the multi-stage `Dockerfile`
- Start MySQL 8.0 on port `3307` (mapped from container's `3306`)
- Start LocalStack on port `4566` (initializes S3 bucket + SSM parameters via `init-aws.sh`)
- Start the Spring Boot app on port `8080` (waits for MySQL to be healthy)

**3. Wait for startup:**

Watch the logs. The application is ready when you see:
```
Started LocalstackDockerAppApplication in X.XXX seconds
```

**4. Test it:**
```bash
curl http://localhost:8080/actuator/health
```
Expected response: `{"status":"UP"}`

**5. Stop all services:**
```bash
docker-compose down
```

To also remove volumes (database data):
```bash
docker-compose down -v
```

### Run in detached mode (background):
```bash
docker-compose up --build -d

# View logs
docker-compose logs -f app

# Stop
docker-compose down
```

---

## Option 2: Run Locally without Docker

Use this when you want faster development cycles (hot reload, debugging, etc.).

### Prerequisites

- Java 17 installed and on PATH
- Maven 3.9+ installed
- MySQL 8 installed and running locally
- (Optional) LocalStack CLI installed

### Steps

**1. Set up MySQL:**

```sql
-- Connect to MySQL as root
mysql -u root -p

-- Create the database and user
CREATE DATABASE authdb;
CREATE USER 'appuser'@'localhost' IDENTIFIED BY 'apppassword';
GRANT ALL PRIVILEGES ON authdb.* TO 'appuser'@'localhost';
FLUSH PRIVILEGES;
```

**2. (Optional) Start LocalStack:**

If you have LocalStack installed via pip:
```bash
pip install localstack
localstack start -d
```

Then run the init script:
```bash
bash init-aws.sh
```

**3. Run the application:**

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

**4. The app is available at:**
- API: http://localhost:8080
- Swagger: http://localhost:8080/swagger-ui.html

---

## Option 3: Run App Locally + MySQL/LocalStack in Docker

Best of both worlds: the infrastructure runs in Docker, but the app runs natively for faster development.

### Steps

**1. Start only MySQL and LocalStack:**
```bash
docker-compose up mysql localstack -d
```

**2. Wait for MySQL to be healthy:**
```bash
docker-compose ps
```
Wait until the `auth-mysql` service shows `healthy`.

**3. Run the Spring Boot app locally:**
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The `application-local.yml` profile connects to:
- MySQL at `localhost:3307` (Docker-mapped port)
- LocalStack at `localhost:4566`

**4. When done, stop the infrastructure:**
```bash
docker-compose down
```

---

## Testing the API

Here is a complete workflow to test all endpoints using `curl`.

### 1. Register a User

```bash
curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "fullName": "John Doe",
    "email": "john@example.com",
    "password": "securePassword123"
  }' | json_pp
```

### 2. Login

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "john@example.com",
    "password": "securePassword123"
  }' | json_pp
```

Save the `access_token` and `refresh_token` from the response:
```bash
# On Linux/Mac:
ACCESS_TOKEN="<paste access_token here>"
REFRESH_TOKEN="<paste refresh_token here>"
```

### 3. Get Profile (Protected Endpoint)

```bash
curl -s -X GET http://localhost:8080/api/users/profile \
  -H "Authorization: Bearer $ACCESS_TOKEN" | json_pp
```

### 4. Refresh Token

```bash
curl -s -X POST http://localhost:8080/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d "{
    \"refreshToken\": \"$REFRESH_TOKEN\"
  }" | json_pp
```

### 5. Logout

```bash
curl -s -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer $ACCESS_TOKEN" | json_pp
```

### 6. Forgot Password (request OTP)

```bash
curl -s -X POST http://localhost:8080/api/auth/forgot-password \
  -H "Content-Type: application/json" \
  -d '{
    "email": "john@example.com"
  }' | json_pp
```

The OTP is sent via email (SES). In development with LocalStack, check the app logs:
```bash
docker-compose logs app | grep "OTP"
```

### 7. Reset Password (use the OTP)

```bash
curl -s -X POST http://localhost:8080/api/auth/reset-password \
  -H "Content-Type: application/json" \
  -d '{
    "email": "john@example.com",
    "token": "482917",
    "newPassword": "myNewSecurePassword"
  }' | json_pp
```

### 8. Login with New Password

```bash
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "john@example.com",
    "password": "myNewSecurePassword"
  }' | json_pp
```

### Windows PowerShell Examples

```powershell
# Register
Invoke-RestMethod -Uri "http://localhost:8080/api/auth/register" -Method POST `
  -ContentType "application/json" `
  -Body '{"fullName":"John Doe","email":"john@example.com","password":"securePassword123"}'

# Login
$response = Invoke-RestMethod -Uri "http://localhost:8080/api/auth/login" -Method POST `
  -ContentType "application/json" `
  -Body '{"email":"john@example.com","password":"securePassword123"}'

$accessToken = $response.data.access_token
$refreshToken = $response.data.refresh_token

# Get Profile
Invoke-RestMethod -Uri "http://localhost:8080/api/users/profile" -Method GET `
  -Headers @{ Authorization = "Bearer $accessToken" }

# Refresh Token
Invoke-RestMethod -Uri "http://localhost:8080/api/auth/refresh" -Method POST `
  -ContentType "application/json" `
  -Body "{`"refreshToken`":`"$refreshToken`"}"

# Forgot Password
Invoke-RestMethod -Uri "http://localhost:8080/api/auth/forgot-password" -Method POST `
  -ContentType "application/json" `
  -Body '{"email":"john@example.com"}'

# Check app logs for OTP: docker-compose logs app | Select-String "OTP"

# Reset Password (replace 123456 with OTP from logs)
Invoke-RestMethod -Uri "http://localhost:8080/api/auth/reset-password" -Method POST `
  -ContentType "application/json" `
  -Body '{"email":"john@example.com","token":"123456","newPassword":"myNewSecurePassword"}'

# Logout
Invoke-RestMethod -Uri "http://localhost:8080/api/auth/logout" -Method POST `
  -Headers @{ Authorization = "Bearer $accessToken" }
```

---

## Swagger UI

The application includes Swagger UI for interactive API exploration.

**URL:** http://localhost:8080/swagger-ui.html

### Using JWT in Swagger

1. Open Swagger UI in your browser.
2. Call `POST /api/auth/register` to create a user.
3. Call `POST /api/auth/login` to get tokens.
4. Click the **Authorize** button (lock icon) at the top.
5. Enter the `access_token` value (without "Bearer " prefix).
6. Click **Authorize** then **Close**.
7. Now all protected endpoints will include the JWT automatically.

---

## LocalStack Verification

After starting Docker Compose, you can verify LocalStack resources were created correctly.

### Check S3 Bucket

```bash
aws --endpoint-url=http://localhost:4566 s3 ls
```
Expected: `user-profiles` bucket listed.

### Check SSM Parameters

```bash
aws --endpoint-url=http://localhost:4566 ssm get-parameter \
  --name "/app/jwt/secret" --with-decryption \
  --region us-east-1
```

### Check SES Identity

```bash
aws --endpoint-url=http://localhost:4566 ses list-identities \
  --region us-east-1
```

> **Note:** You need the AWS CLI installed, or you can execute these inside the LocalStack container:
> ```bash
> docker exec auth-localstack awslocal s3 ls
> docker exec auth-localstack awslocal ssm get-parameter --name "/app/jwt/secret" --with-decryption
> ```

---

## Production on AWS

Deploy the same application JAR against **Amazon RDS (MySQL)** and **real SES / S3** by:

1. Setting **`SPRING_PROFILES_ACTIVE=prod`**.
2. Copying **[`.env.example`](.env.example)** to **`.env`**, filling in RDS URL, credentials, `JWT_SECRET`, `AWS_REGION`, **`APP_AWS_SES_FROM_EMAIL`** (must be verified in SES), and **`APP_AWS_ENDPOINT`** left empty.
3. Using an **IAM role** or instance/task credentials instead of long-lived keys when possible.

Full checklist (RDS security groups, SES verification, S3 bucket IAM, credential chain): **[docs/aws-production.md](docs/aws-production.md)**.

---

## Troubleshooting

### MySQL Connection Refused

**Symptom:** `Communications link failure` or `Connection refused`

**Causes & Fixes:**
- MySQL isn't ready yet. Docker Compose has a health check, but if running locally, wait ~30 seconds after starting MySQL.
- Wrong port. Docker maps `3307:3306`. Use port `3307` for local connections, `3306` for container-to-container (Docker profile).
- Check MySQL is running: `docker-compose ps` should show `auth-mysql` as `healthy`.

### LocalStack Init Script Not Running

**Symptom:** SSM parameters or S3 bucket not found.

**Causes & Fixes:**
- The `init-aws.sh` file must have Unix line endings (LF, not CRLF). On Windows, run:
  ```bash
  # Git Bash
  sed -i 's/\r$//' init-aws.sh

  # Or configure Git:
  git config core.autocrlf input
  ```
- The script must be executable. Docker usually handles this, but if not:
  ```bash
  chmod +x init-aws.sh
  ```
- Check LocalStack logs: `docker-compose logs localstack`

### Port Already in Use

**Symptom:** `Bind for 0.0.0.0:8080 failed: port is already allocated`

**Fix:**
```bash
# Find what's using the port
netstat -ano | findstr :8080    # Windows
lsof -i :8080                  # Linux/Mac

# Or change the port mapping in docker-compose.yml:
# ports:
#   - "9090:8080"
```

### Access Token Expired

**Symptom:** API returns 401 after ~15 minutes.

**Fix:** This is expected. Use the refresh token endpoint to get a new access token:
```bash
curl -X POST http://localhost:8080/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken": "<your-refresh-token>"}'
```

### Docker Build Fails at Maven Stage

**Symptom:** `mvn package` fails in Docker build.

**Causes & Fixes:**
- Check your internet connection (Maven needs to download dependencies).
- Try rebuilding without cache: `docker-compose build --no-cache app`
- If behind a proxy, configure Maven proxy settings.

### Application Starts But Cannot Connect to MySQL in Docker

**Symptom:** `Access denied` or `Unknown database`

**Fix:** The database and user are created by MySQL's init environment variables. If the volume already exists from a previous run with different settings:
```bash
docker-compose down -v   # Remove volumes
docker-compose up --build
```

---

## Environment Variables

The Docker profile supports these environment variables (set in `docker-compose.yml` or override):

| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_PROFILES_ACTIVE` | `docker` | Active Spring profile |
| `JWT_SECRET` | (Base64 key) | JWT signing secret |
| `JWT_ACCESS_EXPIRY` | `900000` | Access token expiry in milliseconds (15 min) |
| `JWT_REFRESH_EXPIRY` | `86400000` | Refresh token expiry in milliseconds (24 hr) |

To override in Docker Compose:
```yaml
app:
  environment:
    JWT_ACCESS_EXPIRY: 1800000  # 30 minutes
```
