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
 *       불변이라 5분 TTL 안전. 미완료(null) 는 캐시하지 않아 async 완료 후 stale NOT_FOUND 가 유지되지
 *       않는다. 비식별본 교체/무효화(신고→'F', 수동 resolve, 재비식별 완료) 시에는
 *       {@code StreamMetaCacheEvictor.evictAfterCommit(rawSn)} 로 커밋 후 즉시 무효화해 옛 경로/크기/MIME
 *       서빙(privacy 회귀·Range 경계 오류)을 차단한다. 이 캐시가 스트림 핫패스를 단일로 포괄하므로
 *       별도 {@code stream-deid} 캐시는 두지 않는다(무효화 표면 단일화).</li>
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
                        .expireAfterWrite(Duration.ofMinutes(5))
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
