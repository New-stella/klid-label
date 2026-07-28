package kr.co.cudo.authoring.dataset.service;

import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.entity.LsMetaReplOutbox;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.dataset.repository.LsMetaReplOutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 2 materialize 어댑터 <b>실 DB(PostgreSQL Testcontainer) 통합 테스트</b>.
 *
 * <p>native 소스 조인(LS_DATA_RAW + LS_DATA_META video.* + MNG_*)의 별칭 매핑·서브쿼리 피벗과
 * deactivate-then-insert + outbox 커밋 전 과정을 실 DB 로 검증한다(단위 테스트가 mock 으로 못 잡는
 * SQL/컬럼 정합을 커버).
 */
@SpringBootTest
@ActiveProfiles("local")
class DatasetVideoMetaSnapshotServiceIT {

    /** 이벤트 카테고리 라벨(MAP CD_TYPE='02'.EVNT_NM) — 동결 EVNT_NM 이 이 값이어야 한다. */
    private static final String CATEGORY_LABEL = "보행자 감지";

    /** 수집 키워드(MNG_EX_EVNT_TYPE.CLCT_EVNT_NM) — 라벨이 아니며 EVNT_NM 으로 새면 안 되는 값. */
    private static final String CLCT_KEYWORD = "보행자,사람,횡단보도";

    @Autowired
    private DatasetVideoMetaSnapshotService service;

    @Autowired
    private LsDatasetVideoMetaRepository metaRepository;

    @Autowired
    private LsMetaReplOutboxRepository outboxRepository;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;

    // 시드 정리용 — 공유 컨테이너 오염(다른 테스트의 이벤트/CCTV 카운트 간섭) 방지.
    private final java.util.List<Long> seededRawSns = new java.util.ArrayList<>();
    private final java.util.List<String> seededCctvIds = new java.util.ArrayList<>();
    private final java.util.List<String> seededLclgvCds = new java.util.ArrayList<>();
    private final java.util.List<String> seededEvntCds = new java.util.ArrayList<>();

