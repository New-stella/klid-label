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
 * <p><b>AFTER_COMMIT 시점</b>: {@link #evictAfterCommit(Long)} 은 활성 트랜잭션이 있으면 커밋 성공
 * 후({@code afterCommit})에만 evict 를 실행하고 롤백 시에는 실행하지 않는다. 트랜잭션 동기화가 없는
 * 경우(비트랜잭션 오케스트레이터·단위 테스트 등)는 즉시 evict 로 폴백한다.
 *
 * <h3>보증 범위 — 과장 금지 (실제 보증만 기술)</h3>
 * <ul>
 *   <li><b>보증한다</b> — ①롤백된 변경으로는 캐시를 비우지 않는다. ②evict 시점 이후에 <b>새로 시작</b>된
 *       조회는 커밋된 값을 적재한다. ③evict 호출 자체는 원자적이다.</li>
 *   <li><b>보증하지 않는다 (구 서술 정정)</b> — "동시 요청의 옛 값 재캐싱을 막는다"는 <b>사실이 아니다</b>.
 *       {@code @Cacheable} 의 get → load → put 은 원자가 아니어서, 커밋 <b>전</b>에 DB 를 읽은 요청이
 *       evict <b>이후</b>에 put 하면 옛 값이 다시 설치되고 최대 TTL 동안 유지된다. AFTER_COMMIT 은 이
 *       창을 좁힐 뿐 없애지 못한다.</li>
 *   <li><b>다른 노드에는 적용되지 않는다</b> — 캐시는 프로세스 로컬(Caffeine)이고 공유 캐시가 아니다
 *       ({@link CacheConfig#cacheManager()}). 배포는 2노드 Active-Active 이므로 노드A 의 evict 는
 *       노드B 의 캐시를 건드리지 않는다. 노드B 는 최대 {@link CacheConfig#STREAM_META_TTL} 동안 옛
 *       경로를 계속 해석한다.</li>
 * </ul>
 *
 * <p>따라서 <b>경로 교체 후 구 파일 삭제는 evict 만 믿고 즉시 수행해서는 안 된다</b>. 파일을 지우는
 * 흐름(해상도 파생 백필)은 TTL 보다 긴 <b>유예 삭제</b>로 stale 을 보는 노드/요청도 정상 서빙되게 한다
 * ({@code ResolutionBackfillService} 참조).
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
     *
     * <p>커밋 전에 시작된 조회가 evict 이후에 옛 값을 다시 put 하는 것은 막지 못한다(클래스 javadoc
     * "보증하지 않는다" 참조). 그 잔여 창은 ①전체 작업 완료 후 한 번 더 evict 하고 ②구 파일을 TTL 보다
     * 길게 유예 삭제하는 것으로 무해화한다.
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
