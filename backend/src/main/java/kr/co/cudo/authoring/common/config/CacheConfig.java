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
 *   <li><b>stream-deid</b> — 마킹 스트림 비식별 경로 해석(Phase 2 성능). HTTP Range 요청마다
 *       반복되던 DB 조회를 캐시. 한번 생성된 비식별 경로는 안정적이라 5분 TTL. 미완료(null)
 *       결과는 {@code @Cacheable(unless="#result==null")} 로 캐시하지 않아 async 비식별 완료 후
 *       stale NOT_FOUND 가 유지되지 않는다.</li>
 *   <li><b>eventType</b> — 관제 이벤트 타입 필터 옵션·코드라벨 맵(Phase 2). 관제 코드 체계는
 *       near-immutable 이라 반복 조회 시 MNG_* READ 를 피하도록 장수명(6h) 캐시. 인자 없는 단순 키.</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CACHE_SYSCONFIG = "sysconfig";
    public static final String CACHE_STREAM_DEID = "stream-deid";
    public static final String CACHE_EVENT_TYPE = "eventType";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCache sysconfig = new CaffeineCache(CACHE_SYSCONFIG,
                Caffeine.newBuilder()
                        .maximumSize(100)
                        .expireAfterWrite(Duration.ofSeconds(60))
                        .build());

        CaffeineCache streamDeid = new CaffeineCache(CACHE_STREAM_DEID,
                Caffeine.newBuilder()
                        .maximumSize(200)
                        .expireAfterWrite(Duration.ofMinutes(5))
                        .build());

        CaffeineCache eventType = new CaffeineCache(CACHE_EVENT_TYPE,
                Caffeine.newBuilder()
                        .maximumSize(50)
                        .expireAfterWrite(Duration.ofHours(6))
                        .build());

        SimpleCacheManager manager = new SimpleCacheManager();
        manager.setCaches(List.of(sysconfig, streamDeid, eventType));
        return manager;
    }
}
