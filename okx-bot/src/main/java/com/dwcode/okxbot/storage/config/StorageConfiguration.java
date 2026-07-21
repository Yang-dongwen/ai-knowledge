package com.dwcode.okxbot.storage.config;

import com.dwcode.okxbot.storage.LocalObjectStorage;
import com.dwcode.okxbot.storage.ObjectStoragePort;
import com.dwcode.okxbot.storage.r2.R2EndpointSupport;
import com.dwcode.okxbot.storage.r2.R2S3ObjectStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * 对象存储 Bean 装配：{@code storage.provider=local|r2}。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class StorageConfiguration {

    private final StorageProperties storageProperties;
    private final LocalObjectStorage localObjectStorage;

    @Bean
    @Primary
    public ObjectStoragePort objectStoragePort() {
        if (storageProperties.isR2()) {
            StorageProperties.R2 r2 = storageProperties.getR2();
            R2EndpointSupport.validate(r2);

            AwsBasicCredentials creds = AwsBasicCredentials.create(
                    r2.getAccessKeyId().trim(),
                    r2.getSecretAccessKey().trim());
            StaticCredentialsProvider credProvider = StaticCredentialsProvider.create(creds);
            Region region = Region.of(R2EndpointSupport.regionOrAuto(r2));
            var endpoint = R2EndpointSupport.resolveEndpointUri(r2);

            S3Configuration s3cfg = S3Configuration.builder()
                    .pathStyleAccessEnabled(r2.isPathStyle())
                    .chunkedEncodingEnabled(false)
                    .build();

            S3Client s3 = S3Client.builder()
                    .httpClient(UrlConnectionHttpClient.builder().build())
                    .credentialsProvider(credProvider)
                    .region(region)
                    .endpointOverride(endpoint)
                    .serviceConfiguration(s3cfg)
                    .build();

            S3Presigner presigner = S3Presigner.builder()
                    .credentialsProvider(credProvider)
                    .region(region)
                    .endpointOverride(endpoint)
                    .serviceConfiguration(s3cfg)
                    .build();

            log.info("ObjectStoragePort => r2, bucket={}, endpoint={}, envPrefix={}, multipartThreshold={}",
                    r2.getBucket(),
                    R2EndpointSupport.resolveEndpoint(r2),
                    storageProperties.getEnvPrefix(),
                    r2.getMultipartThresholdBytes());
            return new R2S3ObjectStorage(s3, presigner, r2);
        }

        log.info("ObjectStoragePort => local, root={}, envPrefix={}, scratch={}",
                storageProperties.getLocal().getRoot(),
                storageProperties.getEnvPrefix(),
                storageProperties.getScratch().getRoot());
        return localObjectStorage;
    }
}
