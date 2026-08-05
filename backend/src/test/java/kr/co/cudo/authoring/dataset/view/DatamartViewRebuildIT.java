package kr.co.cudo.authoring.dataset.view;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 데이터마트 View 계약 — <b>실 DB(PostgreSQL Testcontainer) 회귀/기능 통합 테스트</b>.
 *
 * <p><b>V174 로 계약이 교체됐다.</b> 구 계약(V95 기준 16컬럼 보존 + V101 신규 메타 18컬럼)은 관제가
 * 적재하지 않는 값을 싣고 정작 필요한 값이 없어, 규격서
 * {@code docs/관제-저작도구-데이터연동-규격서-20260805.md} §5-1 의 <b>30컬럼</b>으로 재작성했다.
 * <ol>
 *   <li>{@code V_COMPLETED_VIDEO} 출력이 규격서 30컬럼과 <b>이름·순서까지</b> 일치(관제 SELECT 계약).</li>
 *   <li>제거 대상 26컬럼이 <b>다시 살아나지 않는다</b>(되돌림 방지 — 없어야 할 것을 없다고 단언).</li>
 *   <li>유지 12컬럼이 정정된 표준 별칭(@req R6)으로 같은 값을 낸다.</li>
 *   <li>비활성 스냅샷(ACTIVE_YN='N')·미승인은 미노출(V95/V102 불변식).</li>
 *   <li>{@code V_COMPLETED_META} 는 video.* 기술메타를 제외하고 VLM/시계열 메타만 노출.</li>
 * </ol>
 *
 * <p>값 단위 계약(인입 조달·파생영상·개인정보 기본값·산출물 축)은
 * {@link V174CompletedVideoViewContractIT} 가 담당한다.
 *
 * <p>컨테이너는 {@code PostgresContainerContextCustomizerFactory} 가 자동 주입한다.
 * 시드는 공유 컨테이너 오염 방지를 위해 고유 RAW_SN 으로 넣고 {@link #cleanup()} 에서 제거한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class DatamartViewRebuildIT {

    /**
     * V174 기준 {@code V_COMPLETED_VIDEO} 의 출력 계약 — 규격서 §5-1 의 30컬럼(<b>순서 포함</b>).
     *
     * <p>(a) 영상 식별·분류 13 → 관제 {@code datasets} / (b) 버전 속성 12 → {@code dataset_versions} /
     * (c) 산출물 픽업 5.
     */
    private static final List<String> CONTRACT_VIDEO_COLUMNS = List.of(
            // (a) 영상 식별·분류 (13)
            "RAW_SN", "ORGNL_RAW_SN", "EVNT_TYPE_CD", "EVNT_CLSF_CD", "EVNT_CTGRY_CD",
            "LCLGV_CD", "LCLGV_NM", "GEN_AI_YN", "DATST_NM", "DATST_EXPLN",
            "VDO_LEN_SEC", "FRME_CNT", "RVW_CMPTN_DT",
            // (b) 버전 속성 (12)
            "IMG_YN", "VDO_YN", "ANONY_INCL_YN", "PSDO_INCL_YN", "PRVC_INCL_YN",
            "SRC_ANONY_INCL_YN", "SRC_PSDO_INCL_YN", "SRC_PRVC_INCL_YN",
            "DATA_ETBL_YR", "DATA_ETBL_CPCT", "LBL_TYPE", "LBL_FMT",
            // (c) 산출물 픽업 (5)
            "OUTPUT_PATH_NM", "OUTPUT_STTS_CD", "DE_IDNTF_FILE_PATH_NM",
            "ORGNL_VDO_PATH_NM", "DE_IDNTF_YN");

    /**
     * V174 에서 <b>제거된</b> 26컬럼 + 표준 별칭으로 정정되기 전의 구 이름 6종.
     *
     * <p>"있어야 할 것이 있다"만 단언하면 되돌림(구 컬럼 재추가)이 조용히 통과한다.
     */
    private static final List<String> REMOVED_VIDEO_COLUMNS = List.of(
            // 제거 26
            "VMS_CLIP_ID", "VMS_CCTV_ID", "CCTV_NM", "WGS84_LAT", "WGS84_LOT", "SIDO_NM", "SGG_NM",
            "FILE_FMT", "VDO_CDC", "FPS", "BIT_RT", "ASPRT_RT", "RESL", "VDO_WDTH", "VDO_HGT",
            "FILE_SZ", "DAY_NGT_CD", "SESN_CD", "WTHR_NM", "EVNT_NM", "CAPTURED_AT",
            "PRVC_TYPE_CD", "PRVC_YN", "BATCH_STTS_CD", "REVIEW_STTS_CD", "REVIEW_VERSION",
            // 별칭 정정 전 구 이름(@req R6)
            "DURATION_SEC", "FRAME_CNT", "REVIEW_COMPLETED_AT", "ORIGINAL_VIDEO_PATH",
            "EXPORT_PATH_NM", "EXPORT_STTS_CD");

    private final JdbcTemplate jdbc;
    private final List<Long> seededRawSns = new ArrayList<>();

    DatamartViewRebuildIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanup() {
        for (Long rawSn : seededRawSns) {
            jdbc.update("DELETE FROM LS_DATA_META_REVIEW WHERE DATA_RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_META WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATASET_VIDEO_META WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);
        }
    }

    /** LS_DATA_RAW + LS_RAW_DATA_STATUS(APPROVED) 라이브 소스 시드. */
    private long seedRawAndStatus(String batchStts, long ver) {
        long nano = System.nanoTime();
        Long rawSn = jdbc.queryForObject(
                "INSERT INTO LS_DATA_RAW (VMS_CLIP_ID, VMS_CCTV_ID, EVNT_TYPE_CD, LCLGV_CD, "
                        + "PRVC_TYPE_CD, PRVC_YN, DE_IDENT_YN, RAW_FILE_PATH_NM, SHT_DT, VDO_LEN_SEC, "
                        + "DATA_STTS_CD, REG_DT) "
                        + "VALUES (?, ?, 'EVT01', '1111000000', 'PRVC', 'Y', 'Y', ?, ?, 30, ?, ?) "
                        + "RETURNING RAW_SN",
                Long.class,
                "CLIP-" + nano, "CCTV-" + nano, "/nas/raw/" + nano + ".mp4",
                LocalDateTime.of(2026, 1, 15, 22, 0), batchStts, LocalDateTime.now());
        seededRawSns.add(rawSn);
        jdbc.update("INSERT INTO LS_RAW_DATA_STATUS (RAW_DATA_ID, DATA_STTS_CD, UPD_DT, VER) "
                        + "VALUES (?, 'APPROVED', ?, ?)",
                rawSn, LocalDateTime.now(), ver);
        return rawSn;
    }

    /** LS_DATASET_VIDEO_META 동결 스냅샷 1행 적재(신규 메타 컬럼 포함). */
    private void seedSnapshot(long rawSn, String hash, String activeYn) {
        jdbc.update(
                "INSERT INTO LS_DATASET_VIDEO_META (RAW_SN, SNPSHT_HASH, ACTIVE_YN, ORGNL_RAW_SN, "
                        + "VMS_CLIP_ID, VMS_CCTV_ID, RAW_FILE_PATH_NM, SHT_DT, VDO_LEN_SEC, LCLGV_CD, "
                        + "PRVC_YN, PRVC_TYPE_CD, DE_IDENT_YN, AI_CRT_YN, EVNT_TYPE_CD, "
                        + "CCTV_NM, WGS84_LAT, WGS84_LOT, SIDO_NM, SGG_NM, FILE_FMT, EVNT_NM, "
                        + "VDO_CDC, FPS, BIT_RT, ASPRT_RT, RESL, VDO_WDTH, VDO_HGT, FILE_SZ, "
                        + "DAY_NGT_CD, SESN_CD, WTHR_NM, RVW_CMPL_DT, REG_DT, REG_ID) "
                        + "VALUES (?, ?, ?, NULL, "
                        + "?, 'CCTV-X', '/nas/raw/snap.mp4', ?, 30, '1111000000', "
                        + "'Y', 'PRVC', 'Y', 'N', 'EVT01', "
                        + "'교차로 CCTV', 37.5665000, 126.9780000, '서울특별시', '중구', 'mp4', '보행자', "
                        + "'h264', 25, 4000000, 1.777778, '1920x1080', 1920, 1080, 15000000, "
                        + "'NGT', 'WINTER', '맑음', ?, ?, 'reviewer1')",
                rawSn, hash, activeYn, "CLIP-SNAP-" + rawSn,
                LocalDateTime.of(2026, 1, 15, 22, 0), LocalDateTime.of(2026, 2, 1, 10, 0),
                LocalDateTime.now());
    }

    private void seedMeta(long rawSn, String key, String value, String rvwStts) {
        Long metaSn = jdbc.queryForObject(
                "INSERT INTO LS_DATA_META (RAW_SN, META_KEY, META_VL, REG_DT) VALUES (?, ?, ?, ?) "
                        + "RETURNING META_SN",
                Long.class, rawSn, key, value, LocalDateTime.now());
        jdbc.update(
                "INSERT INTO LS_DATA_META_REVIEW (DATA_META_SN, DATA_RAW_SN, META_TYPE_CD, "
                        + "SRC_SYS_CD, RVW_STTS_CD, RVW_ID, RVW_DT) "
                        + "VALUES (?, ?, 'VLM', 'VLM', ?, 'reviewer1', ?)",
                metaSn, rawSn, rvwStts, LocalDateTime.now());
    }

    @Test
    @DisplayName("뷰_출력컬럼이_규격서_30개와_이름_순서까지_일치")
    void completedVideo_matchesSpecContractColumnsInOrder() {
        // given / when — 관제가 SELECT 하는 계약면. 이름뿐 아니라 <순서>까지 규격서 §5-1 과 맞춘다
        //   (관제가 SELECT * 로 위치 기반 매핑을 하면 순서 변경이 조용히 값을 어긋나게 한다).
        List<String> actual = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'v_completed_video' ORDER BY ordinal_position",
                String.class);

        // then — 30개, 이름·순서 정확 일치
        assertThat(actual)
                .as("규격서 §5-1 의 30컬럼 계약(이름·순서)")
                .containsExactlyElementsOf(
                        CONTRACT_VIDEO_COLUMNS.stream().map(String::toLowerCase).toList());
    }

    @Test
    @DisplayName("제거된_26컬럼이_뷰에_없다")
    void completedVideo_doesNotExposeRemovedColumns() {
        // given / when
        List<String> actual = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'v_completed_video'", String.class);

        // then — 제거분(관제 적재 대상 없음)과 별칭 정정 전 구 이름이 되살아나지 않는다.
        assertThat(actual)
                .as("V174 에서 제거·개명된 컬럼이 다시 노출됐다 — 관제 계약 되돌림")
                .doesNotContainAnyElementsOf(
                        REMOVED_VIDEO_COLUMNS.stream().map(String::toLowerCase).toList());
    }

    @Test
    @DisplayName("유지_12컬럼이_표준_별칭으로_같은_값을_낸다")
    void completedVideo_keepsRetainedColumnsUnderStandardAliases() {
        // given — 활성 스냅샷 + 라이브 APPROVED
        long rawSn = seedRawAndStatus("COMPLETED", 3L);
        seedSnapshot(rawSn, "hash-retained", "Y");

        // when — @req R6 정정 별칭으로 SELECT (하나라도 없으면 SQL 실패 = 회귀)
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT RAW_SN, ORGNL_RAW_SN, EVNT_TYPE_CD, LCLGV_CD, DE_IDNTF_YN, "
                        + "ORGNL_VDO_PATH_NM, VDO_LEN_SEC, RVW_CMPTN_DT "
                        + "FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?", rawSn);

        // then — 값·의미는 그대로이고 이름만 표준 물리명으로 바뀌었다.
        assertThat(row.get("raw_sn")).isEqualTo(rawSn);
        assertThat(row.get("orgnl_raw_sn")).isNull();
        assertThat(row.get("evnt_type_cd")).isEqualTo("EVT01");
        assertThat(row.get("lclgv_cd")).isEqualTo("1111000000");
        assertThat(row.get("de_idntf_yn")).isEqualTo("Y");
        assertThat(row.get("orgnl_vdo_path_nm")).isEqualTo("/nas/raw/snap.mp4");  // 구 ORIGINAL_VIDEO_PATH
        assertThat(row.get("vdo_len_sec")).isEqualTo(30);                          // 구 DURATION_SEC
        assertThat(row.get("rvw_cmptn_dt")).isNotNull();                           // 구 REVIEW_COMPLETED_AT
    }

    @Test
    @DisplayName("V_COMPLETED_VIDEO_ACTIVE_Y만_노출")
    void completedVideo_showsOnlyActiveSnapshots() {
        // given — 비활성 스냅샷(ACTIVE_YN='N')만 존재
        long rawSn = seedRawAndStatus("COMPLETED", 1L);
        seedSnapshot(rawSn, "hash-inactive", "N");

        // when
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?", Integer.class, rawSn);

        // then — 비활성 스냅샷은 뷰에 노출되지 않음
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("재검수중_PENDING이면_V_COMPLETED_VIDEO에_미노출")
    void completedVideo_hiddenWhenReReviewPending() {
        // given — 승인 상태 + 활성 스냅샷 → 뷰에 노출됨
        long rawSn = seedRawAndStatus("COMPLETED", 3L);
        seedSnapshot(rawSn, "hash-gate", "Y");
        Integer before = jdbc.queryForObject(
                "SELECT COUNT(*) FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?", Integer.class, rawSn);
        assertThat(before).isEqualTo(1);

        // when — 재검수 진입: 라이브 상태만 APPROVED→PENDING 으로 되돌림(스냅샷 ACTIVE_YN 은 'Y' 유지)
        jdbc.update("UPDATE LS_RAW_DATA_STATUS SET DATA_STTS_CD = 'PENDING' WHERE RAW_DATA_ID = ?", rawSn);

        // then — 활성 스냅샷이 남아있어도 라이브 상태가 APPROVED 가 아니면 뷰에서 사라진다(불변식 복원)
        Integer after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?", Integer.class, rawSn);
        assertThat(after).isZero();
    }

    @Test
    @DisplayName("재승인시_다시_노출")
    void completedVideo_reappearsOnReApproval() {
        // given — 승인+활성 스냅샷 후 재검수(PENDING) 로 뷰에서 사라진 상태
        long rawSn = seedRawAndStatus("COMPLETED", 3L);
        seedSnapshot(rawSn, "hash-reapprove", "Y");
        jdbc.update("UPDATE LS_RAW_DATA_STATUS SET DATA_STTS_CD = 'PENDING' WHERE RAW_DATA_ID = ?", rawSn);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?", Integer.class, rawSn)).isZero();

        // when — 재승인: 라이브 상태 다시 APPROVED
        jdbc.update("UPDATE LS_RAW_DATA_STATUS SET DATA_STTS_CD = 'APPROVED' WHERE RAW_DATA_ID = ?", rawSn);

        // then — 다시 노출
        Integer after = jdbc.queryForObject(
                "SELECT COUNT(*) FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?", Integer.class, rawSn);
        assertThat(after).isEqualTo(1);
    }

    @Test
    @DisplayName("V_COMPLETED_META_video기술메타_제외_VLM시계열만")
    void completedMeta_excludesVideoTechMeta_keepsVlm() {
        // given — 승인 상태 + video.* 기술메타 6종 + VLM 시계열 메타(모두 검수 APPROVED)
        long rawSn = seedRawAndStatus("COMPLETED", 1L);
        seedMeta(rawSn, "video.codec", "h264", "APPROVED");
        seedMeta(rawSn, "video.fps", "25", "APPROVED");
        seedMeta(rawSn, "video.bit_rate", "4000000", "APPROVED");
        seedMeta(rawSn, "video.duration_ms", "30000", "APPROVED");
        seedMeta(rawSn, "video.filesize", "15000000", "APPROVED");
        seedMeta(rawSn, "video.resolution", "1920x1080", "APPROVED");
        seedMeta(rawSn, "event.summary", "보행자 3명 횡단", "APPROVED");
        seedMeta(rawSn, "vlm.caption", "야간 교차로 보행자", "APPROVED");

        // when
        List<String> keys = jdbc.queryForList(
                "SELECT META_KEY FROM V_COMPLETED_META WHERE RAW_SN = ?", String.class, rawSn);

        // then — video.* 6종은 제외, VLM/시계열 메타만 노출
        assertThat(keys).containsExactlyInAnyOrder("event.summary", "vlm.caption");
        assertThat(keys).noneMatch(k -> k.startsWith("video."));
    }
}
