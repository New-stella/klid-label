package kr.co.cudo.authoring.webhook.migration;

import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotency;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotencyRepository;
import kr.co.cudo.authoring.webhook.idempotency.WebhookIdempotencyLedger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V8 — {@code LS_WEBHOOK_IDEMPOTENCY.APLY_DT}(신청일시) → {@code APLCN_DT}(적용일시) 개명이
 * <b>실제 적용된 스키마</b>에 반영됐고, 그 위에서 원장 경로가 그대로 도는지 확인한다
 * (Testcontainers PostgreSQL).
 *
 * <p>표준용어 근거는 마이그레이션 헤더에 있다. 여기서는 <b>결과 형상</b>과 <b>엔티티 매핑이 실제로
 * 붙는지</b>만 고정한다. 후자가 이 파일의 존재 이유다 — 필드명만 바꾸고 {@code @Column} 을 빠뜨리거나
 * 그 반대여도 <b>컴파일은 통과</b>하고, 어긋남은 런타임 SQL 에서야 드러난다.
 *
 * @design ERD-021
 */
@SpringBootTest
@ActiveProfiles("local")
class V8WebhookIdempotencyAplcnDtRenameIT {

    private static final String TABLE = "ls_webhook_idempotency";
    private static final String OLD_COLUMN = "aply_dt";
    private static final String NEW_COLUMN = "aplcn_dt";

    private static final String KEY_PREFIX = "v8-it-";

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Autowired
    @Qualifier("controlTransactionManager")
    private PlatformTransactionManager txManager;

    @Autowired
    private LsWebhookIdempotencyRepository repository;

    @Autowired
    private WebhookIdempotencyLedger ledger;

    private JdbcTemplate jdbc;
    private TransactionTemplate txTemplate;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(controlDataSource);
        txTemplate = new TransactionTemplate(txManager);
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM " + TABLE + " WHERE IDMP_KEY LIKE ?", KEY_PREFIX + "%");
    }

    // ------------------------------------------------------------------ 형상

    @Test
    @DisplayName("적용일시_컬럼이_표준_물리명으로만_존재한다")
    void columnExistsOnlyUnderStandardName() {
        // 옛 이름이 남아 있으면 개명이 안 된 것이고, 둘 다 있으면 어느 쪽이 정본인지 알 수 없다.
        assertThat(columnCount(NEW_COLUMN)).isEqualTo(1);
        assertThat(columnCount(OLD_COLUMN)).isZero();
    }

    @Test
    @DisplayName("개명_후에도_타입과_널허용이_유지된다")
    void columnShapeIsUnchanged() {
        // 개명은 이름만 바꾼다 — 타입·nullable 이 달라졌다면 RENAME 이 아닌 방식으로 구현된 것이다.
        assertThat(dataType(NEW_COLUMN)).isEqualTo("timestamp without time zone");
        assertThat(isNullable(NEW_COLUMN))
                .as("미처리 행은 적용일시가 없다 — NOT NULL 이 되면 발급(ISSUED) 자체가 막힌다")
                .isEqualTo("YES");
    }

    // ------------------------------------------------------------------ 엔티티 매핑

    @Test
    @DisplayName("엔티티_필드가_개명된_컬럼에_실제로_매핑된다")
    void entityMapsToRenamedColumn() {
        // given — 엔티티로 저장한다(매핑이 어긋나면 여기서 SQL 오류가 난다)
        String key = KEY_PREFIX + "mapping";
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        txTemplate.executeWithoutResult(s -> {
            LsWebhookIdempotency entity = LsWebhookIdempotency.issue(key, LsWebhookIdempotency.CHANNEL_VLM, "ext-1");
            entity.markProcessed("ext-1");
            repository.save(entity);
        });

        // then — 엔티티가 쓴 값이 <새 이름 컬럼>에서 읽힌다(엔티티↔DB 양방향 확인)
        LocalDateTime persisted = jdbc.queryForObject(
                "SELECT " + NEW_COLUMN + " FROM " + TABLE + " WHERE IDMP_KEY = ?",
                LocalDateTime.class, key);
        assertThat(persisted).isNotNull().isAfterOrEqualTo(before);

        LsWebhookIdempotency reloaded = repository.findById(key).orElseThrow();
        assertThat(reloaded.getAplcnDt()).isEqualTo(persisted);
    }

    @Test
    @DisplayName("개명_후에도_발급부터_처리완료까지_원장_경로가_동작한다")
    void ledgerLifecycleWorksAfterRename() {
        // given — 발급(ISSUED): 아직 적용되지 않았으므로 적용일시는 비어 있다
        String key = KEY_PREFIX + "lifecycle";
        ledger.recordIssued(key, LsWebhookIdempotency.CHANNEL_VLM, null);

        Optional<LsWebhookIdempotency> issued = repository.findById(key);
        assertThat(issued).isPresent();
        assertThat(issued.get().getSttsCd()).isEqualTo(LsWebhookIdempotency.STATE_ISSUED);
        assertThat(issued.get().getAplcnDt())
                .as("발급 시점에는 적용된 것이 없다 — 여기에 값이 있으면 미처리/처리완료가 구분되지 않는다")
                .isNull();

        // when — 콜백 처리 완료
        ledger.markProcessed(key, "ext-job-9");

        // then — 상태 전이와 함께 적용일시가 기록된다
        LsWebhookIdempotency processed = repository.findById(key).orElseThrow();
        assertThat(processed.isProcessed()).isTrue();
        assertThat(processed.getAplcnDt()).isNotNull();
        assertThat(processed.getOtsdJobId()).isEqualTo("ext-job-9");
    }

    // ------------------------------------------------------------------ 내부

    private int columnCount(String column) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                Integer.class, TABLE, column);
        return count == null ? 0 : count;
    }

    private String dataType(String column) {
        return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns"
                        + " WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                String.class, TABLE, column);
    }

    private String isNullable(String column) {
        return jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns"
                        + " WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                String.class, TABLE, column);
    }
}
