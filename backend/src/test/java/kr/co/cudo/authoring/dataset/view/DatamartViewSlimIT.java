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
 * V114 데이터마트 View 슬림화 — <b>실 DB(PostgreSQL Testcontainer) 회귀/기능 통합 테스트</b>.
 *
 * <p>검수 승인 시 export 폴더(JSON+이미지)에 라벨 내용이 이미 산출되므로, 데이터마트에서
 * 라벨 내용 뷰(V_COMPLETED_LABEL / V_COMPLETED_LABEL_ATTR)를 제거하고 관제서버가
 * ① export 폴더 경로 + ② 변경점·메타만 DB 뷰로 쿼리하도록 슬림화한 마이그레이션(V114)을 검증한다.
 * <ol>
 *   <li>라벨 내용 뷰 2종 제거 (부재 확인).</li>
 *   <li>{@code V_COMPLETED_VIDEO} 에 EXPORT_PATH_NM/FRAME_CNT 노출 + APPROVED 영상당 1 row(행 증식 0).</li>
 *   <li>미export 영상은 EXPORT_PATH_NM null 이어도 VIDEO 뷰에 노출(LEFT JOIN 보존).</li>
 *   <li>신설 {@code V_COMPLETED_LABEL_CHANGE} 가 APPROVED 영상의 저장이벤트(종류별 건수 + diff)만 반환.</li>
 *   <li>{@code V_COMPLETED_FRAME} / {@code V_COMPLETED_META} 무영향.</li>
 * </ol>
 *
 * <p>컨테이너는 {@code PostgresContainerContextCustomizerFactory} 가 자동 주입한다.
 * 시드는 공유 컨테이너 오염 방지를 위해 고유 RAW_SN 으로 넣고 {@link #cleanup()} 에서 시드 역순 제거한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class DatamartViewSlimIT {

    private final JdbcTemplate jdbc;
    private final List<Long> seededRawSns = new ArrayList<>();

    DatamartViewSlimIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanup() {
        for (Long rawSn : seededRawSns) {
            jdbc.update("DELETE FROM LS_DATASET_EXPORT WHERE DATA_RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_LBL_HSTRY WHERE SRC_SN IN "
                    + "(SELECT SRC_SN FROM LS_DATA_SRC WHERE RAW_SN = ?)", rawSn);
            jdbc.update("DELETE FROM LS_DATA_META_REVIEW WHERE DATA_RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_META WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_LBL WHERE SRC_SN IN "
                    + "(SELECT SRC_SN FROM LS_DATA_SRC WHERE RAW_SN = ?)", rawSn);
            jdbc.update("DELETE FROM LS_DATA_SRC WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATASET_VIDEO_META WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);
        }
    }

    /** LS_DATA_RAW + LS_RAW_DATA_STATUS(reviewStts) 라이브 소스 시드. */
    private long seedRawAndStatus(String reviewStts) {
        long nano = System.nanoTime();
        Long rawSn = jdbc.queryForObject(
                "INSERT INTO LS_DATA_RAW (VMS_CLIP_ID, VMS_CCTV_ID, EVNT_TYPE_CD, LCLGV_CD, "
                        + "PRVC_TYPE_CD, PRVC_YN, DE_IDENT_YN, RAW_FILE_PATH_NM, SHT_DT, VDO_LEN_SEC, "
                        + "DATA_STTS_CD, REG_DT) "
                        + "VALUES (?, ?, 'EVT01', '1111000000', 'PRVC', 'Y', 'Y', ?, ?, 30, 'COMPLETED', ?) "
                        + "RETURNING RAW_SN",
                Long.class,
                "CLIP-" + nano, "CCTV-" + nano, "/nas/raw/" + nano + ".mp4",
                LocalDateTime.of(2026, 1, 15, 22, 0), LocalDateTime.now());
        seededRawSns.add(rawSn);
        jdbc.update("INSERT INTO LS_RAW_DATA_STATUS (RAW_DATA_ID, DATA_STTS_CD, UPD_DT, VER) "
                        + "VALUES (?, ?, ?, 1)",
                rawSn, reviewStts, LocalDateTime.now());
        return rawSn;
    }

    /** LS_DATASET_VIDEO_META 활성 스냅샷 1행(V_COMPLETED_VIDEO 노출 조건). */
    private void seedSnapshot(long rawSn) {
        jdbc.update(
                "INSERT INTO LS_DATASET_VIDEO_META (RAW_SN, SNPSHT_HASH, ACTIVE_YN, RAW_FILE_PATH_NM, "
                        + "SHT_DT, VDO_LEN_SEC, DE_IDENT_YN, REG_DT) "
                        + "VALUES (?, ?, 'Y', '/nas/raw/snap.mp4', ?, 30, 'Y', ?)",
                rawSn, "hash-" + rawSn, LocalDateTime.of(2026, 1, 15, 22, 0), LocalDateTime.now());
    }

    private void seedExport(long rawSn, int verNo, String pathNm, String sttsCd, int frameCnt) {
        jdbc.update(
                "INSERT INTO LS_DATASET_EXPORT (DATA_RAW_SN, EXPORT_VER_NO, EXPORT_PATH_NM, "
                        + "EXPORT_STTS_CD, FRAME_CNT, REG_DT) VALUES (?, ?, ?, ?, ?, ?)",
                rawSn, verNo, pathNm, sttsCd, frameCnt, LocalDateTime.now());
    }

    private long seedFrame(long rawSn, int frameNo) {
        return jdbc.queryForObject(
                "INSERT INTO LS_DATA_SRC (RAW_SN, FRM_NO, SRC_FILE_PATH_NM, "
                        + "DE_IDNTF_SRC_FILE_PATH_NM, SHT_DT, REG_DT) "
                        + "VALUES (?, ?, ?, ?, ?, ?) RETURNING SRC_SN",
                Long.class,
                rawSn, frameNo, "/nas/frames/raw/" + rawSn + "/" + frameNo + ".jpg",
                "/nas/frames/deid/" + rawSn + "/" + frameNo + ".jpg",
                LocalDateTime.of(2026, 1, 15, 22, 0), LocalDateTime.now());
    }

    /** LS_DATA_LBL_HSTRY 저장이벤트 1행 시드(V114 재구조화 스키마 — 종류별 건수 + diff 페이로드). */
    private void seedSaveEvent(long srcSn, int addCnt, int mdfcnCnt, int delCnt,
                              String chgDtlCn, String regId) {
        jdbc.update(
                "INSERT INTO LS_DATA_LBL_HSTRY (SRC_SN, REG_DT, REG_ID, ADD_CNT, MDFCN_CNT, DEL_CNT, CHG_DTL_CN) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                srcSn, LocalDateTime.now(), regId, addCnt, mdfcnCnt, delCnt, chgDtlCn);
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

    // ------------------------------------------------------------------------

    @Test
    @DisplayName("V_COMPLETED_LABEL과_LABEL_ATTR_뷰가_제거되어_존재하지_않는다")
    void labelViews_areDropped() {
        // when — to_regclass 로 뷰 존재 여부 확인(존재하면 OID, 없으면 NULL)
        Object labelReg = jdbc.queryForMap("SELECT to_regclass('v_completed_label') AS r").get("r");
        Object attrReg = jdbc.queryForMap("SELECT to_regclass('v_completed_label_attr') AS r").get("r");

        // then — 두 뷰 모두 부재(NULL)
        assertThat(labelReg).isNull();
        assertThat(attrReg).isNull();
    }

    @Test
    @DisplayName("V_COMPLETED_VIDEO에_EXPORT_PATH_NM이_노출되고_APPROVED영상당_1row다")
    void completedVideo_exposesExportPath_singleRowPerVideo() {
        // given — APPROVED 영상 + 활성 스냅샷 + export 2버전(모두 SUCCEEDED) + 최신 PENDING 1건
        long rawSn = seedRawAndStatus("APPROVED");
        seedSnapshot(rawSn);
        seedExport(rawSn, 1, "/labeling/" + rawSn + "/v1", "SUCCEEDED", 100);
        seedExport(rawSn, 2, "/labeling/" + rawSn + "/v2", "SUCCEEDED", 120);
        seedExport(rawSn, 3, "/labeling/" + rawSn + "/v3", "PENDING", 130); // 진행중 — 선택 제외 대상

        // when
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT EXPORT_PATH_NM, FRAME_CNT FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?", rawSn);

        // then — export 2버전이 있어도 영상 1 row(LATERAL LIMIT 1) + 최신 SUCCEEDED(v2) 값
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("export_path_nm")).isEqualTo("/labeling/" + rawSn + "/v2");
        assertThat(((Number) rows.get(0).get("frame_cnt")).intValue()).isEqualTo(120);
    }

    @Test
    @DisplayName("미export_영상은_EXPORT_PATH_NM이_null이어도_VIDEO에_노출된다")
    void completedVideo_showsVideoEvenWithoutExport() {
        // given — APPROVED 영상 + 활성 스냅샷, export 없음
        long rawSn = seedRawAndStatus("APPROVED");
        seedSnapshot(rawSn);

        // when
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT EXPORT_PATH_NM, FRAME_CNT FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?", rawSn);

        // then — LEFT JOIN 보존: 영상은 노출되고 export 두 값은 null
        assertThat(row.get("export_path_nm")).isNull();
        assertThat(row.get("frame_cnt")).isNull();
    }

    @Test
    @DisplayName("V_COMPLETED_LABEL_CHANGE가_APPROVED영상의_저장이벤트를_반환한다")
    void labelChange_returnsChangesForApprovedVideo() {
        // given — APPROVED 영상 + 프레임 + 저장이벤트 2건(각각 add/mdfcn/del 카운트 상이 + diff)
        long rawSn = seedRawAndStatus("APPROVED");
        long srcSn = seedFrame(rawSn, 10);
        seedSaveEvent(srcSn, 2, 1, 0, "[{\"kind\":\"ADDED\"}]", "worker1");
        seedSaveEvent(srcSn, 0, 0, 3, "[{\"kind\":\"DELETED\"}]", "worker2");

        // when
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT RAW_SN, SRC_SN, ADD_CNT, MDFCN_CNT, DEL_CNT, CHG_DTL_CN, REG_ID, REG_DT "
                        + "FROM V_COMPLETED_LABEL_CHANGE WHERE SRC_SN = ? ORDER BY DEL_CNT", srcSn);

        // then — 저장이벤트 2건 반환 + RAW_SN 조인 정확 + 카운트/diff/REG_ID/시각 노출
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(((Number) r.get("raw_sn")).longValue()).isEqualTo(rawSn);
            assertThat(((Number) r.get("src_sn")).longValue()).isEqualTo(srcSn);
            assertThat(r.get("reg_dt")).isNotNull();
        });
        // 첫 행: worker1 저장이벤트(add=2, mdfcn=1, del=0)
        Map<String, Object> first = rows.get(0);
        assertThat(((Number) first.get("add_cnt")).intValue()).isEqualTo(2);
        assertThat(((Number) first.get("mdfcn_cnt")).intValue()).isEqualTo(1);
        assertThat(((Number) first.get("del_cnt")).intValue()).isZero();
        assertThat(first.get("chg_dtl_cn")).isEqualTo("[{\"kind\":\"ADDED\"}]");
        assertThat(first.get("reg_id")).isEqualTo("worker1");
        // 둘째 행: worker2 저장이벤트(add=0, mdfcn=0, del=3)
        Map<String, Object> second = rows.get(1);
        assertThat(((Number) second.get("add_cnt")).intValue()).isZero();
        assertThat(((Number) second.get("del_cnt")).intValue()).isEqualTo(3);
        assertThat(second.get("chg_dtl_cn")).isEqualTo("[{\"kind\":\"DELETED\"}]");
        assertThat(second.get("reg_id")).isEqualTo("worker2");
    }

    @Test
    @DisplayName("비APPROVED영상의_저장이벤트는_LABEL_CHANGE에_안나온다")
    void labelChange_hiddenWhenNotApproved() {
        // given — PENDING(미승인) 영상의 저장이벤트
        long rawSn = seedRawAndStatus("PENDING");
        long srcSn = seedFrame(rawSn, 1);
        seedSaveEvent(srcSn, 1, 0, 0, "[{\"kind\":\"ADDED\"}]", "worker1");

        // when
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM V_COMPLETED_LABEL_CHANGE WHERE SRC_SN = ?", Integer.class, srcSn);

        // then — APPROVED 게이트로 미노출
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("V_COMPLETED_FRAME·V_COMPLETED_META는_무영향이다")
    void frameAndMeta_unaffected() {
        // given — APPROVED 영상 + 프레임 + 승인 메타
        long rawSn = seedRawAndStatus("APPROVED");
        long srcSn = seedFrame(rawSn, 5);
        seedMeta(rawSn, "vlm.caption", "야간 교차로 보행자", "APPROVED");

        // when — 프레임 페어/시계열 메타 정상 조회
        Map<String, Object> frame = jdbc.queryForMap(
                "SELECT SRC_SN, RAW_SN, ORIGINAL_PATH, DEIDENTIFIED_PATH "
                        + "FROM V_COMPLETED_FRAME WHERE SRC_SN = ?", srcSn);
        List<String> metaKeys = jdbc.queryForList(
                "SELECT META_KEY FROM V_COMPLETED_META WHERE RAW_SN = ?", String.class, rawSn);

        // then — 컬럼/값 정상(V114 무영향)
        assertThat(((Number) frame.get("raw_sn")).longValue()).isEqualTo(rawSn);
        assertThat(frame.get("original_path")).isEqualTo("/nas/frames/raw/" + rawSn + "/5.jpg");
        assertThat(metaKeys).containsExactly("vlm.caption");
    }
}
