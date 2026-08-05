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
 *   <li>신설 {@code V_COMPLETED_LABEL_CHANGE} 가 APPROVED 영상의 저장이벤트(종류별 건수)만 반환하고
 *       라벨 좌표 본문(diff {@code CHG_DTL_CN})은 노출하지 않는다(D-ISSUE-47 / V137).</li>
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
            jdbc.update("DELETE FROM LS_DEIDENT_PROC_LOG WHERE DATA_RAW_SN = ?", rawSn);
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

    /**
     * 산출 원장 1행 시드.
     *
     * <p>⚠ INSERT 는 <b>V173 표준 물리명</b>(OUTPUT_VER_NO / OUTPUT_PATH_NM / OUTPUT_STTS_CD /
     * FRME_CNT)을 쓰지만, 아래 <b>뷰 SELECT 는 구 이름</b>(EXPORT_PATH_NM / FRAME_CNT /
     * EXPORT_STTS_CD)을 그대로 쓴다 — 모순이 아니다. PostgreSQL 은 RENAME COLUMN 시 뷰 <b>본문</b>만
     * 새 컬럼으로 추종하고 <b>출력 컬럼명은 자동 별칭으로 보존</b>하기 때문이다
     * (V160 본문 {@code e.EXPORT_PATH_NM} → {@code e.OUTPUT_PATH_NM AS export_path_nm}).
     * 즉 V173 은 관제 연동 계약면(뷰 출력명)을 건드리지 않았다.
     */
    private void seedExport(long rawSn, int verNo, String pathNm, String sttsCd, int frameCnt) {
        jdbc.update(
                "INSERT INTO LS_DATASET_EXPORT (DATA_RAW_SN, OUTPUT_VER_NO, OUTPUT_PATH_NM, "
                        + "OUTPUT_STTS_CD, FRME_CNT, REG_DT) VALUES (?, ?, ?, ?, ?, ?)",
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

    /**
     * LS_DEIDENT_PROC_LOG 1행 — V138 {@code DE_IDNTF_FILE_PATH_NM} 노출 검증용.
     * 파일명은 외부(mock/KPST)가 정하므로 <b>적재값 그대로</b> 노출되는지 확인한다.
     */
    private void seedDeidProcLog(long rawSn, String sttsCd, String deidFilePath, LocalDateTime reqDt) {
        jdbc.update(
                "INSERT INTO LS_DEIDENT_PROC_LOG (DATA_RAW_SN, ORGNL_FILE_PATH_NM, DE_IDNTF_FILE_PATH_NM, "
                        + "PROC_STTS_CD, REQ_DT, REG_DT) VALUES (?, ?, ?, ?, ?, ?)",
                rawSn, "/nas/raw/" + rawSn + ".mp4", deidFilePath, sttsCd, reqDt, LocalDateTime.now());
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

    // ── V160 / E-ISSUE-81 — PARTIAL export 도 뷰에 노출된다(관제 동기화 사각지대 제거) ──────────

    @Test
    @DisplayName("V160_최초export가_PARTIAL이어도_EXPORT_PATH_NM이_노출된다 — 통지된 산출은 반드시 뷰에서 보인다")
    void completedVideo_exposesPartialExport() {
        // given — 원천 이미지 일부 부재로 최초 export 가 PARTIAL 로 마감된 APPROVED 영상.
        //   구 뷰는 SUCCEEDED 만 조인해 산출 경로/프레임수가 NULL 이었고, 회수기도 FAILED 만
        //   앵커로 삼아 재산출되지 않아 관제가 <영구 미동기화> 상태였다(디스크엔 산출물 실재).
        long rawSn = seedRawAndStatus("APPROVED");
        seedSnapshot(rawSn);
        seedExport(rawSn, 1, "/labeling/" + rawSn, "PARTIAL", 10);

        // when
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT EXPORT_PATH_NM, FRAME_CNT, EXPORT_STTS_CD FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?",
                rawSn);

        // then — 경로/프레임수가 채워지고, 부분 산출임을 관제가 식별할 수 있다.
        assertThat(row.get("export_path_nm")).isEqualTo("/labeling/" + rawSn);
        assertThat(((Number) row.get("frame_cnt")).intValue()).isEqualTo(10);
        assertThat(row.get("export_stts_cd")).isEqualTo("PARTIAL");
    }

    @Test
    @DisplayName("V160_최신이_PARTIAL이면_구_SUCCEEDED가_아니라_최신_PARTIAL을_노출한다")
    void completedVideo_prefersLatestPartialOverOlderSucceeded() {
        // given — v1 성공 후 v2 가 부분 산출. 통지는 v2 기준으로 나갔으므로 관제는 v2 를 봐야 한다.
        //   구 뷰는 v1(구 라벨)의 프레임수를 돌려줘 "수정했다"는 통지와 값이 어긋났다.
        long rawSn = seedRawAndStatus("APPROVED");
        seedSnapshot(rawSn);
        seedExport(rawSn, 1, "/labeling/" + rawSn, "SUCCEEDED", 100);
        seedExport(rawSn, 2, "/labeling/" + rawSn, "PARTIAL", 98);

        // when
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT FRAME_CNT, EXPORT_STTS_CD FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?", rawSn);

        // then — 영상 1 row 유지 + 최신 버전(v2) 선택
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("frame_cnt")).intValue()).isEqualTo(98);
        assertThat(rows.get(0).get("export_stts_cd")).isEqualTo("PARTIAL");
    }

    @Test
    @DisplayName("V160_FAILED_PENDING은_여전히_뷰에서_배제된다 — 산출물 없는 상태를 최신으로 오인 금지")
    void completedVideo_stillExcludesFailedAndPending() {
        // given — v1 성공 후 v2 실패 + v3 진행중. 산출물이 실재하는 최신은 여전히 v1 이다.
        long rawSn = seedRawAndStatus("APPROVED");
        seedSnapshot(rawSn);
        seedExport(rawSn, 1, "/labeling/" + rawSn, "SUCCEEDED", 100);
        seedExport(rawSn, 2, "/labeling/" + rawSn, "FAILED", 0);
        seedExport(rawSn, 3, "/labeling/" + rawSn, "PENDING", 0);

        // when
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT FRAME_CNT, EXPORT_STTS_CD FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?", rawSn);

        // then — PARTIAL 확장이 FAILED/PENDING 까지 열어주지 않았다.
        assertThat(((Number) row.get("frame_cnt")).intValue()).isEqualTo(100);
        assertThat(row.get("export_stts_cd")).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("V138_V_COMPLETED_VIDEO가_비식별영상경로를_적재값_그대로_노출한다 — KPST명 조합 금지")
    void completedVideo_exposesDeidVideoPathAsStored() {
        // given — KPST 실연동 산출명({원본stem}-mask{ext}) 은 영상마다 다르다. 뷰는 이 값을 가공 없이 실어야 한다.
        long rawSn = seedRawAndStatus("APPROVED");
        seedSnapshot(rawSn);
        String kpstPath = "/nas/raw/" + rawSn + "/deid/" + rawSn + "-mask.mp4";
        seedDeidProcLog(rawSn, "SUCCEEDED", kpstPath, LocalDateTime.now());

        // when
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT DE_IDNTF_FILE_PATH_NM FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?", rawSn);

        // then — 적재된 절대경로 원문 그대로(치환·조합 없음)
        assertThat(row.get("de_idntf_file_path_nm")).isEqualTo(kpstPath);
    }

    @Test
    @DisplayName("V138_mock명과_KPST명이_섞여도_각_영상의_적재값이_그대로_나온다")
    void completedVideo_deidPathHandlesMixedNamingConventions() {
        // given — 같은 데이터마트에 mock 산출(고정명)과 KPST 산출(파생명)이 공존
        long mockSn = seedRawAndStatus("APPROVED");
        seedSnapshot(mockSn);
        String mockPath = "/nas/raw/" + mockSn + "/deid/deidentified.mp4";
        seedDeidProcLog(mockSn, "SUCCEEDED", mockPath, LocalDateTime.now());

        long kpstSn = seedRawAndStatus("APPROVED");
        seedSnapshot(kpstSn);
        String kpstPath = "/nas/raw/" + kpstSn + "/deid/clip-" + kpstSn + "-mask.mp4";
        seedDeidProcLog(kpstSn, "SUCCEEDED", kpstPath, LocalDateTime.now());

        // when / then — 각 행이 자기 적재값을 그대로 노출한다.
        assertThat(jdbc.queryForMap(
                "SELECT DE_IDNTF_FILE_PATH_NM FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?", mockSn)
                .get("de_idntf_file_path_nm")).isEqualTo(mockPath);
        assertThat(jdbc.queryForMap(
                "SELECT DE_IDNTF_FILE_PATH_NM FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?", kpstSn)
                .get("de_idntf_file_path_nm")).isEqualTo(kpstPath);
    }

    @Test
    @DisplayName("V138_재비식별로_procLog가_누적돼도_영상당_1row이고_최신_SUCCEEDED가_선택된다")
    void completedVideo_deidPathPicksLatestSuccessSingleRow() {
        // given — 실패 1건 + 성공 2건(구/신) + 경로 null 성공 1건이 누적된 재비식별 이력
        long rawSn = seedRawAndStatus("APPROVED");
        seedSnapshot(rawSn);
        String oldPath = "/nas/deidentified/videos/" + rawSn + "/deidentified.mp4";
        String newPath = "/nas/raw/" + rawSn + "/deid/clip-mask.mp4";
        seedDeidProcLog(rawSn, "SUCCEEDED", oldPath, LocalDateTime.now().minusDays(2));
        seedDeidProcLog(rawSn, "FAILED", null, LocalDateTime.now().minusDays(1));
        seedDeidProcLog(rawSn, "SUCCEEDED", newPath, LocalDateTime.now());

        // when
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT DE_IDNTF_FILE_PATH_NM FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?", rawSn);

        // then — 행 증식 없음 + 최신 성공분
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("de_idntf_file_path_nm")).isEqualTo(newPath);
    }

    @Test
    @DisplayName("V138_비식별_성공이력이_없으면_경로는_null이고_영상행은_보존된다")
    void completedVideo_deidPathNullWhenNoSuccess() {
        // given — 성공 procLog 없음(요청/실패만)
        long rawSn = seedRawAndStatus("APPROVED");
        seedSnapshot(rawSn);
        seedDeidProcLog(rawSn, "FAILED", null, LocalDateTime.now());

        // when
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT RAW_SN, DE_IDNTF_FILE_PATH_NM FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?", rawSn);

        // then — 영상 행은 남고 경로만 null(LEFT JOIN LATERAL 보존)
        assertThat(((Number) row.get("raw_sn")).longValue()).isEqualTo(rawSn);
        assertThat(row.get("de_idntf_file_path_nm")).isNull();
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
                "SELECT RAW_SN, SRC_SN, ADD_CNT, MDFCN_CNT, DEL_CNT, REG_ID, REG_DT "
                        + "FROM V_COMPLETED_LABEL_CHANGE WHERE SRC_SN = ? ORDER BY DEL_CNT", srcSn);

        // then — 저장이벤트 2건 반환 + RAW_SN 조인 정확 + 카운트/REG_ID/시각 노출
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
        assertThat(first.get("reg_id")).isEqualTo("worker1");
        // 둘째 행: worker2 저장이벤트(add=0, mdfcn=0, del=3)
        Map<String, Object> second = rows.get(1);
        assertThat(((Number) second.get("add_cnt")).intValue()).isZero();
        assertThat(((Number) second.get("del_cnt")).intValue()).isEqualTo(3);
        assertThat(second.get("reg_id")).isEqualTo("worker2");
    }

    @Test
    @DisplayName("V_COMPLETED_LABEL_CHANGE에_라벨_좌표_본문이_노출되지_않는다")
    void labelChange_doesNotExposeLabelBody() {
        // given — diff(CHG_DTL_CN)에는 before/after 라벨 전체 스냅샷(좌표 pointCn)이 들어 있다.
        long rawSn = seedRawAndStatus("APPROVED");
        long srcSn = seedFrame(rawSn, 11);
        seedSaveEvent(srcSn, 1, 0, 0,
                "[{\"kind\":\"ADDED\",\"after\":{\"pointCn\":\"[[10,10],[50,50]]\"}}]", "worker1");

        // when — 뷰 컬럼 목록 자체를 조회한다(D-ISSUE-47 / V137).
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM V_COMPLETED_LABEL_CHANGE WHERE SRC_SN = ?", srcSn);

        // then — 변경 사실·건수만 노출. 라벨 본문(diff JSON) 컬럼은 뷰에 존재하지 않는다.
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).keySet())
                .containsExactlyInAnyOrder("lbl_hstry_sn", "raw_sn", "src_sn",
                        "add_cnt", "mdfcn_cnt", "del_cnt", "reg_id", "reg_dt");
        assertThat(rows.get(0)).doesNotContainKey("chg_dtl_cn");
        // 원 테이블에는 감사·복구 근거로 그대로 남아 있어야 한다(노출면만 좁힌 것).
        String stored = jdbc.queryForObject(
                "SELECT CHG_DTL_CN FROM LS_DATA_LBL_HSTRY WHERE SRC_SN = ?", String.class, srcSn);
        assertThat(stored).contains("pointCn");
    }

    @Test
    @DisplayName("변경_0건_이력은_V_COMPLETED_LABEL_CHANGE_에_노출되지_않는다")
    void labelChange_excludesZeroChangeRows() {
        // given — APPROVED 영상의 프레임에 ①롤백 이벤트(라벨 델타 0건, 봉투 JSON) ②개인정보 메타 리셋
        //   감사(0건) ③실제 변경 1건이 섞여 있다. ①②는 LS_DATA_LBL_HSTRY 설계상 0/0/0 으로 기록된다.
        long rawSn = seedRawAndStatus("APPROVED");
        long srcSn = seedFrame(rawSn, 12);
        seedSaveEvent(srcSn, 0, 0, 0,
                "{\"rollbackToVersionHash\":\"abc\",\"changes\":[]}", "reviewer1");
        seedSaveEvent(srcSn, 0, 0, 0,
                "{\"event\":\"PRIVACY_META_RESET\",\"deidentReportSn\":7,\"changes\":[]}", "worker1");
        seedSaveEvent(srcSn, 1, 0, 0, "[{\"kind\":\"ADDED\"}]", "worker1");

        // when
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT ADD_CNT, MDFCN_CNT, DEL_CNT FROM V_COMPLETED_LABEL_CHANGE WHERE SRC_SN = ?", srcSn);

        // then — V139: 관제가 "변경 없는 변경점"(팬텀 0/0/0 행)을 픽업하지 않는다. 실제 변경 1건만 노출.
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("add_cnt")).intValue()).isEqualTo(1);
        // 원 테이블에는 감사 근거로 3건 모두 남는다(노출면만 좁힌 것).
        Integer stored = jdbc.queryForObject(
                "SELECT COUNT(*) FROM LS_DATA_LBL_HSTRY WHERE SRC_SN = ?", Integer.class, srcSn);
        assertThat(stored).isEqualTo(3);
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

    // ---------- V133 — V_COMPLETED_FRAME 비식별 경로 불변식 게이트 (D-ISSUE-46) ----------

    /** 임의 원본/비식별 경로로 프레임 1행 시드(게이트 검증용). */
    private long seedFramePaths(long rawSn, int frameNo, String originalPath, String deidPath) {
        return jdbc.queryForObject(
                "INSERT INTO LS_DATA_SRC (RAW_SN, FRM_NO, SRC_FILE_PATH_NM, "
                        + "DE_IDNTF_SRC_FILE_PATH_NM, SHT_DT, REG_DT) "
                        + "VALUES (?, ?, ?, ?, ?, ?) RETURNING SRC_SN",
                Long.class, rawSn, frameNo, originalPath, deidPath,
                LocalDateTime.of(2026, 1, 15, 22, 0), LocalDateTime.now());
    }

    @Test
    @DisplayName("V_COMPLETED_FRAME_이_원본경로를_비식별컬럼에_노출하지_않음")
    void completedFrame_gatesRowsWhereDeidEqualsOriginal() {
        // given — APPROVED 영상. ①정상 페어 ②원본==비식별(파생 결함 형태) ③비식별 NULL(결측)
        long rawSn = seedRawAndStatus("APPROVED");
        long okSrcSn = seedFramePaths(rawSn, 30,
                "/nas/frames/raw/" + rawSn + "/30.jpg", "/nas/frames/deid/" + rawSn + "/30.jpg");
        long sameSrcSn = seedFramePaths(rawSn, 31,
                "/nas/resolution/" + rawSn + "/frames/31.jpg", "/nas/resolution/" + rawSn + "/frames/31.jpg");
        long nullSrcSn = seedFramePaths(rawSn, 32, "/nas/frames/raw/" + rawSn + "/32.jpg", null);

        // when
        List<Long> visible = jdbc.queryForList(
                "SELECT SRC_SN FROM V_COMPLETED_FRAME WHERE RAW_SN = ? ORDER BY SRC_SN", Long.class, rawSn);

        // then — 게이트는 <결함 형태>(원본==비식별)만 배제한다(M-1).
        assertThat(visible).contains(okSrcSn);
        assertThat(visible).doesNotContain(sameSrcSn);
        // 비식별 경로 결측(NULL)은 PII 노출이 아니라 데이터 결측이므로 <종전대로 노출>한다 —
        // 배제하면 증강 파생(WINTER/NIGHT/RAIN) 전량과 비식별 실패 영상 전량이 마트에서 사라진다.
        assertThat(visible).contains(nullSrcSn);
        // 노출된 행 중 두 경로가 모두 있는 경우 반드시 상이하다(관제 계약 불변식)
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT ORIGINAL_PATH, DEIDENTIFIED_PATH FROM V_COMPLETED_FRAME WHERE RAW_SN = ?", rawSn);
        assertThat(rows).allSatisfy(r -> {
            if (r.get("deidentified_path") != null && r.get("original_path") != null) {
                assertThat(r.get("deidentified_path")).isNotEqualTo(r.get("original_path"));
            }
        });
    }

    @Test
    @DisplayName("파생영상_프레임은_ORIGINAL_PATH가_null이어도_비식별경로로_뷰에_노출된다(정책A)")
    void completedFrame_allowsNullOriginalForDerivative() {
        // given — 해상도 파생영상 프레임(정책 A: 원본 부재 → SRC_FILE_PATH_NM null)
        long rawSn = seedRawAndStatus("APPROVED");
        long srcSn = seedFramePaths(rawSn, 40, null, "/nas/frames/deid/" + rawSn + "/40.jpg");

        // when
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT SRC_SN, ORIGINAL_PATH, DEIDENTIFIED_PATH FROM V_COMPLETED_FRAME WHERE SRC_SN = ?", srcSn);

        // then — 원본 부재는 정상이며 비식별 경로만 노출된다(원본을 비식별로 오인할 여지 없음)
        assertThat(((Number) row.get("src_sn")).longValue()).isEqualTo(srcSn);
        assertThat(row.get("original_path")).isNull();
        assertThat(row.get("deidentified_path")).isEqualTo("/nas/frames/deid/" + rawSn + "/40.jpg");
    }
}
