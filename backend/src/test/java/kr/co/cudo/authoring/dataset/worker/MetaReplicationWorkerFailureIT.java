package kr.co.cudo.authoring.dataset.worker;

import kr.co.cudo.authoring.dataset.entity.LsMetaReplOutbox;
import kr.co.cudo.authoring.dataset.repository.LsMetaReplOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * Phase 3 포털 복제 워커 실패/재시도/dead-letter + 관심 분리 검증.
 *
 * <p>{@link PortalMetaReplicaWriter} 를 {@code @MockBean} 으로 대체해 포털 upsert 예외를 주입하고,
 * outbox 상태 전이(RETRY_CNT++ → max 초과 시 DEAD)와 복제 실패가 control SoT 를 훼손하지 않음
 * (관심 분리)을 검증한다. {@code max-retry=2} 로 낮춰 dead-letter 루프를 짧게 한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = "authoring.meta-replication.max-retry=2")
class MetaReplicationWorkerFailureIT {
    // ── DB-ISSUE-01 / V146: 자식 행이 참조할 부모 영상(LS_DATA_RAW) 시드 ──
    //   FK 신설 전에는 임의 정수를 rawSn 으로 써도 통과했지만 그렇게 만든 데이터는 실제로는 고아였다.
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.jdbc.core.JdbcTemplate parentVideoJdbc;

    private final java.util.List<Long> seededParentRawSns = new java.util.ArrayList<>();

    /** 실재하는 부모 영상 1건을 만들고 rawSn 을 돌려준다(V146 FK). */
    private long newVideo() {
        long rawSn = kr.co.cudo.authoring.support.RawVideoFixture.newRaw(parentVideoJdbc);
        seededParentRawSns.add(rawSn);
        return rawSn;
    }

    @org.junit.jupiter.api.AfterEach
    void cleanSeededParentVideos() {
        // 부모 삭제 = 자식(동결 메타·아웃박스·export 등) CASCADE 삭제.
        seededParentRawSns.forEach(
                sn -> kr.co.cudo.authoring.support.RawVideoFixture.deleteRaws(parentVideoJdbc, sn));
        seededParentRawSns.clear();
    }


    @Autowired
    private MetaReplicationWorker worker;

    @Autowired
    private LsMetaReplOutboxRepository outboxRepository;

    @MockBean
    private PortalMetaReplicaWriter replicaWriter;

    private final TransactionTemplate controlTx;
    private final JdbcTemplate jdbc;
    private final List<Long> seededRawSns = new ArrayList<>();

    MetaReplicationWorkerFailureIT(
            @Qualifier("controlDataSource") DataSource controlDataSource,
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.controlTx = new TransactionTemplate(controlTxManager);
        this.jdbc = new JdbcTemplate(controlDataSource);
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        for (Long rawSn : seededRawSns) {
            jdbc.update("DELETE FROM LS_META_REPL_OUTBOX WHERE RAW_SN = ?", rawSn);
        }
    }

    @BeforeEach
    void stubWriter() {
        // 포털 복제본 테이블은 사용 가능하다고 보되(스킵 아님), 실제 upsert 는 항상 실패시킨다.
        given(replicaWriter.isReplicaAvailable()).willReturn(true);
        willThrow(new RuntimeException("portal down")).given(replicaWriter).replicate(any());
    }

    private Long insertOutbox(long rawSn, String hash) {
        seededRawSns.add(rawSn);
        return controlTx.execute(s ->
                outboxRepository.save(LsMetaReplOutbox.create(rawSn, hash, "{\"rawSn\":" + rawSn + "}"))
                        .getOutboxSn());
    }

    private LsMetaReplOutbox reload(Long outboxSn) {
        return controlTx.execute(s -> outboxRepository.findById(outboxSn).orElseThrow());
    }

    @Test
    @DisplayName("복제실패시_RETRY_증가_후_STATUS_PENDING_유지")
    void failure_incrementsRetry_keepsPending() {
        // given
        long rawSn = newVideo();
        Long outboxSn = insertOutbox(rawSn, "hash-fail");

        // when — 1회 실패
        worker.replicatePending();

        // then — RETRY_CNT=1, 아직 재시도 대상(PENDING)
        LsMetaReplOutbox after = reload(outboxSn);
        assertThat(after.getRetryCnt()).isEqualTo(1);
        assertThat(after.getStatus()).isEqualTo(LsMetaReplOutbox.STATUS_PENDING);
        assertThat(after.getProcDt()).isNull();
    }

    @Test
    @DisplayName("RETRY_max_초과시_DEAD_전환_deadletter")
    void failure_exceedingMaxRetry_movesToDead() {
        // given — max-retry=2
        long rawSn = newVideo();
        Long outboxSn = insertOutbox(rawSn, "hash-dead");

        // when — 2회 실패(각 tick 이 동일 PENDING 을 재폴링)
        worker.replicatePending();
        worker.replicatePending();

        // then — RETRY_CNT=2 도달 → DEAD(dead-letter), 이후 tick 은 PENDING 이 아니라 폴링 제외
        LsMetaReplOutbox after = reload(outboxSn);
        assertThat(after.getRetryCnt()).isEqualTo(2);
        assertThat(after.getStatus()).isEqualTo(LsMetaReplOutbox.STATUS_DEAD);
        assertThat(after.getProcDt()).isNotNull();

        // DEAD 는 더 이상 폴링되지 않아 재시도 카운트가 늘지 않는다.
        worker.replicatePending();
        assertThat(reload(outboxSn).getRetryCnt()).isEqualTo(2);
    }

    @Test
    @DisplayName("복제실패가_승인_materialize_커밋에_무영향_관심분리")
    void replicationFailure_doesNotAffectControlCommit() {
        // given — control 커밋(승인)은 이미 완료되어 outbox PENDING 이 쌓인 상태
        long rawSn = newVideo();
        Long outboxSn = insertOutbox(rawSn, "hash-sep");

        // when — 워커가 실패해도(예외 주입) control 트랜잭션은 롤백되지 않는다
        worker.replicatePending();

        // then — outbox 는 재시도 대상으로 보존(소실/롤백 없음), 승인 워크플로우와 분리
        LsMetaReplOutbox after = reload(outboxSn);
        assertThat(after).isNotNull();
        assertThat(after.getRawSn()).isEqualTo(rawSn);
        assertThat(after.getStatus()).isEqualTo(LsMetaReplOutbox.STATUS_PENDING);
        assertThat(after.getRetryCnt()).isEqualTo(1);
    }
}