    DatasetVideoMetaSnapshotServiceIT(
            @Qualifier("controlDataSource") DataSource dataSource,
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        for (Long rawSn : seededRawSns) {
            // event_annotation FK 자식(review) → anno → raw 순으로 삭제(FK 정합).
            jdbc.update("DELETE FROM LS_EVNT_ANNO_REVIEW WHERE EVNT_ANNO_SN IN "
                    + "(SELECT EVNT_ANNO_SN FROM LS_EVNT_ANNO WHERE RAW_SN = ?)", rawSn);
            jdbc.update("DELETE FROM LS_EVNT_ANNO WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_META_REPL_OUTBOX WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATASET_VIDEO_META WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_META WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);
        }
        for (String cctvId : seededCctvIds) {
            jdbc.update("DELETE FROM MNG_RESOURCE_CCTV WHERE VMS_CCTV_ID = ?", cctvId);
        }
        for (String lclgvCd : seededLclgvCds) {
            jdbc.update("DELETE FROM MNG_EX_LOCAL_GOV WHERE LCLGV_CD = ?", lclgvCd);
        }
        for (String evntCd : seededEvntCds) {
            jdbc.update("DELETE FROM MNG_EX_EVNT_TYPE WHERE EVNT_TYPE_CD = ?", evntCd);
        }
        // 합성 카테고리명행(cls='A', ctgry='B001') 정리 — dev-seed 실코드('01'~'08')와 충돌 없음.
        if (!seededEvntCds.isEmpty()) {
            jdbc.update("DELETE FROM MNG_EX_EVNT_TYPE_MAP "
                    + "WHERE CD_TYPE = '02' AND EVNT_CLS_CD = 'A' AND EVNT_CTGRY_CD = 'B001'");
        }
    }

    private long seedSource() {
        // 고유 식별자로 충돌 방지.
        long nano = System.nanoTime();
        String clipId = "CLIP-" + nano;
        String cctvId = "CCTV-" + nano;
        String lclgvCd = "LG-" + (nano % 100000);
        String evntCd = "EV-" + (nano % 100000);
        seededCctvIds.add(cctvId);
        seededLclgvCds.add(lclgvCd);
        seededEvntCds.add(evntCd);

        Long rawSn = jdbc.queryForObject(
                "INSERT INTO LS_DATA_RAW (VMS_CLIP_ID, VMS_CCTV_ID, EVNT_TYPE_CD, LCLGV_CD, "
                        + "PRVC_TYPE_CD, PRVC_YN, DE_IDENT_YN, RAW_FILE_PATH_NM, SHT_DT, VDO_LEN_SEC, "
                        + "ORGNL_RAW_SN, DATA_STTS_CD, REG_DT) "
                        + "VALUES (?, ?, ?, ?, 'PRVC', 'Y', 'Y', ?, ?, 30, NULL, 'APPROVED', ?) "
                        + "RETURNING RAW_SN",
                Long.class,
                clipId, cctvId, evntCd, lclgvCd, "/nas/raw/" + nano + ".mp4",
                LocalDateTime.of(2026, 1, 15, 22, 0), LocalDateTime.now());
        seededRawSns.add(rawSn);

        // MNG 공유 시드 — 조인 동결 검증용.
        jdbc.update("INSERT INTO MNG_RESOURCE_CCTV (VMS_CCTV_ID, CCTV_NM, WGS84_LAT, WGS84_LOT, USE_YN) "
                + "VALUES (?, ?, ?, ?, 'Y')", cctvId, "교차로 CCTV", 37.5665000, 126.9780000);
        jdbc.update("INSERT INTO MNG_EX_LOCAL_GOV (LCLGV_CD, SIDO_NM, SGG_NM, USE_YN) "
                + "VALUES (?, '서울특별시', '중구', 'Y')", lclgvCd);
        // CLCT_EVNT_NM 은 '수집 키워드'(라벨 아님) — 라벨은 MAP CD_TYPE='02' 의 EVNT_NM.
        // 둘을 명확히 다른 값으로 시드해 동결 소스가 키워드가 아닌 카테고리 라벨을 조달함을 검증한다.
        jdbc.update("INSERT INTO MNG_EX_EVNT_TYPE (EVNT_TYPE_CD, EVNT_CLS_CD, EVNT_CTGRY_CD, CLCT_EVNT_NM, CLCT_YN) "
                + "VALUES (?, 'A', 'B001', ?, 'Y')", evntCd, CLCT_KEYWORD);
        jdbc.update("INSERT INTO MNG_EX_EVNT_TYPE_MAP "
                + "(CD_TYPE, EVNT_CLS_CD, EVNT_CTGRY_CD, DTL_EVNT, EVNT_TYPE_CD, EVNT_NM, USE_YN) "
                + "VALUES ('02', 'A', 'B001', '', '', ?, 'Y') "
                + "ON CONFLICT (CD_TYPE, EVNT_CLS_CD, EVNT_CTGRY_CD, DTL_EVNT, EVNT_TYPE_CD) DO NOTHING",
                CATEGORY_LABEL);

        // ffprobe video.* 기술메타(LS_DATA_META).
        seedMeta(rawSn, "video.codec", "h264");
        seedMeta(rawSn, "video.fps", "25");
        seedMeta(rawSn, "video.bit_rate", "4000000");
        seedMeta(rawSn, "video.duration_ms", "30000");
        seedMeta(rawSn, "video.filesize", "15000000");
        seedMeta(rawSn, "video.resolution", "1920x1080");
        return rawSn;
    }

    private void seedMeta(Long rawSn, String key, String value) {
        jdbc.update("INSERT INTO LS_DATA_META (RAW_SN, META_KEY, META_VL, RTRY_NMTM, REG_DT) "
                + "VALUES (?, ?, ?, 0, ?)", rawSn, key, value, LocalDateTime.now());
    }

    /** event_annotation payload + 검토상태 시드. 반환은 EVNT_ANNO_SN. */
    private long seedEventAnnotation(Long rawSn, String payloadJson, String rvwSttsCd) {
        Long annoSn = jdbc.queryForObject(
                "INSERT INTO LS_EVNT_ANNO (RAW_SN, ANNO_CN, REG_ID, REG_DT) "
                        + "VALUES (?, CAST(? AS jsonb), 'tester', ?) RETURNING EVNT_ANNO_SN",
                Long.class, rawSn, payloadJson, LocalDateTime.now());
        jdbc.update("INSERT INTO LS_EVNT_ANNO_REVIEW (EVNT_ANNO_SN, RVW_STTS_CD, META_TYPE_CD, REG_DT, VER) "
                + "VALUES (?, ?, 'VLM', ?, 0)", annoSn, rvwSttsCd, LocalDateTime.now());
        return annoSn;
    }

    private static final String EVENT_ANNO_PAYLOAD =
            "{\"event_class\":\"assault\",\"question\":\"무슨 일?\","
                    + "\"caption\":{\"c1\":{\"caption_text\":\"다툼\",\"cot\":[\"a\",\"b\"]}},"
                    + "\"answer\":\"폭행\","
                    + "\"evidence\":{\"c1\":{\"evidence_text\":\"주먹\",\"obj_id\":[\"o1\"]}}}";

    @Test
    @DisplayName("증강_파생영상의_ORIGINAL_VIDEO_PATH_는_NULL_이다")
    void derivativeVideoExposesNoOriginalPathInDatamartView() {
        // given — 부모 1건 + 파생 1건(ORGNL_RAW_SN 참조). 파생의 RAW_FILE_PATH_NM 은 <파생 자신의
        //         비식별 사본> 경로이며, 결함 시절에는 여기에 부모 원본 NAS 경로가 실렸다.
        long parentRawSn = seedSource();
        long derivativeRawSn = seedSource();
        jdbc.update("UPDATE LS_DATA_RAW SET ORGNL_RAW_SN = ?, RAW_FILE_PATH_NM = ? WHERE RAW_SN = ?",
                parentRawSn,
                "/nas-storage/videos/augment/" + parentRawSn + "/" + derivativeRawSn + "/WINTER.mp4",
                derivativeRawSn);
        jdbc.update("INSERT INTO LS_RAW_DATA_STATUS (RAW_DATA_ID, DATA_STTS_CD, UPD_DT, VER) "
                + "VALUES (?, 'APPROVED', ?, 1)", derivativeRawSn, LocalDateTime.now());

        // when — 검수 승인 동결
        txTemplate.executeWithoutResult(s -> service.materialize(derivativeRawSn));

        // then — 동결 컬럼 null + 관제 뷰의 ORIGINAL_VIDEO_PATH 도 null(관제 연동 계약).
        LsDatasetVideoMeta m = txTemplate.execute(s ->
                metaRepository.findByRawSnAndActiveYn(derivativeRawSn, LsDatasetVideoMeta.ACTIVE_YES)).get(0);
        assertThat(m.getRawFilePathNm()).isNull();
        assertThat(m.getAiCrtYn()).isEqualTo("Y");

        String viewPath = jdbc.query(
                "SELECT ORIGINAL_VIDEO_PATH FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?",
                rs -> rs.next() ? rs.getString(1) : "ROW_ABSENT", derivativeRawSn);
        assertThat(viewPath).isNull();
    }

    @Test
    @DisplayName("검수승인시_event_annotation이_스냅샷으로_동결된다")
    void materialize_freezesApprovedEventAnnotation() {
        // given — 승인(APPROVED) 상태의 event_annotation
        long rawSn = seedSource();
        seedEventAnnotation(rawSn, EVENT_ANNO_PAYLOAD, "APPROVED");

        // when
        txTemplate.executeWithoutResult(s -> service.materialize(rawSn));

        // then — 활성 스냅샷에 event_annotation 동결본이 담긴다(원문 형태 보존).
        LsDatasetVideoMeta m = txTemplate.execute(s ->
                metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES)).get(0);
        assertThat(m.getEvntAnnoCn()).isNotNull();
        assertThat(m.getEvntAnnoCn()).contains("assault").contains("caption_text").contains("evidence");
    }

