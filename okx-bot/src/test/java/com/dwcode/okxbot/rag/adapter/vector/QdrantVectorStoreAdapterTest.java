package com.dwcode.okxbot.rag.adapter.vector;

import com.dwcode.okxbot.common.exception.BusinessException;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QdrantVectorStoreAdapterTest {

    @Test
    void filterRequiresUserId() {
        assertThrows(BusinessException.class, () -> QdrantVectorStoreAdapter.buildUserFilter(0));
        assertThrows(BusinessException.class, () -> QdrantVectorStoreAdapter.buildUserFilter(-1));
    }

    @Test
    void filterContainsUserId() {
        Points.Filter f = QdrantVectorStoreAdapter.buildUserFilter(42L);
        assertEquals(1, f.getMustCount());
        assertTrue(f.toString().contains("42"));
    }
}
