package kr.co.cudo.authoring.common.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * 캐시 활성화 (Phase 12) + 캐시별 개별 TTL/사이즈 구성.
 *
 * <p>캐시마다 만료/사이즈 정책이 다르므로(아래) Spring Boot 단일 {@code spring.cache.caffeine.spec}
 * 자동구성 대신 명시적 {@link CacheManager} 빈을 등록해 캐시별 Caffeine 스펙을 부여한다.
 *
 * <ul>
 *   <li><b>sysconfig</b> — 시스템 설정. 변경 빈도 낮음, 정합성 위해 짧은 TTL(60s).</li>
 *   <li><b>stream-meta</b> — 마킹 스트림 메타(해석 경로 + contentLength + mediaType). HTTP Range 요청마다
 *       반복되던 DB 조회 + 파일 stat/MIME/length 재계산을 캐시(4배속 버벅임 완화). 비식별 완료 파일은
 *       불변이라 5분 TTL({@link #STREAM_META_TTL}) 안전. 미완료(null) 는 캐시하지 않아 async 완료 후 stale
 *       NOT_FOUND 가 유지되지 않는다. 비식별본 교체/무효화(신고→'F', 수동 resolve, 재비식별 완료) 시에는
 *       {@code StreamMetaCacheEvictor.evictAfterCommit(rawSn)} 로 커밋 후 즉시 무효화해 옛 경로/크기/MIME
 *       서빙(privacy 회귀·Range 경계 오류)을 차단한다. 이 캐시가 스트림 핫패스를 단일로 포괄하므로
 *       별도 {@code stream-deid} 캐시는 두지 않는다(무효화 표면 단일화).
 *       <p><b>2노드 한계(설계 근거 — 반드시 인지)</b>: 본 {@link CacheManager} 는 프로세스 로컬
 *       (Caffeine)이며 공유 캐시가 아니다. 배포는 <b>2노드 Active-Active</b>(D5 배포 토폴로지)이므로
 *       한 노드에서 수행한 evict 는 <b>다른 노드에 전파되지 않는다</b>. 즉 노드A 가 경로를 옮겨도 노드B 는
 *       최대 TTL 동안 옛 경로를 계속 해석한다. Redis 등 공유 캐시는 도입하지 않기로 확정(운영 결정)했으므로,
 *       <b>정합은 "무효화 전파"가 아니라 "파일 유예 삭제"로 확보</b>한다 — 파일을 옮기고 옛 경로를 지우는
 *       흐름은 구 파일을 즉시 지우지 않고 이 TTL 보다 긴 유예 후에 정리해야 한다. 유예 동안 옛 경로에도
 *       <b>바이트 동일 사본</b>이 살아 있어 stale 을 보는 노드가 정상 재생되며(500 없음), TTL 경과 후
 *       자연히 새 경로로 수렴한다. <b>지금은 이 규칙을 쓰는 흐름이 없다</b> — 유일한 사용처였던 해상도
 *       파생 저장소 이관 백필이 2026-07-30 제거됐다(구 스킴 산출물 정정용 1회성 배치, 대상 소진).
 *       따라서 현재 이 TTL 은 단독으로 조정 가능하나, 파일 이관 흐름을 새로 만들면 유예 하한을 이 값보다
 *       길게 잡고 기동 시 검증하는 배선을 함께 둘 것.</li>
 *   <li><b>eventType</b> — 관제 이벤트 타입 필터 옵션·코드라벨 맵(Phase 2). 관제 코드 체계는
 *       near-immutable 이라 반복 조회 시 MNG_* READ 를 피하도록 장수명(6h) 캐시. 인자 없는 단순 키.</li>
 *   <li><b>userRole</b> — 저작도구 인가 역할(LS_USER_ROLE) 해석(역할 분리 Phase 3). 매 요청마다
 *       JwtAuthenticationFilter 가 sub(userNo)→역할을 조회하므로 짧은 TTL(60s)로 캐시한다. 키는
 *       userNo(Long). 역할 변경/부여 시 {@code UserRoleResolver.evict(userNo)} 를 트랜잭션
 *       AFTER_COMMIT 에 호출해 즉시 무효화한다(강등 지연 방지). null(미배정/조회실패) 결과는
 *       {@code @Cacheable(unless="#result==null")} 로 캐시하지 않아 stale 무권한이 고정되지 않는다.</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CACHE_SYSCONFIG = "sysconfig";
    public static final String CACHE_STREAM_META = "stream-meta";
    public static final String CACHE_EVENT_TYPE = "eventType";
    public static final String CACHE_USER_ROLE = "userRole";

    /**
     * {@code stream-meta} 만료 시간 — <b>유예 삭제(grace period)의 하한 기준</b>이다.
     *
     * <p>프로세스 로컬 캐시라 evict 가 다른 노드에 전파되지 않으므로, 다른 노드가 stale 경로를 계속
     * 해석할 수 있는 최대 시간이 곧 이 값이다. 구 파일 삭제 유예는 반드시 이 값보다 길어야 한다.
     * (이 하한을 기동 시 검증하던 {@code ResolutionBackfillService} 는 2026-07-30 제거됐다 — 현재 파일
     * 유예 삭제를 하는 흐름이 없어 검증 대상도 없다. 그런 흐름을 새로 만들면 검증도 함께 되살릴 것.)
     */
    public static final Duration STREAM_META_TTL = Duration.ofMinutes(5);

    @Bean
    public CacheManager cacheManager() {
        CaffeineCache sysconfig = new CaffeineCache(CACHE_SYSCONFIG,
                Caffeine.newBuilder()
                        .maximumSize(100)
                        .expireAfterWrite(Duration.ofSeconds(60))
                        .build());

        CaffeineCache streamMeta = new CaffeineCache(CACHE_STREAM_META,
                Caffeine.newBuilder()
                        .maximumSize(200)
                        .expireAfterWrite(STREAM_META_TTL)
                        .build());

        CaffeineCache eventType = new CaffeineCache(CACHE_EVENT_TYPE,
                Caffeine.newBuilder()
                        .maximumSize(50)
                        .expireAfterWrite(Duration.ofHours(6))
                        .build());

        CaffeineCache userRole = new CaffeineCache(CACHE_USER_ROLE,
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(Duration.ofSeconds(60))
                        .build());

        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(sysconfig, streamMeta, eventType, userRole));
        return manager;
    }
}
