package com.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.net.URI;

/**
 * AWS SDK v2 clients for S3, SES, and SSM.
 * <p>
 * <b>Primary: Docker Compose + LocalStack</b> — activate Spring profile {@code docker}
 * ({@code SPRING_PROFILES_ACTIVE=docker}). {@code application-docker.yml} sets
 * {@code app.aws.endpoint: http://localstack:4566} so this JVM (running inside the {@code app}
 * container) calls LocalStack by its Compose service name.
 * <p>
 * <b>Local JVM (optional)</b> — profile {@code local} in {@code application-local.yml} sets
 * {@code app.aws.endpoint: http://localhost:4566} while MySQL/LocalStack run via
 * {@code docker compose up mysql localstack}. Those URLs are only in YAML; this class does not
 * hardcode {@code localhost} vs {@code localstack}.
 * <p>
 * <b>Real AWS</b> — omit or clear {@code app.aws.endpoint}; clients use
 * {@link DefaultCredentialsProvider} (IAM role, env vars, ~/.aws/credentials).
 */
@Configuration
public class AwsConfig {

    /** LocalStack dummy access key (standard for emulator). */
    private static final String LOCALSTACK_ACCESS_KEY = "test";
    /** LocalStack dummy secret key (standard for emulator). */
    private static final String LOCALSTACK_SECRET_KEY = "test";

    @Value("${app.aws.endpoint:}")
    private String awsEndpoint;

    @Value("${app.aws.region:us-east-1}")
    private String awsRegion;

    private static StaticCredentialsProvider localStackStaticCredentials() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCALSTACK_ACCESS_KEY, LOCALSTACK_SECRET_KEY));
    }

    private boolean useLocalStack() {
        return awsEndpoint != null && !awsEndpoint.isBlank();
    }

    @Bean
    public S3Client s3Client() {
        var builder = S3Client.builder()
                .region(Region.of(awsRegion))
                .forcePathStyle(true);

        if (useLocalStack()) {
            // LocalStack: path-style buckets, custom endpoint (Docker: localstack:4566)
            builder.endpointOverride(URI.create(awsEndpoint.trim()))
                    .credentialsProvider(localStackStaticCredentials());
        } else {
            // Real S3 — default credential chain (not LocalStack)
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        return builder.build();
    }

    @Bean
    public SesClient sesClient() {
        var builder = SesClient.builder()
                .region(Region.of(awsRegion));

        if (useLocalStack()) {
            // Password reset emails; LocalStack SES (see init-aws.sh for verified identities)
            builder.endpointOverride(URI.create(awsEndpoint.trim()))
                    .credentialsProvider(localStackStaticCredentials());
        } else {
            // Real SES — default credential chain
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        return builder.build();
    }

    @Bean
    public SsmClient ssmClient() {
        var builder = SsmClient.builder()
                .region(Region.of(awsRegion));

        if (useLocalStack()) {
            builder.endpointOverride(URI.create(awsEndpoint.trim()))
                    .credentialsProvider(localStackStaticCredentials());
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        return builder.build();
    }
}
