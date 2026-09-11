package com.multimodalAgent.agent.service.knowledge;

import java.io.IOException;
import java.io.InputStream;

/** Private object-storage boundary for immutable knowledge originals. */
public interface KnowledgeObjectStore {

    ObjectRef put(PutRequest request) throws IOException;

    StoredObject get(String bucket, String objectKey) throws IOException;

    ObjectRef stat(String bucket, String objectKey) throws IOException;

    void remove(String bucket, String objectKey) throws IOException;

    record PutRequest(
            String bucket,
            String objectKey,
            InputStream content,
            long size,
            String sha256,
            String contentType
    ) {
    }

    record ObjectRef(String bucket, String objectKey, String sha256, long size, String versionId) {
    }

    final class StoredObject implements AutoCloseable {
        private final InputStream content;
        private final ObjectRef ref;

        public StoredObject(InputStream content, ObjectRef ref) {
            this.content = content;
            this.ref = ref;
        }

        public InputStream content() {
            return content;
        }

        public ObjectRef ref() {
            return ref;
        }

        @Override
        public void close() throws IOException {
            content.close();
        }
    }
}
