package kr.co.cudo.authoring.eventtype.service;

import kr.co.cudo.authoring.common.config.CacheConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 이벤트유형 캐시({@link CacheConfig#CACHE_EVENT_TYPE}) 무효화의 <b>단일 진입점</b>.
 *
 * <h3>왜 커밋 <em>이후</em>에 비워야 하는가 (Critical)</h3>
 * <p>쓰기 트랜잭션 <b>안</b>에서 동기적으로 캐시를 비우면 다음 경합 창이 열린다:
 * <ol>
 *   <li>INSERT/UPDATE — <b>아직 미커밋</b></li>
 *   <li>캐시 clear</li>
 *   <li>다른 요청이 {@code filterOptions()} 호출 → 캐시 미스 → {@code findAll()} 이
 *       <b>미커밋 변경을 못 보고</b> 옛 스냅샷으로 캐시를 <b>다시 채움</b></li>
 *   <li>커밋 — 그러나 캐시에는 이미 옛 값이 들어앉았다</li>
 * </ol>
 * <p>결과는 "즉시 반영" 보장이 깨지고 <b>TTL(6시간) 동안 새 유형이 안 보이는</b> 고착이다. 그래서
 * evict 는 반드시 {@link TransactionSynchronization#afterCommit()} 에서 수행한다.
 *
 * <h3>왜 별도 빈인가 — 자기호출로 프록시를 우회하지 않게</h3>
 * <p>이 저장소에는 자기호출(self-invocation)로 프록시를 우회해 트랜잭션/캐시 배선이 조용히 빠진
 * 사고 이력이 있다. 무효화 로직을 호출자 클래스 안에 두면 그 함정이 반복되므로, <b>주입받아
 * 호출</b>하는 별도 빈으로 고정한다. 회귀 가드: {@code EventTypeCacheIT}.
 *
 * <p>트랜잭션 밖에서 호출되면(동기화가 활성이 아니면) <b>즉시</b> 비운다 — 커밋을 기다릴 대상이
 * 없으므로 지연시키면 영영 실행되지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventTypeCacheEvictor {

    private final CacheManager cacheManager;

    /**
     * 이벤트유형 캐시를 <b>커밋 이후</b>에 비운다(트랜잭션 밖이면 즉시).
     *
     * <p>호출자는 "실제로 데이터가 바뀐 경우에만" 이 메서드를 부른다 — 매 호출마다 비우면
     * 장수명(6h) 캐시가 사실상 무력화된다.
     */
    public void evictAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            evictNow();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictNow();
            }
        });
    }

    /**
     * <b>즉시</b> 비운다 — 쓰기가 이미 커밋된 것을 아는 호출자용
     * ({@link EventTypeAutoRegistrar} 는 자체 트랜잭션이 커밋된 뒤에 부른다).
     *
     * <p>이 경우 {@link #evictAfterCommit()} 을 쓰면 <b>호출자의</b> 트랜잭션 커밋까지 미뤄지고,
     * 그 트랜잭션이 롤백되면 <b>이미 커밋된 등록이 캐시에 영영 반영되지 않는다</b>.
     */
    public void evictNow() {
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_EVENT_TYPE);
        if (cache == null) {
            log.debug("[EventType] 캐시({})가 구성돼 있지 않아 무효화를 건너뛴다", CacheConfig.CACHE_EVENT_TYPE);
            return;
        }
        cache.clear();
    }
}
