package com.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.net.URI;

@Configuration
public class AwsConfig {

    @Value("${app.aws.endpoint:}")
    private String awsEndpoint;

    @Value("${app.aws.region:us-east-1}")
    private String awsRegion;

    private StaticCredentialsProvider localCredentials() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test"));
    }

    @Bean
    public S3Client s3Client() {
        var builder = S3Client.builder()
                .region(Region.of(awsRegion))
                .forcePathStyle(true);

        if (!awsEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(awsEndpoint))
                    .credentialsProvider(localCredentials());
        }

        return builder.build();
    }

    @Bean
    public SesClient sesClient() {
        var builder = SesClient.builder()
                .region(Region.of(awsRegion));

        if (!awsEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(awsEndpoint))
                    .credentialsProvider(localCredentials());
        }

        return builder.build();
    }

    @Bean
    public SsmClient ssmClient() {
        var builder = SsmClient.builder()
                .region(Region.of(awsRegion));

        if (!awsEndpoint.isBlank()) {
            builder.endpointOverride(URI.create(awsEndpoint))
                    .credentialsProvider(localCredentials());
        }

        return builder.build();
    }
}