    @Test
    @DisplayName("수동값_저장된_영상_검수승인시_스냅샷에_수동값_동결")
    void materialize_freezesManualShootingEnvironment() {
        // given — SHT_DT 파생은 야간(22시)·겨울(1월)인데 작업자가 주간·여름·비 를 수동 저장
        long rawSn = seedSource();
        jdbc.update("UPDATE LS_DATA_RAW SET WTHR_NM = ?, DAY_NGT_CD = ?, SESN_CD = ? WHERE RAW_SN = ?",
                "비", "DAY", "SUMMER", rawSn);

        // when — 검수 승인 동결
        txTemplate.executeWithoutResult(s -> service.materialize(rawSn));

        // then — 파생값이 아니라 수동값이 동결된다(native 소스 컬럼 정합 포함)
        LsDatasetVideoMeta m = txTemplate.execute(s ->
                metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES)).get(0);
        assertThat(m.getWthrNm()).isEqualTo("비");
        assertThat(m.getDayNgtCd()).isEqualTo("DAY");
        assertThat(m.getSesnCd()).isEqualTo("SUMMER");
    }

    @Test
    @DisplayName("수동값_미저장_영상은_기존_파생_촬영환경으로_동결된다")
    void materialize_keepsDerivedShootingEnvironmentWhenNoManualValue() {
        // given — 촬영환경 수동값 미입력(회귀 방어)
        long rawSn = seedSource();

        // when
        txTemplate.executeWithoutResult(s -> service.materialize(rawSn));

        // then — 기존 파생 규칙 그대로
        LsDatasetVideoMeta m = txTemplate.execute(s ->
                metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES)).get(0);
        assertThat(m.getWthrNm()).isNull();
        assertThat(m.getDayNgtCd()).isEqualTo("NGT");
        assertThat(m.getSesnCd()).isEqualTo("WINTER");
    }

    @Test
    @DisplayName("승인안된_event_annotation은_동결되지_않는다_null")
    void materialize_skipsUnapprovedEventAnnotation() {
        // given — PENDING(미승인) event_annotation
        long rawSn = seedSource();
        seedEventAnnotation(rawSn, EVENT_ANNO_PAYLOAD, "PENDING");

        // when
        txTemplate.executeWithoutResult(s -> service.materialize(rawSn));

        // then — 동결 대상 없음(null)
        LsDatasetVideoMeta m = txTemplate.execute(s ->
                metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES)).get(0);
        assertThat(m.getEvntAnnoCn()).isNull();
    }

    @Test
    @DisplayName("동결후_원본_event_annotation_수정해도_활성스냅샷은_동결본_유지")
    void materialize_frozenEventAnnotationImmutableAgainstLiveEdit() {
        // given — 승인 event_annotation 동결
        long rawSn = seedSource();
        long annoSn = seedEventAnnotation(rawSn, EVENT_ANNO_PAYLOAD, "APPROVED");
        txTemplate.executeWithoutResult(s -> service.materialize(rawSn));
        String frozen = txTemplate.execute(s ->
                metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES)).get(0).getEvntAnnoCn();

        // when — 원본 LS_EVNT_ANNO payload 수정(동결 후 편집)
        jdbc.update("UPDATE LS_EVNT_ANNO SET ANNO_CN = CAST(? AS jsonb) WHERE EVNT_ANNO_SN = ?",
                "{\"event_class\":\"robbery\"}", annoSn);

        // then — 활성 스냅샷 동결본은 그대로(편집분에 오염되지 않음 = export 멱등의 근거)
        String stillFrozen = txTemplate.execute(s ->
                metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES)).get(0).getEvntAnnoCn();
        assertThat(stillFrozen).isEqualTo(frozen);
        assertThat(stillFrozen).contains("assault").doesNotContain("robbery");
    }

    @Test
    @DisplayName("승인시_통합메타_동결_적재_및_MNG조인_동결_및_outbox")
    void materialize_freezesJoinedMetaAndEmitsOutbox() {
        // given
        long rawSn = seedSource();

        // when
        txTemplate.executeWithoutResult(s -> service.materialize(rawSn));

        // then — 활성 스냅샷 정확히 1건 + MNG 동결 + ffprobe 파생.
        List<LsDatasetVideoMeta> active = txTemplate.execute(s ->
                metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES));
        assertThat(active).hasSize(1);
        LsDatasetVideoMeta m = active.get(0);
        assertThat(m.getCctvNm()).isEqualTo("교차로 CCTV");
        assertThat(m.getWgs84Lat()).isEqualByComparingTo("37.5665000");
        assertThat(m.getWgs84Lot()).isEqualByComparingTo("126.9780000");
        assertThat(m.getSidoNm()).isEqualTo("서울특별시");
        assertThat(m.getSggNm()).isEqualTo("중구");
        assertThat(m.getEvntNm()).isEqualTo(CATEGORY_LABEL);   // MAP CD_TYPE='02' 라벨(수집 키워드 아님)
        assertThat(m.getVdoCdc()).isEqualTo("h264");
        assertThat(m.getFps()).isEqualByComparingTo("25");
        assertThat(m.getBitRt()).isEqualTo(4_000_000L);
        assertThat(m.getFileSz()).isEqualTo(15_000_000L);
        assertThat(m.getResl()).isEqualTo("1920x1080");
        assertThat(m.getVdoWdth()).isEqualTo(1920);
        assertThat(m.getVdoHgt()).isEqualTo(1080);
        assertThat(m.getAsprtRt()).isEqualByComparingTo("1.777778");
        assertThat(m.getDayNgtCd()).isEqualTo("NGT");   // 22시
        assertThat(m.getSesnCd()).isEqualTo("WINTER");  // 1월
        assertThat(m.getAiCrtYn()).isEqualTo("N");      // orgnlRawSn null
        assertThat(m.getSnpshtHash()).hasSize(64);

        // outbox PENDING 1건 발행(같은 트랜잭션 커밋).
        List<LsMetaReplOutbox> pending = txTemplate.execute(s ->
                outboxRepository.findByStatusOrderByRegDtAsc(
                        LsMetaReplOutbox.STATUS_PENDING, PageRequest.of(0, 50)));
        assertThat(pending).anyMatch(o -> o.getRawSn().equals(rawSn)
                && o.getSnpshtHash().equals(m.getSnpshtHash()));
    }

    @Test
    @DisplayName("통합메타_EVNT_NM은_수집키워드가_아니라_카테고리라벨")
    void materialize_freezesCategoryLabelNotCollectKeyword() {
        // given — CLCT_EVNT_NM(수집 키워드) 과 카테고리 라벨(MAP CD_TYPE='02'.EVNT_NM)이 서로 다른 시드.
        long rawSn = seedSource();

        // when
        txTemplate.executeWithoutResult(s -> service.materialize(rawSn));

        // then — 동결된 EVNT_NM 은 카테고리 라벨이며, 수집 키워드(CLCT_EVNT_NM)가 아니다.
        LsDatasetVideoMeta m = txTemplate.execute(s ->
                metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES)).get(0);
        assertThat(m.getEvntNm()).isEqualTo(CATEGORY_LABEL);
        assertThat(m.getEvntNm()).isNotEqualTo(CLCT_KEYWORD);

        // 소스 테이블에는 수집 키워드가 그대로 존재(라벨이 키워드를 덮어쓰지 않았음을 반대 방향으로 확인).
        String seededKeyword = jdbc.queryForObject(
                "SELECT et.CLCT_EVNT_NM FROM MNG_EX_EVNT_TYPE et "
                        + "JOIN LS_DATA_RAW r ON r.EVNT_TYPE_CD = et.EVNT_TYPE_CD WHERE r.RAW_SN = ?",
                String.class, rawSn);
        assertThat(seededKeyword).isEqualTo(CLCT_KEYWORD);
        assertThat(seededKeyword).isNotEqualTo(m.getEvntNm());
    }

    @Test
    @DisplayName("수정_재승인시_옛_PENDING_outbox_SUPERSEDED_coalescing")
    void materialize_supersedesPriorPendingOutbox() {
        // given — 1차 승인으로 outbox O1(H1) PENDING 발행(아직 워커 미처리)
        long rawSn = seedSource();
        txTemplate.executeWithoutResult(s -> service.materialize(rawSn));
        String h1 = txTemplate.execute(s ->
                metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES).get(0).getSnpshtHash());

        // 소스 변경(해상도) → 다른 해시 H2 유도 후 2차 승인(수정)
        jdbc.update("UPDATE LS_DATA_META SET META_VL = '1280x720' "
                + "WHERE RAW_SN = ? AND META_KEY = 'video.resolution'", rawSn);
        txTemplate.executeWithoutResult(s -> service.materialize(rawSn));
        String h2 = txTemplate.execute(s ->
                metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES).get(0).getSnpshtHash());

        // then — 해시가 실제로 바뀌었고, rawSn 당 PENDING outbox 는 최신(H2) 1건만, 옛(H1)은 SUPERSEDED
        assertThat(h2).isNotEqualTo(h1);
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM LS_META_REPL_OUTBOX WHERE RAW_SN = ? AND STATUS = 'PENDING'",
                Integer.class, rawSn);
        Integer superseded = jdbc.queryForObject(
                "SELECT COUNT(*) FROM LS_META_REPL_OUTBOX WHERE RAW_SN = ? AND STATUS = 'SUPERSEDED'",
                Integer.class, rawSn);
        assertThat(pending).isEqualTo(1);
        assertThat(superseded).isEqualTo(1);
        String pendingHash = jdbc.queryForObject(
                "SELECT SNPSHT_HASH FROM LS_META_REPL_OUTBOX WHERE RAW_SN = ? AND STATUS = 'PENDING'",
                String.class, rawSn);
        assertThat(pendingHash).isEqualTo(h2);
    }

    @Test
    @DisplayName("동일메타_재승인시_동일해시_멱등_활성1건")
    void materialize_reapproval_isIdempotent() {
        // given
        long rawSn = seedSource();

        // when — 같은 소스로 두 번 동결(재승인).
        txTemplate.executeWithoutResult(s -> service.materialize(rawSn));
        txTemplate.executeWithoutResult(s -> service.materialize(rawSn));

        // then — 스냅샷 행 1개(중복 없음), 활성 1건.
        List<LsDatasetVideoMeta> all = txTemplate.execute(s -> metaRepository.findByRawSn(rawSn));
        assertThat(all).hasSize(1);
        assertThat(all).filteredOn(r -> r.getActiveYn().equals("Y")).hasSize(1);
    }
}
