package kr.co.cudo.authoring.dev;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

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
 * <p><b>멱등성 — 추가만 한다(DELETE 없음)</b>: dev-seed.sql 은 마스터/시작점 행을 "없을 때만"
 * 넣는다({@code ON CONFLICT DO NOTHING/DO UPDATE}). 업무 진행 결과(LS_DATA_RAW·LS_DATA_SRC·
 * LS_DATA_LBL·상태·배정 …)는 건드리지 않는다. 구 시드는 "시드 ID 범위 DELETE 후 INSERT" 였고,
 * 그 DELETE 가 V146 의 FK({@code ON DELETE CASCADE})를 타고 <b>사용자 작업 데이터를 통째로</b>
 * 지웠다(로컬 재기동 시 프레임 전량 소실 사고).
 *
 * <p><b>원자성</b>: 스크립트 전체를 <b>단일 트랜잭션</b>으로 실행한다. 어느 구문이 실패하든 앞선
 * 구문까지 함께 롤백되어 "삭제는 커밋됐는데 복구 INSERT 는 실행되지 않은" 부분 적용 상태가 남지
 * 않는다.
 *
 * <p>fail-soft: 로컬 편의 기능이므로 적재 실패가 부팅을 막지 않는다(기동 차단은 로컬 개발을
 * 세우므로 채택하지 않는다). 단 예외를 삼키지 않고 ERROR 로 남기며, "전량 롤백되어 시드가 적용되지
 * 않았다" 는 사실을 함께 알린다. 로그에는 소스 경로만 남기고 시드 row 내용(PII)은 출력하지 않는다.
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
            // fail-soft — 로컬 편의 기능이므로 부팅은 막지 않되 예외는 삼키지 않는다.
            //   단일 트랜잭션이므로 실패 시 시드는 <전량 롤백>이며 DB 는 실행 전 상태 그대로다.
            //   이 사실을 함께 알리지 않으면 "일부만 적용된 DB" 를 의심하며 원인을 찾게 된다.
            log.error("[DevSeed] seed apply FAILED — 전량 롤백되어 시드가 적용되지 않았습니다"
                    + " (부팅은 계속). source={}", SEED_SCRIPT_LOCATION, e);
        }
    }

    /**
     * controlDataSource 커넥션에 dev-seed.sql 전체를 실행한다. VisibleForTesting.
     */
    void applySeed() {
        applyScript(new ClassPathResource(SEED_SCRIPT_LOCATION));
    }

    /**
     * 주어진 SQL 스크립트를 <b>단일 트랜잭션</b>으로 실행한다.
     *
     * <p>{@link ResourceDatabasePopulator} 로 SQL 파일을 통째로 실행하되(수동 split 금지),
     * {@link DatabasePopulatorUtils} 를 트랜잭션 경계 <b>안</b>에서 호출한다 —
     * {@code populator.execute(dataSource)} 는 autocommit 커넥션에 구문을 하나씩 커밋하므로,
     * 중간 실패 시 이미 실행된 구문이 되돌아가지 않는다(사고 원인).
     * {@code DatabasePopulatorUtils} 는 {@code DataSourceUtils} 로 커넥션을 얻어 진행 중인
     * 트랜잭션에 참여하므로, 실패 시 스크립트 전체가 롤백된다.
     *
     * <p>스크립트를 인자로 받는 이유(VisibleForTesting): 원자성 자체를 검증하려면 "도중에 반드시
     * 실패하는 스크립트" 가 필요한데, 정상 시드는 (설계상) 실패하지 않기 때문이다.
     */
    void applyScript(Resource script) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(script);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(controlDataSource));
        tx.executeWithoutResult(status -> DatabasePopulatorUtils.execute(populator, controlDataSource));
        log.info("[DevSeed] applied seed in single transaction source={}", SEED_SCRIPT_LOCATION);
    }
}
