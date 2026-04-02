# Real AWS (production)

This guide describes how to run **localstack-docker-app** against **real AWS services** and a **managed MySQL** database (for example **Amazon RDS**), instead of Docker Compose + LocalStack.

For local development with emulators, see [localstack-docker.md](localstack-docker.md) and [RUNNING.md](../RUNNING.md).

---

## How this app talks to AWS

| Service | Purpose in this project | LocalStack vs real AWS |
|--------|-------------------------|-------------------------|
| **SES** | Send password-reset OTP emails | Real SES requires a **verified identity** (email or domain) in the same **region** as the client |
| **S3** | Configured for profile storage (bucket name in config) | Create a bucket and grant the app’s IAM principal `s3:GetObject` / `s3:PutObject` as needed |
| **SSM** | Client bean available for Parameter Store | Use for secrets or config if you extend the app |

[`AwsConfig.java`](../src/main/java/com/app/config/AwsConfig.java) chooses the mode:

- **`app.aws.endpoint` is non-empty** → LocalStack-style URL + dummy `test` / `test` credentials (development only).
- **`app.aws.endpoint` empty or unset** → Standard AWS endpoints + **`DefaultCredentialsProvider`** (recommended for production).

So in production you **do not** set a LocalStack URL. Credentials come from the **default chain**: environment variables (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`), shared `~/.aws/credentials`, **ECS task role**, **EKS IRSA**, **EC2 instance profile**, etc.

---

## Spring profile: `prod`

Use profile **`prod`** so Spring loads [`application-prod.yml`](../src/main/resources/application-prod.yml).

- **Database:** JDBC URL and credentials come from `SPRING_DATASOURCE_*` (typical for RDS).
- **JWT:** `JWT_SECRET` is required (Base64 HMAC key; use a new strong secret, not the dev default).
- **SES “From” address:** `APP_AWS_SES_FROM_EMAIL` must match a **verified** SES identity.
- **S3:** `AWS_S3_BUCKET` should be your real bucket name (optional if you do not use S3 yet).
- **JPA:** `ddl-auto` defaults to **`validate`**. For the very first deployment against an empty database you may set `JPA_DDL_AUTO=update` once, then switch back to `validate` and manage schema with migrations in the long term.

Example:

```bash
export SPRING_PROFILES_ACTIVE=prod
# load variables from a file (bash)
set -a && source .env && set +a
java -jar target/localstack-docker-app-*.jar
```

On **Windows PowerShell**, set variables or use `Get-Content .env | ForEach-Object { ... }` patterns, or configure your process manager / ECS task definition with the same names as in [`.env.example`](../.env.example).

---

## Environment file

The repository includes **[`.env.example`](../.env.example)** as a **template** (no real secrets).

1. Copy it to **`.env`** in the project root (`.env` is listed in `.gitignore`).
2. Replace placeholders with your RDS endpoint, passwords, JWT secret, region, bucket name, and verified SES sender.

Variable names follow **Spring Boot relaxed binding**, for example:

| Variable | Maps to |
|----------|---------|
| `APP_AWS_ENDPOINT` | `app.aws.endpoint` (leave **empty** for real AWS) |
| `AWS_REGION` | `app.aws.region` |
| `APP_AWS_SES_FROM_EMAIL` | `app.aws.ses.from-email` |
| `AWS_S3_BUCKET` | `app.aws.s3.bucket-name` |

---

## AWS Console / IAM checklist

### 1. Region

Create resources and run the app in one consistent **region** (for example `us-east-1`). Set `AWS_REGION` to match.

### 2. Amazon RDS (MySQL)

- Create a **MySQL** instance (compatible with the driver in `pom.xml`).
- Security group: allow the app host (EC2 security group, ECS service, VPN, or bastion) to reach **3306**.
- Build `SPRING_DATASOURCE_URL` with **SSL** parameters appropriate for your policy, for example:

  `jdbc:mysql://<endpoint>:3306/authdb?useSSL=true&serverTimezone=UTC`

- Use strong credentials; store them in **Secrets Manager** or **SSM Parameter Store** and inject at runtime if possible, rather than plain text in `.env` on disk.

### 3. Amazon SES

- **Verify** the sender (or domain) you use in `APP_AWS_SES_FROM_EMAIL`.
- If your account is in the SES **sandbox**, you can only send to **verified** recipient addresses; request production access when ready.
- Attach an IAM policy allowing **`ses:SendEmail`** (and **`ses:SendRawEmail`** if you add raw MIME later) on resource `*` or scoped ARNs.

### 4. Amazon S3

- Create a **bucket**; enable default encryption and block public access unless you have a specific requirement.
- IAM: allow `s3:ListBucket` on the bucket and `s3:GetObject` / `s3:PutObject` on `arn:aws:s3:::bucket-name/*` for the app role.

### 5. IAM for the application

Prefer **short-lived credentials** via:

- **ECS task role** or **EKS service account (IRSA)**  
- **EC2 instance profile**

Avoid long-lived access keys on servers when possible. If you must use keys, rotate them and never commit them to git.

---

## Security notes

- **Never commit** `.env` with real secrets. Use `.env.example` only as a template.
- **JWT_SECRET:** generate a new random Base64 key for production; rotate with a token invalidation strategy if you change it.
- **LocalStack keys** (`test` / `test`) are only for the emulator; do not use them against real AWS.

---

## Differences from LocalStack

| Topic | LocalStack (dev) | Real AWS (prod) |
|-------|------------------|-----------------|
| Endpoint | `http://localstack:4566` or `http://localhost:4566` | Omit `APP_AWS_ENDPOINT` (default AWS endpoints) |
| Credentials | Dummy keys in code | IAM role or default credential chain |
| SES | Emulated identities from init scripts | Verified identities + SES sending limits |
| MySQL | Container on `mysql:3306` or `localhost:3307` | RDS hostname, TLS, security groups |

---

## Related files

- [`application-prod.yml`](../src/main/resources/application-prod.yml) — production profile defaults
- [`AwsConfig.java`](../src/main/java/com/app/config/AwsConfig.java) — S3 / SES / SSM client wiring
- [`RUNNING.md`](../RUNNING.md) — local and Docker Compose workflows
