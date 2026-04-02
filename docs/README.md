# Documentation

This folder contains focused guides for the **localstack-docker-app** Spring Boot project.

| Document | Description |
|----------|-------------|
| [localstack-docker.md](localstack-docker.md) | **Primary:** Docker Compose + LocalStack (`localstack:4566` vs `localhost:4566`), config files, optional local profile. |
| [authentication-and-authorization.md](authentication-and-authorization.md) | How JWT access tokens, refresh tokens, Spring Security, and roles work together. |
| [forgot-password-implementation.md](forgot-password-implementation.md) | Step-by-step how the forgot-password and reset-password flow is implemented (database, SES, APIs). |
| [aws-production.md](aws-production.md) | **Production:** real AWS (SES, S3, IAM), RDS-style MySQL, profile `prod`, `.env.example`. |

The repository root also includes [AUTHENTICATION.md](../AUTHENTICATION.md) (full reference) and [RUNNING.md](../RUNNING.md) (how to run locally or with Docker).
