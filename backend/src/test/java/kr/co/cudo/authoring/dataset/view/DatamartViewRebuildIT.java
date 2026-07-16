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
 * Phase 4 데이터마트 View 재구성 — <b>실 DB(PostgreSQL Testcontainer) 회귀/기능 통합 테스트</b>.
 *
 * <p>V101 마이그레이션으로 재정의된 두 View 를 실 DB SELECT 로 검증한다.
 * <ol>
 *   <li>{@code V_COMPLETED_VIDEO} 기존 출력 16컬럼(계약)이 재정의 후에도 동일 이름으로 SELECT 가능
 *       (관제 소비자 쿼리 회귀 0).</li>
 *   <li>신규 메타 컬럼(CCTV_NM·WGS84_LAT·VDO_CDC·FPS·RESL 등)이 통합 스냅샷 값으로 노출.</li>
 *   <li>비활성 스냅샷(ACTIVE_YN='N')은 미노출.</li>
 *   <li>{@code V_COMPLETED_META} 는 video.* 기술메타를 제외하고 VLM/시계열 메타만 노출.</li>
 * </ol>
 *
 * <p>컨테이너는 {@code PostgresContainerContextCustomizerFactory} 가 자동 주입한다.
 * 시드는 공유 컨테이너 오염 방지를 위해 고유 RAW_SN 으로 넣고 {@link #cleanup()} 에서 제거한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class DatamartViewRebuildIT {

    /** V95 기준 V_COMPLETED_VIDEO 의 기존 출력 컬럼(계약) — 이름·순서 보존 대상. */
    private static final List<String> LEGACY_VIDEO_COLUMNS = List.of(
            "RAW_SN", "VMS_CLIP_ID", "VMS_CCTV_ID", "EVNT_TYPE_CD", "LCLGV_CD",
            "PRVC_TYPE_CD", "PRVC_YN", "DE_IDNTF_YN", "ORIGINAL_VIDEO_PATH", "CAPTURED_AT",
            "DURATION_SEC", "ORGNL_RAW_SN", "BATCH_STTS_CD", "REVIEW_STTS_CD",
            "REVIEW_COMPLETED_AT", "REVIEW_VERSION");

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
    @DisplayName("V_COMPLETED_VIDEO_기존_출력컬럼_전부_보존")
    void completedVideo_preservesLegacyContractColumns() {
        // given — 활성 스냅샷 + 라이브 상태(APPROVED, VER=3, BATCH=COMPLETED)
        long rawSn = seedRawAndStatus("COMPLETED", 3L);
        seedSnapshot(rawSn, "hash-legacy", "Y");

        // when — 기존 16개 계약 컬럼을 명시적으로 SELECT (하나라도 없으면 SQL 실패 = 회귀)
        String cols = String.join(", ", LEGACY_VIDEO_COLUMNS);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT " + cols + " FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?", rawSn);

        // then — 16개 컬럼 모두 존재 + 기존 매핑값(라이브 상태/버전 포함) 유지
        assertThat(row.keySet()).containsAll(
                LEGACY_VIDEO_COLUMNS.stream().map(String::toLowerCase).toList());
        assertThat(row.get("raw_sn")).isEqualTo(rawSn);
        assertThat(row.get("de_idntf_yn")).isEqualTo("Y");
        assertThat(row.get("original_video_path")).isEqualTo("/nas/raw/snap.mp4");
        assertThat(row.get("duration_sec")).isEqualTo(30);
        assertThat(row.get("batch_stts_cd")).isEqualTo("COMPLETED");   // 라이브(LS_DATA_RAW)
        assertThat(row.get("review_stts_cd")).isEqualTo("APPROVED");   // 라이브(LS_RAW_DATA_STATUS)
        assertThat(((Number) row.get("review_version")).longValue()).isEqualTo(3L);
        assertThat(row.get("review_completed_at")).isNotNull();
    }

    @Test
    @DisplayName("V_COMPLETED_VIDEO_신규_메타컬럼_노출")
    void completedVideo_exposesNewMetaColumns() {
        // given
        long rawSn = seedRawAndStatus("COMPLETED", 1L);
        seedSnapshot(rawSn, "hash-newmeta", "Y");

        // when — 신규 메타 컬럼 SELECT
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT CCTV_NM, WGS84_LAT, WGS84_LOT, SIDO_NM, SGG_NM, FILE_FMT, EVNT_NM, "
                        + "VDO_CDC, FPS, BIT_RT, ASPRT_RT, RESL, VDO_WDTH, VDO_HGT, FILE_SZ, "
                        + "DAY_NGT_CD, SESN_CD, WTHR_NM "
                        + "FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?", rawSn);

        // then — 통합 스냅샷 값으로 노출
        assertThat(row.get("cctv_nm")).isEqualTo("교차로 CCTV");
        assertThat(((Number) row.get("wgs84_lat")).doubleValue()).isEqualTo(37.5665000);
        assertThat(((Number) row.get("wgs84_lot")).doubleValue()).isEqualTo(126.9780000);
        assertThat(row.get("sido_nm")).isEqualTo("서울특별시");
        assertThat(row.get("sgg_nm")).isEqualTo("중구");
        assertThat(row.get("evnt_nm")).isEqualTo("보행자");
        assertThat(row.get("vdo_cdc")).isEqualTo("h264");
        assertThat(((Number) row.get("fps")).intValue()).isEqualTo(25);
        assertThat(((Number) row.get("bit_rt")).longValue()).isEqualTo(4000000L);
        assertThat(row.get("resl")).isEqualTo("1920x1080");
        assertThat(((Number) row.get("vdo_wdth")).intValue()).isEqualTo(1920);
        assertThat(((Number) row.get("vdo_hgt")).intValue()).isEqualTo(1080);
        assertThat(((Number) row.get("file_sz")).longValue()).isEqualTo(15000000L);
        assertThat(row.get("day_ngt_cd")).isEqualTo("NGT");
        assertThat(row.get("sesn_cd")).isEqualTo("WINTER");
        assertThat(row.get("wthr_nm")).isEqualTo("맑음");
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
