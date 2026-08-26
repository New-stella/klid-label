package kr.co.cudo.authoring.dataset.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
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
import java.time.LocalDateTime;
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

    @Autowired
    private PortalDatasetVideoMetaRepository portalRepository;

    /** 복제 write 단일 진입점 — 프로덕션과 같은 경로(deactivate → upsert → activate)로 검증한다. */
    @Autowired
    private PortalMetaReplicaWriter replicaWriter;

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
        long rawSn = newVideo();
        Long outboxSn = insertOutbox(rawSn, "hash-repl");

        // when — 워커 1회 실행
        int done = worker.replicatePending();

        // then — 포털 복제본 활성 1행 + outbox DONE
        assertThat(done).isGreaterThanOrEqualTo(1);
        assertThat(activeRowCount(rawSn)).isEqualTo(1);
        LsMetaReplOutbox after = reload(outboxSn);
        assertThat(after.getSttsCd()).isEqualTo(LsMetaReplOutbox.STATUS_DONE);
        assertThat(after.getPrcsDt()).isNotNull();
    }

    @Test
    @DisplayName("포털_upsert_멱등_중복복제_무해")
    void replicate_isIdempotent_onDuplicateOutbox() {
        // given — 동일 (rawSn, hash) outbox 2건(재시도/중복 발행 시나리오)
        long rawSn = newVideo();
        Long first = insertOutbox(rawSn, "hash-idem");
        Long second = insertOutbox(rawSn, "hash-idem");

        // when
        worker.replicatePending();

        // then — 포털 복제본은 여전히 1행(ON CONFLICT DO NOTHING), 두 outbox 모두 DONE
        assertThat(activeRowCount(rawSn)).isEqualTo(1);
        assertThat(reload(first).getSttsCd()).isEqualTo(LsMetaReplOutbox.STATUS_DONE);
        assertThat(reload(second).getSttsCd()).isEqualTo(LsMetaReplOutbox.STATUS_DONE);
    }

    // ── event_annotation 동결 payload 복제 (@design INT-009 — 전 컬럼 동형 복제) ──────────────
    //   포털 복제 upsert 에 EVNT_ANNO_CN 이 빠져 있어 복제본에서 영구히 NULL 이던 결함의 회귀 시험.
    //   컬럼 집합 정합 자체는 PortalMetaReplicaColumnParityGuardTest(순수 JUnit)가 고정하고,
    //   여기서는 실 PostgreSQL 에 대해 jsonb CAST 가 실제로 값을 적재하는지(왕복)를 확인한다.

    /** 포털 복제본의 활성 행에서 EVNT_ANNO_CN 원문을 읽는다(복제본 = control 과 동일 물리 테이블). */
    private String activeEvntAnnoCn(long rawSn) {
        List<LsDatasetVideoMeta> rows = portalRepository.findByRawSnAndActiveYn(rawSn, "Y");
        assertThat(rows).hasSize(1);
        return rows.get(0).getEvntAnnoCn();
    }

    private LsDatasetVideoMeta snapshot(long rawSn, String hash, String evntAnnoCn) {
        return LsDatasetVideoMeta.builder()
                .rawSn(rawSn)
                .snpshtHash(hash)
                .activeYn(LsDatasetVideoMeta.ACTIVE_YES)
                .evntAnnoCn(evntAnnoCn)
                .regDt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("이벤트어노테이션_동결본이_포털_복제본에_그대로_적재된다")
    void replicate_carriesEventAnnotationPayload() throws Exception {
        // given — event_annotation 동결 payload 를 가진 스냅샷
        long rawSn = newVideo();
        seededRawSns.add(rawSn);
        String frozen = "{\"event_type\":\"fire\",\"caption\":{\"c1\":{\"caption_text\":\"연기가 보인다\"}}}";

        // when — 프로덕션 복제 경로로 write
        replicaWriter.replicate(snapshot(rawSn, "hash-anno", frozen));

        // then — 복제본에 동일한 값이 적재된다(jsonb 표기 정규화가 있으므로 의미 동등성으로 비교)
        String replicated = activeEvntAnnoCn(rawSn);
        assertThat(replicated).as("EVNT_ANNO_CN 이 복제되지 않고 NULL 로 남으면 포털 채널이 그 값을 받지 못한다")
                .isNotNull();
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(replicated)).isEqualTo(mapper.readTree(frozen));
    }

    @Test
    @DisplayName("이벤트어노테이션_동결본이_없으면_NULL로_적재되고_복제는_성공한다")
    void replicate_acceptsNullEventAnnotationPayload() {
        // given — 승인된 event_annotation 이 없어 동결본이 null 인 스냅샷(정상 케이스)
        long rawSn = newVideo();
        seededRawSns.add(rawSn);

        // when / then — CAST(null AS jsonb) 로 복제가 실패하지 않는다
        replicaWriter.replicate(snapshot(rawSn, "hash-anno-null", null));
        assertThat(activeEvntAnnoCn(rawSn)).isNull();
    }

    @Test
    @DisplayName("이벤트어노테이션_동결본_재복제도_멱등이다")
    void replicate_withEventAnnotation_isIdempotent() {
        // given
        long rawSn = newVideo();
        seededRawSns.add(rawSn);
        String frozen = "{\"event_type\":\"fall\"}";

        // when — 같은 (rawSn, hash) 로 두 번 복제(at-least-once 재전달)
        replicaWriter.replicate(snapshot(rawSn, "hash-anno-idem", frozen));
        replicaWriter.replicate(snapshot(rawSn, "hash-anno-idem", frozen));

        // then — 활성 1행 유지 + 값 보존
        assertThat(activeRowCount(rawSn)).isEqualTo(1);
        assertThat(activeEvntAnnoCn(rawSn)).isNotNull();
    }

    // ── 발신함 → 워커 → 포털 복제본 end-to-end (@design INT-009 — payload 축) ────────────────
    //   복제 경로는 4단(직렬화 → 전송 계약 record → 복원 빌더 → 포털 INSERT)이다. 위 세 시험은
    //   마지막 INSERT 단만 검증하므로, 여기서는 <b>실제 발신함 payload 문자열</b>에서 출발해
    //   워커가 값을 복원·적재하는 전 구간을 확인한다.

    /** {@link #payload} 의 컬럼형 JSON 에 event_annotation 동결값 키를 추가한다. */
    private String payloadWithEventAnnotation(long rawSn, String hash, String evntAnnoCn) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = (ObjectNode) mapper.readTree(payload(rawSn, hash));
        node.put("evntAnnoCn", evntAnnoCn); // JSON 문자열로 인코딩 — 복원 시 원문 그대로 돌아온다
        return mapper.writeValueAsString(node);
    }

    @Test
    @DisplayName("발신함_payload의_이벤트어노테이션이_워커를_거쳐_포털복제본까지_적재된다")
    void replicatePending_carriesEventAnnotation_endToEnd() throws Exception {
        // given — event_annotation 동결값을 실은 발신함 PENDING 1건
        long rawSn = newVideo();
        seededRawSns.add(rawSn);
        String frozen = "{\"event_type\":\"fire\",\"caption\":{\"c1\":{\"caption_text\":\"연기가 보인다\"}}}";
        Long outboxSn = controlTx.execute(s -> outboxRepository.save(
                LsMetaReplOutbox.create(rawSn, "hash-e2e-anno",
                        unchecked(() -> payloadWithEventAnnotation(rawSn, "hash-e2e-anno", frozen)))).getOutboxSn());

        // when — 워커 1회 실행(폴링 → 역직렬화 → 복원 → 포털 upsert)
        worker.replicatePending();

        // then — 복제본 컬럼에 동일한 값이 도달한다(직렬화 입구가 끊겨 있으면 NULL 로 남는다)
        assertThat(reload(outboxSn).getSttsCd()).isEqualTo(LsMetaReplOutbox.STATUS_DONE);
        String replicated = activeEvntAnnoCn(rawSn);
        assertThat(replicated)
                .as("발신함 payload 가 event_annotation 을 싣지 않으면 복제본에서 영구히 NULL 이 된다(INT-009)")
                .isNotNull();
        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(replicated)).isEqualTo(mapper.readTree(frozen));
    }

    @Test
    @DisplayName("이벤트어노테이션_키가_없는_구_발신함_행도_오류없이_복제되고_해당컬럼은_NULL이_된다")
    void replicatePending_acceptsLegacyPayloadWithoutEventAnnotation() {
        // given — 이 변경 이전에 적재돼 아직 처리되지 않은 발신함 행(payload 에 키 자체가 없다).
        //         payload() 헬퍼가 곧 그 구 스킴이다.
        long rawSn = newVideo();
        Long outboxSn = insertOutbox(rawSn, "hash-e2e-legacy");

        // when
        worker.replicatePending();

        // then — 실패로 돌면 미처리 발신함이 전량 dead-letter 로 간다.
        assertThat(reload(outboxSn).getSttsCd()).isEqualTo(LsMetaReplOutbox.STATUS_DONE);
        assertThat(activeEvntAnnoCn(rawSn)).isNull();
    }

    /** 람다 안에서 검사 예외를 던지는 헬퍼(테스트 가독성 목적). */
    private static <T> T unchecked(java.util.concurrent.Callable<T> callable) {
        try {
            return callable.call();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
