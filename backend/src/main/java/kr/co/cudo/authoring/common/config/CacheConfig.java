package kr.co.cudo.authoring.common.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * 캐시 활성화 (Phase 12).
 * Caffeine 자동 구성 + spring.cache.* 설정으로 TTL/사이즈 제어.
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
