package kr.co.cudo.authoring.dev;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * [로컬 전용] dev-seed.sql 자동 적재 Runner.
 *
 * <p>{@code db/seed/dev-seed.sql} 은 Flyway 마이그레이션 경로(db/migration)에 없어 자동 실행되지
 * 않으며, 시나리오 테스트 전 매번 수동 {@code psql -f} 가 필요했다. 본 Runner 가 이 갭을 제거한다.
 *
 * <p>실행 방법:
 * <pre>
 *   ./gradlew bootRun --args='--spring.profiles.active=local'
 * </pre>
 *
 * <p>실행 순서 보장: Spring Boot 는 Flyway 마이그레이션을 {@link CommandLineRunner} 보다 먼저
 * 수행하므로, 본 Runner 시점에는 LS_/MNG_ 테이블이 이미 생성되어 있다.
 *
 * <p>운영 안전 장치 (이중 게이팅 — 운영 DB 오염 방지):
 * <ul>
 *   <li>{@code @Profile("local")} — prd/dev/stg 프로파일에서는 빈 자체가 미등록</li>
 *   <li>{@code authoring.dev.seed.enabled}(기본 true, local 한정) — 토글 off 면 미등록</li>
 * </ul>
 *
 * <p>멱등성: dev-seed.sql 은 시드 ID 범위 DELETE 후 INSERT(+ ON CONFLICT)로 매 부팅 재실행해도
 * 안전하다.
 *
 * <p>fail-soft: 로컬 편의 기능이므로 적재 실패가 부팅을 막지 않는다. 단 예외를 삼키지 않고 WARN
 * 으로 남긴다. 로그에는 카운트/소스 경로만 남기고 시드 row 내용(PII)은 출력하지 않는다.
 */
@Slf4j
@Component
@Profile("local")
@ConditionalOnProperty(name = "authoring.dev.seed.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class DevSeedRunner implements CommandLineRunner {

    /** 시드 SQL classpath 경로 — 고정 상수(사용자 입력 아님, Path Manipulation 무관). */
    static final String SEED_SCRIPT_LOCATION = "db/seed/dev-seed.sql";

    @Qualifier("controlDataSource")
    private final DataSource controlDataSource;

    @Override
    public void run(String... args) {
        try {
            applySeed();
        } catch (RuntimeException e) {
            // fail-soft — 로컬 편의 기능이므로 부팅은 막지 않되 예외는 삼키지 않고 WARN.
            log.warn("[DevSeed] seed apply failed (boot continues) source={}", SEED_SCRIPT_LOCATION, e);
        }
    }

    /**
     * controlDataSource 커넥션에 dev-seed.sql 전체를 실행한다.
     *
     * <p>{@link ResourceDatabasePopulator} 로 SQL 파일을 통째로 실행한다(수동 split 금지).
     * dev-seed.sql 이 멱등이므로 반복 호출해도 안전하다. VisibleForTesting.
     */
    void applySeed() {
        Resource script = new ClassPathResource(SEED_SCRIPT_LOCATION);
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(script);
        populator.execute(controlDataSource);
        log.info("[DevSeed] applied seed source={}", SEED_SCRIPT_LOCATION);
    }
}
