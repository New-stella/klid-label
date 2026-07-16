package kr.co.cudo.authoring.dataset.worker;

import kr.co.cudo.authoring.dataset.entity.LsMetaReplOutbox;
import kr.co.cudo.authoring.dataset.repository.LsMetaReplOutboxRepository;
import kr.co.cudo.authoring.dataset.repository.PortalDatasetVideoMetaRepository;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 포털 복제 워커 <b>실 DB(PostgreSQL Testcontainer) 통합 테스트</b> — happy path + 멱등.
 *
 * <p>테스트 인프라 특성상 control/portal 두 데이터소스가 동일 컨테이너/DB 를 가리킨다
 * ({@code PostgresContainerContextCustomizerFactory}). 따라서 포털 복제본 테이블은 control
 * 원본과 같은 물리 테이블이 되며, 물리 분리(운영 PORTAL_DB_*) 자체는 재현되지 않는다. 대신
 * 워커가 (a) control outbox PENDING 을 폴링해 (b) <b>portal EMF/트랜잭션</b>을 통해 멱등 upsert 하고
 * (c) outbox 를 DONE 전이시키는 로직을 실 DB 로 검증한다. 복제 대상 행은 control materialize 를
 * 돌리지 않고 outbox 만 넣어(=포털 초기 공백), 워커가 payload 로부터 행을 생성하는지 확인한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class MetaReplicationWorkerIT {

    @Autowired
    private MetaReplicationWorker worker;

    @Autowired
    private LsMetaReplOutboxRepository outboxRepository;

    @Autowired
    private PortalDatasetVideoMetaRepository portalRepository;

    private final TransactionTemplate controlTx;
    private final JdbcTemplate jdbc;
    private final List<Long> seededRawSns = new ArrayList<>();

    MetaReplicationWorkerIT(
            @Qualifier("controlDataSource") DataSource controlDataSource,
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.controlTx = new TransactionTemplate(controlTxManager);
        this.jdbc = new JdbcTemplate(controlDataSource);
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        for (Long rawSn : seededRawSns) {
            jdbc.update("DELETE FROM LS_META_REPL_OUTBOX WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATASET_VIDEO_META WHERE RAW_SN = ?", rawSn);
        }
    }

    private String payload(long rawSn, String hash) {
        return "{"
                + "\"rawSn\":" + rawSn + ","
                + "\"snpshtHash\":\"" + hash + "\","
                + "\"orgnlRawSn\":null,"
                + "\"vmsClipId\":\"CLIP-" + rawSn + "\","
                + "\"vmsCctvId\":\"CCTV-1\","
                + "\"rawFilePathNm\":\"/nas/raw/" + rawSn + ".mp4\","
                + "\"shtDt\":\"2026-07-13T10:15:30\","
                + "\"vdoLenSec\":30,"
                + "\"lclgvCd\":\"1111000000\","
                + "\"prvcYn\":\"Y\",\"prvcTypeCd\":\"PRVC\",\"deIdentYn\":\"Y\",\"aiCrtYn\":\"N\","
                + "\"evntTypeCd\":\"EVT01\","
                + "\"cctvNm\":\"교차로 CCTV\",\"wgs84Lat\":37.5665000,\"wgs84Lot\":126.9780000,"
                + "\"sidoNm\":\"서울특별시\",\"sggNm\":\"중구\",\"fileFmt\":\"mp4\",\"evntNm\":\"보행자\","
                + "\"vdoCdc\":\"h264\",\"fps\":25,\"bitRt\":4000000,\"asprtRt\":1.777778,\"resl\":\"1920x1080\","
                + "\"vdoWdth\":1920,\"vdoHgt\":1080,\"fileSz\":15000000,"
                + "\"dayNgtCd\":\"DAY\",\"sesnCd\":\"FALL\","
                + "\"rvwCmplDt\":\"2026-07-13T11:00:00\""
                + "}";
    }

    private Long insertOutbox(long rawSn, String hash) {
        seededRawSns.add(rawSn);
        return controlTx.execute(s ->
                outboxRepository.save(LsMetaReplOutbox.create(rawSn, hash, payload(rawSn, hash)))
                        .getOutboxSn());
    }

    private LsMetaReplOutbox reload(Long outboxSn) {
        return controlTx.execute(s -> outboxRepository.findById(outboxSn).orElseThrow());
    }

    private long activeRowCount(long rawSn) {
        // 포털 리포지토리(portalTransactionManager)를 직접 조회 — 자체 readOnly 트랜잭션으로 실행된다.
        return portalRepository.findByRawSnAndActiveYn(rawSn, "Y").size();
    }

    @Test
    @DisplayName("PENDING_outbox_복제후_DONE_전환")
    void replicatePending_writesPortalRow_andMarksDone() {
        // given — 포털에 복제되지 않은 outbox PENDING 1건(control materialize 미실행 = 포털 공백)
        long rawSn = System.nanoTime();
        Long outboxSn = insertOutbox(rawSn, "hash-repl");

        // when — 워커 1회 실행
        int done = worker.replicatePending();

        // then — 포털 복제본 활성 1행 + outbox DONE
        assertThat(done).isGreaterThanOrEqualTo(1);
        assertThat(activeRowCount(rawSn)).isEqualTo(1);
        LsMetaReplOutbox after = reload(outboxSn);
        assertThat(after.getStatus()).isEqualTo(LsMetaReplOutbox.STATUS_DONE);
        assertThat(after.getProcDt()).isNotNull();
    }

    @Test
    @DisplayName("포털_upsert_멱등_중복복제_무해")
    void replicate_isIdempotent_onDuplicateOutbox() {
        // given — 동일 (rawSn, hash) outbox 2건(재시도/중복 발행 시나리오)
        long rawSn = System.nanoTime();
        Long first = insertOutbox(rawSn, "hash-idem");
        Long second = insertOutbox(rawSn, "hash-idem");

        // when
        worker.replicatePending();

        // then — 포털 복제본은 여전히 1행(ON CONFLICT DO NOTHING), 두 outbox 모두 DONE
        assertThat(activeRowCount(rawSn)).isEqualTo(1);
        assertThat(reload(first).getStatus()).isEqualTo(LsMetaReplOutbox.STATUS_DONE);
        assertThat(reload(second).getStatus()).isEqualTo(LsMetaReplOutbox.STATUS_DONE);
    }
}
