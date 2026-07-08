package kr.co.cudo.authoring.common.cache;

import kr.co.cudo.authoring.common.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 마킹 스트림 메타 캐시({@code stream-meta}) 무효화 유틸 (DEV_FIX HIGH — CWE-359 privacy + 무결성).
 *
 * <p>{@code VideoStreamService.resolveStreamMeta} 는 rawSn 키로 해석된 비식별 파일 경로 +
 * contentLength + mediaType 를 5분 TTL 로 캐시한다. 비식별본이 <b>교체·무효화</b>되는 흐름
 * (신고→DE_IDNTF_YN='F', 외부 수동 재비식별 resolve, KPST 재비식별 완료로 비식별본 in-place 교체,
 * 배치 비식별 완료)에는 캐시를 즉시 무효화해야 한다. 무효화가 없으면 최대 TTL 동안 옛 경로/옛 파일
 * 크기/옛 MIME 가 계속 서빙되어, 교체된 개인정보 노출본을 노출하거나 옛 contentLength 로 Range
 * 경계가 어긋나 재생 잘림/500 을 유발한다.
 *
 * <p><b>프록시 self-invocation 회피</b>: {@code @CacheEvict} 어노테이션은 자기호출 시 프록시를
 * 우회해 무효화가 누락될 수 있으므로, {@link CacheManager} 를 직접 주입해 {@code cache.evict(key)} 로
 * 무효화한다({@code UserRoleResolver.evict} 준용 + 프록시 의존 제거).
 *
 * <p><b>AFTER_COMMIT 안전</b>: {@link #evictAfterCommit(Long)} 은 활성 트랜잭션이 있으면 커밋 성공
 * 후({@code afterCommit})에만 evict 를 실행하고 롤백 시에는 실행하지 않는다(커밋 전 evict 는 동시
 * 요청이 옛 값을 재캐싱할 수 있음). 트랜잭션 동기화가 없는 경우(테스트 등)는 즉시 evict 로 폴백한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StreamMetaCacheEvictor {

    private final CacheManager cacheManager;

    /**
     * stream-meta 캐시에서 해당 rawSn 항목을 즉시 무효화한다(부수효과만). rawSn 이 null 이거나
     * 캐시가 미구성이면 무시(no-op).
     */
    public void evict(Long rawSn) {
        if (rawSn == null) {
            return;
        }
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_STREAM_META);
        if (cache != null) {
            cache.evict(rawSn);
            log.debug("[StreamMetaCache] evicted rawSn={}", rawSn);
        }
    }

    /**
     * 트랜잭션 커밋 후 stream-meta 캐시를 무효화한다. 활성 트랜잭션이 있으면 {@code afterCommit}
     * 콜백으로 등록해 커밋 성공 시에만 실행(롤백 시 미실행)하고, 없으면 즉시 실행한다.
     */
    public void evictAfterCommit(Long rawSn) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(rawSn);
                }
            });
        } else {
            evict(rawSn);
        }
    }
}
