// SPDX-License-Identifier: Apache-2.0
package ai.tessary.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.tessary.open.media.MediaStore.MediaRef;
import ai.tessary.open.media.MediaStore.StoredMedia;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The media read cache. What it protects: the judge re-hydrates {@code image_ref} blocks inside the
 * per-grader request build, so an image unit graded by N graders used to issue N identical reads of
 * the same immutable row.
 */
class CachingMediaStoreTest {

    private static StoredMedia media(String id, int size) {
        return new StoredMedia(new MediaRef(id), "image/png", new byte[size]);
    }

    @Test
    void repeatedReadsOfOneObjectHitTheStoreOnce() {
        PostgresMediaStore delegate = mock(PostgresMediaStore.class);
        when(delegate.get(any(), any())).thenReturn(Optional.of(media("m1", 1024)));
        CachingMediaStore store = new CachingMediaStore(delegate, 0);

        for (int i = 0; i < 5; i++) {
            assertTrue(store.get("proj", new MediaRef("m1")).isPresent());
        }
        verify(delegate, times(1)).get(any(), any());
    }

    @Test
    void cacheIsScopedPerProject() {
        // Media is content-addressed PER PROJECT, so the same id in another project is another object.
        PostgresMediaStore delegate = mock(PostgresMediaStore.class);
        when(delegate.get(any(), any())).thenReturn(Optional.of(media("m1", 16)));
        CachingMediaStore store = new CachingMediaStore(delegate, 0);

        store.get("proj-a", new MediaRef("m1"));
        store.get("proj-b", new MediaRef("m1"));
        verify(delegate, times(2)).get(any(), any());
    }

    @Test
    void aMissIsNotCached() {
        PostgresMediaStore delegate = mock(PostgresMediaStore.class);
        when(delegate.get(any(), any())).thenReturn(Optional.empty());
        CachingMediaStore store = new CachingMediaStore(delegate, 0);

        assertTrue(store.get("proj", new MediaRef("gone")).isEmpty());
        assertTrue(store.get("proj", new MediaRef("gone")).isEmpty());
        verify(delegate, times(2)).get(any(), any());
    }

    @Test
    void evictionRespectsTheByteBudget() {
        PostgresMediaStore delegate = mock(PostgresMediaStore.class);
        CachingMediaStore store = new CachingMediaStore(delegate, 1000);

        for (int i = 0; i < 5; i++) {
            when(delegate.get(any(), any())).thenReturn(Optional.of(media("m" + i, 400)));
            store.get("proj", new MediaRef("m" + i));
        }
        assertTrue(store.retainedBytesForTest() <= 1000, "retained bytes must stay within the budget");
    }

    @Test
    void anObjectLargerThanTheBudgetIsServedButNotRetained() {
        // Otherwise one oversized image would evict the entire cache to make room for itself.
        PostgresMediaStore delegate = mock(PostgresMediaStore.class);
        when(delegate.get(any(), any())).thenReturn(Optional.of(media("huge", 5000)));
        CachingMediaStore store = new CachingMediaStore(delegate, 1000);

        assertArrayEquals(
                new byte[5000],
                store.get("proj", new MediaRef("huge")).orElseThrow().bytes());
        assertEquals(0, store.retainedBytesForTest());
        store.get("proj", new MediaRef("huge"));
        verify(delegate, times(2)).get(any(), any());
    }

    @Test
    void putDelegatesUnchanged() {
        PostgresMediaStore delegate = mock(PostgresMediaStore.class);
        when(delegate.put(any(), any(), any())).thenReturn(new MediaRef("stored"));
        CachingMediaStore store = new CachingMediaStore(delegate, 0);

        assertEquals("stored", store.put("proj", new byte[8], "image/png").id());
        verify(delegate, times(1)).put(any(), any(), any());
    }
}
