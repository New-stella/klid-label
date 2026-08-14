package kr.co.cudo.authoring.dataset.worker;

import io.micrometer.core.instrument.MeterRegistry;
import kr.co.cudo.authoring.dataset.entity.LsMetaReplOutbox;
import kr.co.cudo.authoring.dataset.repository.LsMetaReplOutboxRepository;
import kr.co.cudo.authoring.support.RawVideoFixture;
import kr.co.cudo.authoring.support.UnprovisionedPortalReplica;
import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 포털 복제본 <b>미프로비저닝</b>(포털 DB 에 {@code LS_DATASET_VIDEO_META} 없음) 회귀 가드 IT.
 *
 * <p>배경(dev cudo_246 실측, 2026-07-30): 포털 DB 가 빈 상태였고 워커가 매 tick
 * {@code [MetaReplication] unexpected tick failure reason=UnexpectedRollbackException} 로 실패했다.
 * 원인은 {@code isReplicaAvailable()} 이 <b>자기 {@code @Transactional} 안에서</b> 42P01 을 삼킨 것 —
 * 트랜잭션은 이미 rollback-only 라 커밋 단계에서 터졌고, 설계 의도였던 graceful skip 이 성립하지 않았다.
 *
 * <p>이 결함이 전체 테스트 GREEN 을 통과한 이유는 기본 테스트 인프라가 control/portal 을 <b>같은 DB</b> 로
 * 물려 미프로비저닝 상황 자체를 재현하지 못했기 때문이다. {@link UnprovisionedPortalReplica} 로 portal 만
 * 빈 DB 로 돌려 그 사각을 메운다.
 *
 * <p>검증 계약:
 * <ol>
 *   <li>가용성 probe 는 <b>예외를 던지지 않고</b> false 를 반환한다(= tick 이 ERROR 로 끝나지 않는다).</li>
 *   <li>PENDING outbox 가 있어도 tick 은 0건 처리로 조용히 skip 하고, outbox 를 실패로 몰지 않는다
 *       (재시도/dead-letter 폭주 방지 — 프로비저닝 후 그대로 복제돼야 한다).</li>
 *   <li>skip 사유는 {@code missing}(정상 미프로비저닝)으로 분류된다 — 실장애({@code error})와 혼동 금지.</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("local")
@UnprovisionedPortalReplica
class MetaReplicationUnprovisionedPortalIT {

    private static final String UNAVAILABLE_METRIC = "meta.replication.replica.unavailable";

    @Autowired
    private MetaReplicationWorker worker;

    @Autowired
    private PortalMetaReplicaWriter replicaWriter;

    @Autowired
    private LsMetaReplOutboxRepository outboxRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    private final TransactionTemplate controlTx;
    private final JdbcTemplate controlJdbc;
    private Long seededRawSn;

    MetaReplicationUnprovisionedPortalIT(
            @Qualifier("controlDataSource") DataSource controlDataSource,
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.controlTx = new TransactionTemplate(controlTxManager);
        this.controlJdbc = new JdbcTemplate(controlDataSource);
    }

    @AfterEach
    void cleanup() {
        if (seededRawSn != null) {
            // 부모 삭제 = outbox 등 자식 CASCADE 삭제(V146).
            RawVideoFixture.deleteRaws(controlJdbc, seededRawSn);
            seededRawSn = null;
        }
    }

    @Test
    @DisplayName("포털_복제본_미프로비저닝시_probe는_예외없이_false를_반환한다")
    void isReplicaAvailable_returnsFalse_withoutThrowing_whenTableMissing() {
        // given: portal 데이터소스가 복제본 테이블 없는 빈 DB 를 가리킨다(@UnprovisionedPortalReplica)
        double before = unavailableCount("missing");

        // when / then: 회귀 지점 — 과거엔 여기서 UnexpectedRollbackException 이 터졌다
        assertThatCode(() -> assertThat(replicaWriter.isReplicaAvailable()).isFalse())
                .doesNotThrowAnyException();

        // then: 실장애가 아니라 미프로비저닝(missing)으로 분류된다
        assertThat(unavailableCount("missing")).isGreaterThan(before);
        assertThat(unavailableCount("error")).isZero();
    }

    @Test
    @DisplayName("미프로비저닝_tick은_PENDING을_실패시키지_않고_0건으로_graceful_skip한다")
    void replicatePending_skipsQuietly_andLeavesOutboxPending() {
        // given: 복제 대기 outbox 1건
        seededRawSn = RawVideoFixture.newRaw(controlJdbc);
        Long outboxSn = controlTx.execute(s -> outboxRepository
                .save(LsMetaReplOutbox.create(seededRawSn, "hash-unprovisioned", "{}"))
                .getOutboxSn());

        // when: tick 1회 — 예외 없이 끝나야 한다(과거엔 tick 전체가 ERROR 로 종료)
        int[] done = new int[1];
        assertThatCode(() -> done[0] = worker.replicatePending()).doesNotThrowAnyException();

        // then: 0건 처리 + outbox 는 손대지 않은 채 PENDING 유지(재시도 소진·dead-letter 없음)
        assertThat(done[0]).isZero();
        LsMetaReplOutbox after = controlTx.execute(s -> outboxRepository.findById(outboxSn).orElseThrow());
        assertThat(after.getSttsCd()).isEqualTo(LsMetaReplOutbox.STATUS_PENDING);
        assertThat(after.getRtryNmtm()).isZero();
    }

    private double unavailableCount(String reason) {
        return meterRegistry.find(UNAVAILABLE_METRIC).tag("reason", reason).counters().stream()
                .mapToDouble(io.micrometer.core.instrument.Counter::count)
                .sum();
    }
}
