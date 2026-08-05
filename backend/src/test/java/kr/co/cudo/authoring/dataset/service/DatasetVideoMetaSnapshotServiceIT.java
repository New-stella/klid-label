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
 * <p>native 소스 조인(LS_DATA_RAW + LS_DATA_META video.* + LS_DATA_INGEST + LS_EVNT_TYPE)의 별칭 매핑·서브쿼리 피벗과
 * deactivate-then-insert + outbox 커밋 전 과정을 실 DB 로 검증한다(단위 테스트가 mock 으로 못 잡는
 * SQL/컬럼 정합을 커버).
 */
@SpringBootTest
@ActiveProfiles("local")
class DatasetVideoMetaSnapshotServiceIT {

    /** 이벤트유형 마스터의 이벤트명(LS_EVNT_TYPE.EVNT_NM) — 동결 EVNT_NM 이 이 값이어야 한다. */
    private static final String CATEGORY_LABEL = "보행자 감지";

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
            jdbc.update("DELETE FROM LS_DATA_INGEST WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);
        }
        for (String evntCd : seededEvntCds) {
            jdbc.update("DELETE FROM LS_EVNT_TYPE WHERE EVNT_TYPE_CD = ?", evntCd);
        }
    }

    private long seedSource() {
        // 고유 식별자로 충돌 방지.
        long nano = System.nanoTime();
        String clipId = "CLIP-" + nano;
        String cctvId = "CCTV-" + nano;
        String lclgvCd = "LG-" + (nano % 100000);
        String evntCd = "EV-" + (nano % 100000);
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
        // 관제 인입 평면값 시드 — CCTV명·좌표·파일형식의 유일한 조달처(V167 — 구 MNG_* 마스터 제거).
        //   ★조인 축이 VMS_CCTV_ID/LCLGV_CD 가 아니라 RAW_SN 이다(IngestSourceLink).
        //   지자체명(LCLGV_NM)은 넣되 동결 스냅샷의 sidoNm/sggNm 은 상수 null 이다 — 인입은 지역명을
        //   1필드로만 주고 그 입도가 계약으로 확정되지 않아 시도 전용 필드에 넣지 않는다.
        seedIngestFlatValues(rawSn, cctvId);
        // CLCT_EVNT_NM 은 '수집 키워드'(라벨 아님) — 라벨은 MAP CD_TYPE='02' 의 EVNT_NM.
        // 둘을 명확히 다른 값으로 시드해 동결 소스가 키워드가 아닌 카테고리 라벨을 조달함을 검증한다.
        jdbc.update("INSERT INTO LS_EVNT_TYPE (EVNT_TYPE_CD, EVNT_NM, EVNT_CLSF_CD, CLCT_YN) "
                + "VALUES (?, ?, 'A', 'Y')", evntCd, CATEGORY_LABEL);

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

        // then — 동결 컬럼 null + 관제 뷰의 ORGNL_VDO_PATH_NM(V174, 구 ORIGINAL_VIDEO_PATH) 도
        //   null(관제 연동 계약 — 파생영상에는 원본 영상이 없다).
        LsDatasetVideoMeta m = txTemplate.execute(s ->
                metaRepository.findByRawSnAndActiveYn(derivativeRawSn, LsDatasetVideoMeta.ACTIVE_YES)).get(0);
        assertThat(m.getRawFilePathNm()).isNull();
        assertThat(m.getAiCrtYn()).isEqualTo("Y");

        String viewPath = jdbc.query(
                "SELECT ORGNL_VDO_PATH_NM FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?",
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
    @DisplayName("수동값_미저장_영상은_촬영환경_3필드가_모두_null로_동결된다")
    void materialize_freezesNullShootingEnvironmentWhenNoManualValue() {
        // given — 촬영환경 수동값 미입력. SHT_DT(1월 22시) 파생 추정을 하지 않는다(E-ISSUE-42, self-fill 금지).
        long rawSn = seedSource();

        // when
        txTemplate.executeWithoutResult(s -> service.materialize(rawSn));

        // then — 3필드 모두 미상(null). 관제/데이터마트가 추정값을 관측값처럼 소비하지 않는다.
        LsDatasetVideoMeta m = txTemplate.execute(s ->
                metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES)).get(0);
        assertThat(m.getWthrNm()).isNull();
        assertThat(m.getDayNgtCd()).isNull();
        assertThat(m.getSesnCd()).isNull();
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
        // ★sidoNm/sggNm 은 동결 소스가 상수 null 로 낸다(V167) — 필드는 하위호환으로 남지만 값은 없다.
        //   구 조달처 MNG_EX_LOCAL_GOV 는 실DB 0행이라 <제거 전에도 이미 항상 null> 이었고, 인입은
        //   지역명을 1필드(LCLGV_NM)로만 줘 시도/시군구 입도가 계약으로 확정되지 않았다.
        assertThat(m.getSidoNm()).isNull();
        assertThat(m.getSggNm()).isNull();
        assertThat(m.getEvntNm()).isEqualTo(CATEGORY_LABEL);   // MAP CD_TYPE='02' 라벨(수집 키워드 아님)
        assertThat(m.getVdoCdc()).isEqualTo("h264");
        assertThat(m.getFps()).isEqualByComparingTo("25");
        assertThat(m.getBitRt()).isEqualTo(4_000_000L);
        assertThat(m.getFileSz()).isEqualTo(15_000_000L);
        assertThat(m.getResl()).isEqualTo("1920x1080");
        assertThat(m.getVdoWdth()).isEqualTo(1920);
        assertThat(m.getVdoHgt()).isEqualTo(1080);
        assertThat(m.getAsprtRt()).isEqualByComparingTo("1.777778");
        assertThat(m.getDayNgtCd()).isNull();           // 수동 미입력 → 미상(22시 파생 추정 안 함)
        assertThat(m.getSesnCd()).isNull();             // 수동 미입력 → 미상(1월 파생 추정 안 함)
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
    @DisplayName("통합메타_EVNT_NM은_이벤트유형_마스터의_이벤트명이다")
    void materialize_freezesEventTypeName() {
        // given — 이벤트유형 마스터(LS_EVNT_TYPE, V168)에 이벤트명이 등록된 영상.
        //   구 구현은 관제 마스터 2종을 조인해 <카테고리명>을 동결했다(같은 카테고리의 상세 유형이
        //   모두 같은 이름으로 뭉갬). 이제는 유형 자체의 이름을 동결한다 — export JSON 의
        //   event_name 이 영상당 단일 유형 값을 요구하기 때문이다(어노테이션 계약).
        long rawSn = seedSource();

        // when
        txTemplate.executeWithoutResult(s -> service.materialize(rawSn));

        // then — 동결된 EVNT_NM 이 마스터의 이벤트명과 같다
        LsDatasetVideoMeta m = txTemplate.execute(s ->
                metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES)).get(0);
        assertThat(m.getEvntNm()).isEqualTo(CATEGORY_LABEL);

        // then — 소스는 라이브 마스터 1행이며 동결이 그 값을 바꾸지 않았다(반대 방향 확인).
        String seededName = jdbc.queryForObject(
                "SELECT et.EVNT_NM FROM LS_EVNT_TYPE et "
                        + "JOIN LS_DATA_RAW r ON r.EVNT_TYPE_CD = et.EVNT_TYPE_CD WHERE r.RAW_SN = ?",
                String.class, rawSn);
        assertThat(seededName).isEqualTo(CATEGORY_LABEL);
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

    /**
     * 관제 인입 평면값({@code LS_DATA_INGEST}) 시드 — 동결 소스가 조인해 읽는 CCTV명·좌표·파일형식.
     *
     * <p>구 시드는 {@code MNG_RESOURCE_CCTV}(VMS_CCTV_ID 축) + {@code MNG_EX_LOCAL_GOV}(LCLGV_CD 축)
     * 두 마스터였다. V167 로 두 테이블이 제거되면서 조달처가 인입 평면값 하나로 합쳐졌고,
     * <b>조인 축도 영상(RAW_SN)</b> 으로 바뀌었다.
     */
    private void seedIngestFlatValues(long rawSn, String cctvId) {
        jdbc.update("INSERT INTO LS_DATA_INGEST "
                        + "(RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, VDO_FILE_NM, RAW_FILE_PATH_NM, SRC_TYPE, "
                        + " RCPTN_DT, PRCS_STTS_CD, CCTV_NM, WGS84_LAT, WGS84_LOT, FILE_FMT, LCLGV_NM) "
                        + "VALUES (?, ?, ?, 'clip.mp4', '/nas/raw/clip.mp4', 'ORIGINAL', "
                        + "        CURRENT_TIMESTAMP, 'DONE', ?, ?, ?, 'mp4', ?)",
                rawSn, "ING-" + rawSn, cctvId, "교차로 CCTV",
                37.5665000, 126.9780000, "서울특별시 중구");
    }

}
