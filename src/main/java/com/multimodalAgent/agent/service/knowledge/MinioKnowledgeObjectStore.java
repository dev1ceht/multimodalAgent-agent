package com.multimodalAgent.agent.service.knowledge;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** MinIO-backed implementation enabled only for the explicit kafka-minio mode. */
@Component
@ConditionalOnProperty(
        prefix = "multimodal-agent.knowledge",
        name = "ingestion-mode",
        havingValue = "kafka-minio")
public class MinioKnowledgeObjectStore implements KnowledgeObjectStore {

    private final MinioClient client;

    public MinioKnowledgeObjectStore(multimodalAgentProperties properties) {
        var minio = properties.getKnowledge().getMinio();
        require(minio.getEndpoint(), "MINIO_ENDPOINT");
        require(minio.getAccessKey(), "MINIO_ACCESS_KEY");
        require(minio.getSecretKey(), "MINIO_SECRET_KEY");
        require(minio.getBucket(), "MINIO_KNOWLEDGE_BUCKET");
        try {
            this.client = MinioClient.builder()
                    .endpoint(minio.getEndpoint())
                    .credentials(minio.getAccessKey(), minio.getSecretKey())
                    .build();
            if (!client.bucketExists(io.minio.BucketExistsArgs.builder().bucket(minio.getBucket()).build())) {
                throw new IllegalStateException("MinIO bucket does not exist: " + minio.getBucket());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot connect to the configured MinIO bucket", exception);
        }
    }

    @Override
    public ObjectRef put(PutRequest request) throws IOException {
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(request.bucket())
                    .object(request.objectKey())
                    .stream(request.content(), request.size(), -1)
                    .contentType(request.contentType() == null ? "application/octet-stream" : request.contentType())
                    .userMetadata(Map.of("sha256", request.sha256()))
                    .build());
            return stat(request.bucket(), request.objectKey());
        } catch (Exception exception) {
            throw asIoException("Cannot store knowledge original", exception);
        }
    }

    @Override
    public StoredObject get(String bucket, String objectKey) throws IOException {
        try {
            StatObjectResponse stat = client.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            GetObjectResponse content = client.getObject(
                    GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return new StoredObject(content, toRef(bucket, objectKey, stat));
        } catch (Exception exception) {
            throw asIoException("Cannot read knowledge original", exception);
        }
    }

    @Override
    public ObjectRef stat(String bucket, String objectKey) throws IOException {
        try {
            return toRef(bucket, objectKey,
                    client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build()));
        } catch (Exception exception) {
            throw asIoException("Cannot inspect knowledge original", exception);
        }
    }

    @Override
    public void remove(String bucket, String objectKey) throws IOException {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception exception) {
            throw asIoException("Cannot remove knowledge original", exception);
        }
    }

    private ObjectRef toRef(String bucket, String objectKey, StatObjectResponse stat) {
        String sha256 = stat.userMetadata().entrySet().stream()
                .filter(entry -> "sha256".equalsIgnoreCase(entry.getKey())
                        || "x-amz-meta-sha256".equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        return new ObjectRef(bucket, objectKey, sha256, stat.size(), stat.versionId());
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when kafka-minio ingestion is enabled");
        }
    }

    private IOException asIoException(String message, Exception exception) {
        return new IOException(message, exception);
    }
}
