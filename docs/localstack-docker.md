# LocalStack in Docker (primary setup)

This project’s **default** way to run AWS-dependent features is **Docker Compose** with a **LocalStack** container. The Spring Boot app uses profile **`docker`** and talks to LocalStack at **`http://localstack:4566`** (Compose network). JDBC uses **`mysql:3306`**.

---

## What runs where

| Component | In Docker Compose | On your PC (browser / curl) |
|-----------|-------------------|------------------------------|
| Spring Boot | Service `app`, port **8080** inside container | **http://localhost:8080** |
| MySQL | Service `mysql`, port 3306 inside network | **localhost:3307** (host mapping) |
| LocalStack | Service `localstack`, port **4566** | **http://localhost:4566** |

Inside the **`app`** container, you must **not** use `localhost` for MySQL or LocalStack — use hostnames **`mysql`** and **`localstack`**. That is what [`application-docker.yml`](../src/main/resources/application-docker.yml) configures.

---

## Configuration files

| File | Role |
|------|------|
| [`docker-compose.yml`](../docker-compose.yml) | Defines `mysql`, `localstack`, `app`; sets `SPRING_PROFILES_ACTIVE=docker` for the app. |
| [`application-docker.yml`](../src/main/resources/application-docker.yml) | **`app.aws.endpoint: http://localstack:4566`**, datasource `jdbc:mysql://mysql:3306/...` |
| [`init-aws.sh`](../init-aws.sh) | LocalStack ready-hook: S3 bucket, SSM parameters, SES identity for `noreply@authapp.local`. |
| [`AwsConfig.java`](../src/main/java/com/app/config/AwsConfig.java) | Builds S3/SES/SSM clients: if `app.aws.endpoint` is set → LocalStack + dummy `test/test` credentials; if empty → real AWS `DefaultCredentialsProvider`. |

---

## Local profile (optional, commented in YAML)

[`application-local.yml`](../src/main/resources/application-local.yml) is for running **the JVM on your machine** while MySQL and LocalStack still run in Docker (`docker compose up mysql localstack -d`). It uses **`localhost:3307`** and **`localhost:4566`**.

The same file contains a **commented reference** showing why `mysql` / `localstack` hostnames from the docker profile **do not work** on the host — they only resolve inside the Compose network.

---

## Commands

**Start everything (recommended):**

```bash
docker compose up --build -d
```

**Check LocalStack from the host:**

```bash
docker exec auth-localstack awslocal s3 ls
```

See also [RUNNING.md](../RUNNING.md).
