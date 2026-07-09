package kr.co.cudo.authoring.common.cache;

import kr.co.cudo.authoring.common.config.CacheConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * StreamMetaCacheEvictor 단위 테스트 — 무효화 시점(즉시/AFTER_COMMIT)·롤백 안전·null 가드.
 */
class StreamMetaCacheEvictorTest {

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("evict가_stream-meta캐시에서_rawSn키를_무효화한다")
    void evict_evictsRawSnKey() {
        CacheManager cacheManager = mock(CacheManager.class);
        Cache cache = mock(Cache.class);
        when(cacheManager.getCache(CacheConfig.CACHE_STREAM_META)).thenReturn(cache);
        StreamMetaCacheEvictor evictor = new StreamMetaCacheEvictor(cacheManager);

        evictor.evict(42L);

        verify(cache).evict(42L);
    }

    @Test
    @DisplayName("evict_rawSn이_null이면_no-op")
    void evict_nullRawSn_noOp() {
        CacheManager cacheManager = mock(CacheManager.class);
        StreamMetaCacheEvictor evictor = new StreamMetaCacheEvictor(cacheManager);

        evictor.evict(null);

        verify(cacheManager, never()).getCache(any());
    }

    @Test
    @DisplayName("evictAfterCommit_활성트랜잭션_없으면_즉시_무효화")
    void evictAfterCommit_noTransaction_evictsImmediately() {
        CacheManager cacheManager = mock(CacheManager.class);
        Cache cache = mock(Cache.class);
        when(cacheManager.getCache(CacheConfig.CACHE_STREAM_META)).thenReturn(cache);
        StreamMetaCacheEvictor evictor = new StreamMetaCacheEvictor(cacheManager);

        evictor.evictAfterCommit(7L);

        verify(cache).evict(7L);
    }

    @Test
    @DisplayName("evictAfterCommit_활성트랜잭션이면_커밋후에만_무효화 — 등록시점엔_미실행")
    void evictAfterCommit_activeTransaction_evictsOnlyAfterCommit() {
        CacheManager cacheManager = mock(CacheManager.class);
        Cache cache = mock(Cache.class);
        when(cacheManager.getCache(CacheConfig.CACHE_STREAM_META)).thenReturn(cache);
        StreamMetaCacheEvictor evictor = new StreamMetaCacheEvictor(cacheManager);

        TransactionSynchronizationManager.initSynchronization();
        try {
            evictor.evictAfterCommit(9L);
            // 등록만 되고 아직 커밋 전 — evict 미실행(커밋 전 evict 는 old value 재캐싱 위험)
            verify(cache, never()).evict(any());

            // 커밋 성공 시뮬레이션 — afterCommit 콜백 실행
            List<TransactionSynchronization> syncs =
                    TransactionSynchronizationManager.getSynchronizations();
            syncs.forEach(TransactionSynchronization::afterCommit);

            verify(cache).evict(9L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("evictAfterCommit_활성트랜잭션이_롤백되면_afterCommit_미실행으로_evict가_호출되지_않는다")
    void evictAfterCommit_rolledBackTransaction_doesNotEvict() {
        CacheManager cacheManager = mock(CacheManager.class);
        Cache cache = mock(Cache.class);
        when(cacheManager.getCache(CacheConfig.CACHE_STREAM_META)).thenReturn(cache);
        StreamMetaCacheEvictor evictor = new StreamMetaCacheEvictor(cacheManager);

        TransactionSynchronizationManager.initSynchronization();
        try {
            evictor.evictAfterCommit(11L);

            // 롤백 시뮬레이션 — Spring 은 afterCommit 을 호출하지 않고 afterCompletion(STATUS_ROLLED_BACK)만 호출한다.
            List<TransactionSynchronization> syncs =
                    TransactionSynchronizationManager.getSynchronizations();
            syncs.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

            // 커밋 콜백이 실행되지 않았으므로 옛 값 재캐싱 방지 — 캐시 무효화가 발생하지 않아야 한다.
            verify(cache, never()).evict(any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
