package kr.co.cudo.authoring.dataset.view;

import kr.co.cudo.authoring.dataset.export.ExportPrivacyPolicy;
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
 * V174 — {@code V_COMPLETED_VIDEO} 30컬럼 재작성의 <b>값 계약</b> 검증(@req R5 · @req R6).
 *
 * <p>정본은 {@code docs/관제-저작도구-데이터연동-규격서-20260805.md} §5-1(컬럼) · §5-2~5-7(값 규칙)이다.
 * 컬럼 이름·순서 계약은 {@link DatamartViewRebuildIT} 가 고정하고, 여기서는 <b>각 컬럼이 무엇에서
 * 어떻게 조달되는가</b>를 실 DB(PostgreSQL Testcontainer)로 고정한다.
 *
 * <h3>무엇을 막는가</h3>
 * <ul>
 *   <li><b>파생영상 오염</b> — 파생은 대응하는 원천 영상이 없다. 부모 인입 행에서
 *       {@code SRC_*_INCL_YN} 을 끌어오면 "비식별 전 영상"의 판정이 비식별본 파생에 붙는다.
 *       {@code ORGNL_VDO_PATH_NM} 은 부모 <b>원본</b> 경로를 흘려 PII 경로 노출이 된다(CWE-359).</li>
 *   <li><b>행 증식</b> — LATERAL 이 4개(export · 비식별로그 · 인입 · 라벨집계)로 늘었다. 하나라도
 *       다행을 반환하면 영상이 중복 노출돼 관제 UPSERT 가 어긋난다.</li>
 *   <li><b>인입 결측에서의 붕괴</b> — 관제는 현재 이벤트 코드만 보내고 분류/카테고리/지자체명·
 *       개인정보 3필드를 <b>보내지 않는다</b>(dev 실측 40행 전량 NULL). NULL 이 정상 경로다.</li>
 *   <li><b>상수 복제 드리프트</b> — 개인정보 프리필 상수가 {@code ExportPrivacyPolicy} 와 갈리면
 *       화면·export 산출물과 뷰가 서로 다른 값을 말한다(2026-08-03 실사고).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("local")
class V174CompletedVideoViewContractIT {

    private final JdbcTemplate jdbc;
    private final List<Long> seededRawSns = new ArrayList<>();

    V174CompletedVideoViewContractIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanup() {
        // 자식 → 부모 순. 파생이 먼저 지워지도록 시드 역순으로 돈다.
        for (int idx = seededRawSns.size() - 1; idx >= 0; idx--) {
            Long rawSn = seededRawSns.get(idx);
            jdbc.update("DELETE FROM LS_DATA_INGEST WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATASET_EXPORT WHERE DATA_RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DEIDENT_PROC_LOG WHERE DATA_RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_LBL WHERE SRC_SN IN "
                    + "(SELECT SRC_SN FROM LS_DATA_SRC WHERE RAW_SN = ?)", rawSn);
            jdbc.update("DELETE FROM LS_DATA_SRC WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATASET_VIDEO_META WHERE RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_RAW WHERE RAW_SN = ?", rawSn);
        }
    }

    // ---------------------------------------------------------------- 시드

    /** 라이브 영상 1건(원본). {@code srcType} 이 GEN_AI_YN 판정축(D2)이다. */
    private long seedRaw(String srcType) {
        return seedRaw(srcType, null);
    }

    /** 라이브 영상 1건. {@code orgnlRawSn} 이 non-null 이면 파생영상이다. */
    private long seedRaw(String srcType, Long orgnlRawSn) {
        long nano = System.nanoTime();
        Long rawSn = jdbc.queryForObject(
                "INSERT INTO LS_DATA_RAW (VMS_CLIP_ID, VMS_CCTV_ID, EVNT_TYPE_CD, LCLGV_CD, "
                        + "PRVC_TYPE_CD, PRVC_YN, DE_IDENT_YN, RAW_FILE_PATH_NM, SHT_DT, VDO_LEN_SEC, "
                        + "DATA_STTS_CD, SRC_TYPE, ORGNL_RAW_SN, REG_DT) "
                        + "VALUES (?, ?, 'EVT01', '1111000000', 'PRVC', 'Y', 'Y', ?, ?, 30, "
                        + "'COMPLETED', ?, ?, ?) RETURNING RAW_SN",
                Long.class,
                "CLIP-V174-" + nano, "CCTV-" + nano, "/nas/raw/" + nano + ".mp4",
                LocalDateTime.of(2026, 1, 15, 22, 0), srcType, orgnlRawSn, LocalDateTime.now());
        seededRawSns.add(rawSn);
        return rawSn;
    }

    /** 검수 승인 상태(뷰 노출 게이트). */
    private void seedApproved(long rawSn) {
        jdbc.update("INSERT INTO LS_RAW_DATA_STATUS (RAW_DATA_ID, DATA_STTS_CD, UPD_DT, VER) "
                + "VALUES (?, 'APPROVED', ?, 1)", rawSn, LocalDateTime.now());
    }

    private void seedStatus(long rawSn, String sttsCd) {
        jdbc.update("INSERT INTO LS_RAW_DATA_STATUS (RAW_DATA_ID, DATA_STTS_CD, UPD_DT, VER) "
                + "VALUES (?, ?, ?, 1)", rawSn, sttsCd, LocalDateTime.now());
    }

    /**
     * 동결 스냅샷 1행. {@code rawFilePathNm} 은 파생영상이면 null 로 동결된다
     * ({@code DatasetVideoMetaSnapshotService} — 관제 연동 계약).
     */
    private void seedSnapshot(long rawSn, Long orgnlRawSn, String evntNm, String rawFilePathNm,
                              LocalDateTime rvwCmplDt) {
        jdbc.update(
                "INSERT INTO LS_DATASET_VIDEO_META (RAW_SN, SNPSHT_HASH, ACTIVE_YN, ORGNL_RAW_SN, "
                        + "RAW_FILE_PATH_NM, SHT_DT, VDO_LEN_SEC, LCLGV_CD, DE_IDENT_YN, AI_CRT_YN, "
                        + "EVNT_TYPE_CD, EVNT_NM, RVW_CMPL_DT, REG_DT) "
                        + "VALUES (?, ?, 'Y', ?, ?, ?, 30, '1111000000', 'Y', 'N', 'EVT01', ?, ?, ?)",
                rawSn, "hash-v174-" + rawSn, orgnlRawSn, rawFilePathNm,
                LocalDateTime.of(2026, 1, 15, 22, 0), evntNm, rvwCmplDt, LocalDateTime.now());
    }

    /** 관제 인입 1행(수신 원장). null 인자는 "관제가 그 값을 보내지 않았다"는 뜻이다. */
    private void seedIngest(long rawSn, String evntClsfCd, String evntCtgryCd, String lclgvNm,
                            String anony, String psdo, String prvc) {
        long nano = System.nanoTime();
        jdbc.update(
                "INSERT INTO LS_DATA_INGEST (RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, VDO_FILE_NM, "
                        + "RAW_FILE_PATH_NM, SRC_TYPE, EVNT_CLSF_CD, EVNT_CTGRY_CD, LCLGV_NM, "
                        + "ANONY_INCL_YN, PSDO_INCL_YN, PRVC_INCL_YN, RCPTN_DT) "
                        + "VALUES (?, ?, 'CCTV-ING', 'clip.mp4', '/nas/raw/clip.mp4', 'ORIGINAL', "
                        + "?, ?, ?, ?, ?, ?, ?)",
                rawSn, "ING-V174-" + nano, evntClsfCd, evntCtgryCd, lclgvNm,
                anony, psdo, prvc, LocalDateTime.now());
    }

    private void seedExport(long rawSn, int verNo, String pathNm, String sttsCd,
                            Integer frmeCnt, Long capacity) {
        jdbc.update(
                "INSERT INTO LS_DATASET_EXPORT (DATA_RAW_SN, OUTPUT_VER_NO, OUTPUT_PATH_NM, "
                        + "OUTPUT_STTS_CD, FRME_CNT, DATA_ETBL_CPCT, REG_DT) VALUES (?, ?, ?, ?, ?, ?, ?)",
                rawSn, verNo, pathNm, sttsCd, frmeCnt, capacity, LocalDateTime.now());
    }

    private void seedDeidProcLog(long rawSn, String deidFilePath) {
        jdbc.update(
                "INSERT INTO LS_DEIDENT_PROC_LOG (DATA_RAW_SN, ORGNL_FILE_PATH_NM, DE_IDNTF_FILE_PATH_NM, "
                        + "PROC_STTS_CD, REQ_DT, REG_DT) VALUES (?, ?, ?, 'SUCCEEDED', ?, ?)",
                rawSn, "/nas/raw/" + rawSn + ".mp4", deidFilePath,
                LocalDateTime.now(), LocalDateTime.now());
    }

    /** 프레임 1장 + 그 프레임의 라벨 1건(형태 집계용). */
    private void seedLabel(long rawSn, int frameNo, String lblTypeCd) {
        Long srcSn = jdbc.queryForObject(
                "INSERT INTO LS_DATA_SRC (RAW_SN, FRM_NO, SRC_FILE_PATH_NM, SHT_DT, REG_DT) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING SRC_SN",
                Long.class, rawSn, frameNo, "/nas/frames/raw/" + rawSn + "/" + frameNo + ".jpg",
                LocalDateTime.of(2026, 1, 15, 22, 0), LocalDateTime.now());
        jdbc.update("INSERT INTO LS_DATA_LBL (SRC_SN, LBL_TYPE_CD, LBL_NM, POINT_CN, REG_DT) "
                        + "VALUES (?, ?, '사람', '[[1,1],[2,2]]', ?)",
                srcSn, lblTypeCd, LocalDateTime.now());
    }

    /** 승인 + 활성 스냅샷까지 갖춘 최소 원본 영상. */
    private long seedApprovedOriginal(String evntNm) {
        long rawSn = seedRaw("ORIGINAL");
        seedApproved(rawSn);
        seedSnapshot(rawSn, null, evntNm, "/nas/raw/orgnl-" + rawSn + ".mp4",
                LocalDateTime.of(2026, 2, 1, 10, 0));
        return rawSn;
    }

    private Map<String, Object> viewRow(long rawSn) {
        return jdbc.queryForMap("SELECT * FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?", rawSn);
    }

    // ---------------------------------------------------------------- 행 단위 불변식

    @Test
    @DisplayName("승인영상_1건이_1row_로_노출됨")
    void 승인영상_1건이_1row_로_노출됨() {
        // given — 모든 LATERAL 이 <여러 후보 행>을 갖는 최악 조건.
        //   export 3버전 · 비식별 성공 이력 2건 · 인입 2행(수기 정정 상정) · 라벨 3건.
        long rawSn = seedApprovedOriginal("교통사고");
        seedExport(rawSn, 1, "/labeling/" + rawSn, "SUCCEEDED", 100, 1_000L);
        seedExport(rawSn, 2, "/labeling/" + rawSn, "SUCCEEDED", 120, 2_000L);
        seedExport(rawSn, 3, "/labeling/" + rawSn, "PARTIAL", 130, 3_000L);
        seedDeidProcLog(rawSn, "/nas/deid/" + rawSn + "/old-mask.mp4");
        seedDeidProcLog(rawSn, "/nas/deid/" + rawSn + "/new-mask.mp4");
        seedIngest(rawSn, "01", "0102", "서울특별시", "N", "N", "Y");
        seedIngest(rawSn, "02", "0203", "부산광역시", "Y", "Y", "N");
        seedLabel(rawSn, 1, "BBOX");
        seedLabel(rawSn, 2, "BBOX");
        seedLabel(rawSn, 3, "POLYGON");

        // when
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?", rawSn);

        // then — LATERAL 4개가 모두 최대 1행이라 영상 1건 = 1 row 가 유지된다.
        assertThat(rows).hasSize(1);
        // 최신 산출(v3 PARTIAL)·최신 인입(RCPTN_SN DESC = 두 번째 시드)이 선택된다.
        assertThat(rows.get(0).get("output_stts_cd")).isEqualTo("PARTIAL");
        assertThat(((Number) rows.get(0).get("frme_cnt")).intValue()).isEqualTo(130);
        assertThat(rows.get(0).get("lclgv_nm")).isEqualTo("부산광역시");
    }

    @Test
    @DisplayName("미승인_영상은_뷰에_노출되지_않음")
    void 미승인_영상은_뷰에_노출되지_않음() {
        // given — 활성 스냅샷은 있으나 라이브 상태가 PENDING(재검수 진입)
        long rawSn = seedRaw("ORIGINAL");
        seedStatus(rawSn, "PENDING");
        seedSnapshot(rawSn, null, "교통사고", "/nas/raw/x.mp4", LocalDateTime.now());

        // when / then — V95/V102 불변식(뷰 노출 ⇔ 라이브 APPROVED)은 재작성 후에도 유지된다.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?",
                Integer.class, rawSn)).isZero();
    }

    // ---------------------------------------------------------------- 인입 조달 (D1)

    @Test
    @DisplayName("인입값이_전부_NULL_이어도_뷰가_1행을_반환한다")
    void 인입값이_전부_NULL_이어도_뷰가_1행을_반환한다() {
        // given — 관제는 현재 이벤트 코드만 보내고 분류·카테고리·지자체명·개인정보 3필드를 보내지
        //   않는다(dev 실측 40행 전량 NULL). 이것이 <정상 경로>다.
        long rawSn = seedApprovedOriginal("교통사고");
        seedIngest(rawSn, null, null, null, null, null, null);

        // when
        Map<String, Object> row = viewRow(rawSn);

        // then — 행은 존재하고 인입 유래 값만 null 이다(저작도구가 임의로 채우지 않는다 — 규격서 §5-1).
        assertThat(((Number) row.get("raw_sn")).longValue()).isEqualTo(rawSn);
        assertThat(row.get("evnt_clsf_cd")).isNull();
        assertThat(row.get("evnt_ctgry_cd")).isNull();
        assertThat(row.get("lclgv_nm")).isNull();
        assertThat(row.get("src_anony_incl_yn")).isNull();
        assertThat(row.get("src_psdo_incl_yn")).isNull();
        assertThat(row.get("src_prvc_incl_yn")).isNull();
        // 인입과 무관한 값은 정상 조달된다.
        assertThat(row.get("evnt_type_cd")).isEqualTo("EVT01");
        assertThat(row.get("anony_incl_yn")).isEqualTo("Y");
    }

    @Test
    @DisplayName("인입행_자체가_없어도_뷰가_1행을_반환한다")
    void 인입행_자체가_없어도_뷰가_1행을_반환한다() {
        // given — 인입 테이블 신설(V147) 이전에 적재된 영상은 인입 행이 아예 없다.
        long rawSn = seedApprovedOriginal("교통사고");

        // when / then — LEFT JOIN LATERAL 이라 행이 사라지지 않는다.
        Map<String, Object> row = viewRow(rawSn);
        assertThat(((Number) row.get("raw_sn")).longValue()).isEqualTo(rawSn);
        assertThat(row.get("lclgv_nm")).isNull();
    }

    @Test
    @DisplayName("인입값이_있으면_이벤트분류_카테고리_지자체명이_그대로_노출된다")
    void 인입값이_있으면_이벤트분류_카테고리_지자체명이_그대로_노출된다() {
        // given
        long rawSn = seedApprovedOriginal("교통사고");
        seedIngest(rawSn, "01", "0102", "서울특별시", "N", "N", "Y");

        // when
        Map<String, Object> row = viewRow(rawSn);

        // then — 관제 수신 원장 값을 가공 없이 돌려준다.
        assertThat(row.get("evnt_clsf_cd")).isEqualTo("01");
        assertThat(row.get("evnt_ctgry_cd")).isEqualTo("0102");
        assertThat(row.get("lclgv_nm")).isEqualTo("서울특별시");
        assertThat(row.get("src_anony_incl_yn")).isEqualTo("N");
        assertThat(row.get("src_psdo_incl_yn")).isEqualTo("N");
        assertThat(row.get("src_prvc_incl_yn")).isEqualTo("Y");
    }

    // ---------------------------------------------------------------- 파생영상

    @Test
    @DisplayName("파생영상은_SRC_개인정보3필드가_NULL")
    void 파생영상은_SRC_개인정보3필드가_NULL() {
        // given — 부모(원본)에는 인입 행이 있고 원천 판정도 실려 있다. 파생은 자기 인입 행이 없다.
        long parentSn = seedApprovedOriginal("교통사고");
        seedIngest(parentSn, "01", "0102", "서울특별시", "N", "N", "Y");

        long derivedSn = seedRaw("AUGMENTED", parentSn);
        seedApproved(derivedSn);
        seedSnapshot(derivedSn, parentSn, "교통사고", null, LocalDateTime.of(2026, 2, 1, 10, 0));

        // when
        Map<String, Object> row = viewRow(derivedSn);

        // then — 파생본은 부모의 <비식별 영상>을 복사한 것이라 대응하는 원천 영상이 없다(규격서 §5-2).
        //   부모 인입의 원천 판정을 끌어오면 "비식별 전 영상"의 판정을 비식별본에 붙이는 셈이다.
        assertThat(row.get("src_anony_incl_yn")).isNull();
        assertThat(row.get("src_psdo_incl_yn")).isNull();
        assertThat(row.get("src_prvc_incl_yn")).isNull();
        // 반면 분류·카테고리·지자체명은 <부모 값이 그대로 유효>하므로 상속된다(D1 — 두 갈래).
        assertThat(row.get("evnt_clsf_cd")).isEqualTo("01");
        assertThat(row.get("evnt_ctgry_cd")).isEqualTo("0102");
        assertThat(row.get("lclgv_nm")).isEqualTo("서울특별시");
    }

    @Test
    @DisplayName("파생영상은_ORGNL_VDO_PATH_NM_이_NULL")
    void 파생영상은_ORGNL_VDO_PATH_NM_이_NULL() {
        // given — 파생은 동결 시점에 원본 경로가 null 로 동결된다(파생에는 원본 영상이 없다).
        long parentSn = seedApprovedOriginal("교통사고");
        long derivedSn = seedRaw("AUGMENTED", parentSn);
        seedApproved(derivedSn);
        seedSnapshot(derivedSn, parentSn, "교통사고", null, LocalDateTime.of(2026, 2, 1, 10, 0));
        seedDeidProcLog(derivedSn, "/nas/deid/augment/" + derivedSn + "/WINTER-mask.mp4");

        // when
        Map<String, Object> row = viewRow(derivedSn);

        // then — 부모 원본 경로 폴백이 없다(있으면 DE_IDNTF_YN='Y' 행에 PII 원본 경로가 실린다 — CWE-359).
        assertThat(row.get("orgnl_vdo_path_nm")).isNull();
        // 관제는 파생 비디오를 비식별 경로로 픽업한다(§5-5).
        assertThat(row.get("de_idntf_file_path_nm"))
                .isEqualTo("/nas/deid/augment/" + derivedSn + "/WINTER-mask.mp4");
        assertThat(row.get("vdo_yn")).isEqualTo("Y");
    }

    // ---------------------------------------------------------------- GEN_AI_YN (D2)

    @Test
    @DisplayName("GENERATED_원본의_GEN_AI_YN_이_Y")
    void GENERATED_원본의_GEN_AI_YN_이_Y() {
        // given — 관제가 AI 생성물로 인입한 <원본>(ORGNL_RAW_SN IS NULL). 동결 AI_CRT_YN 은 'N' 으로
        //   오동결돼 있다(도출식 결함) — 뷰는 그 값을 읽지 않고 SRC_TYPE 에서 직접 도출한다(D2).
        long rawSn = seedRaw("GENERATED");
        seedApproved(rawSn);
        seedSnapshot(rawSn, null, "교통사고", "/nas/raw/gen.mp4", LocalDateTime.of(2026, 2, 1, 10, 0));

        // when / then
        assertThat(viewRow(rawSn).get("gen_ai_yn")).isEqualTo("Y");
        assertThat(jdbc.queryForObject(
                "SELECT AI_CRT_YN FROM LS_DATASET_VIDEO_META WHERE RAW_SN = ?", String.class, rawSn))
                .as("동결 AI_CRT_YN 은 결함 값 그대로다 — 뷰가 이 값을 읽으면 안 된다")
                .isEqualTo("N");
    }

    @Test
    @DisplayName("AUGMENTED_파생의_GEN_AI_YN_이_Y")
    void AUGMENTED_파생의_GEN_AI_YN_이_Y() {
        // given
        long parentSn = seedApprovedOriginal("교통사고");
        long derivedSn = seedRaw("AUGMENTED", parentSn);
        seedApproved(derivedSn);
        seedSnapshot(derivedSn, parentSn, "교통사고", null, LocalDateTime.of(2026, 2, 1, 10, 0));

        // when / then — 증강·해상도 파생은 저작도구가 만든 생성물이다.
        assertThat(viewRow(derivedSn).get("gen_ai_yn")).isEqualTo("Y");
    }

    @Test
    @DisplayName("일반_원본의_GEN_AI_YN_이_N")
    void 일반_원본의_GEN_AI_YN_이_N() {
        // given / when / then — ORIGINAL·RELAY·USER_ULD 는 생성형 AI 산출물이 아니다.
        assertThat(viewRow(seedApprovedOriginal("교통사고")).get("gen_ai_yn")).isEqualTo("N");
    }

    // ---------------------------------------------------------------- 개인정보 3필드(비식별 축)

    @Test
    @DisplayName("개인정보3필드_미입력시_기본값_Y_N_N_이_채워짐")
    void 개인정보3필드_미입력시_기본값_Y_N_N_이_채워짐() {
        // given — 사람이 판정을 입력하지 않은 영상(레거시 행). 규격서 §5-1 은 이 3컬럼을 NOT NULL 로
        //   공표했으므로 NULL 이 아니라 기본값이 나가야 한다.
        long rawSn = seedApprovedOriginal("교통사고");
        jdbc.update("UPDATE LS_DATA_RAW SET ANONY_INCL_YN = NULL, PSDO_INCL_YN = NULL, "
                + "PRVC_INCL_YN = NULL WHERE RAW_SN = ?", rawSn);

        // when
        Map<String, Object> row = viewRow(rawSn);

        // then
        assertThat(row.get("anony_incl_yn")).isEqualTo("Y");
        assertThat(row.get("psdo_incl_yn")).isEqualTo("N");
        assertThat(row.get("prvc_incl_yn")).isEqualTo("N");
    }

    @Test
    @DisplayName("개인정보_기본값은_ExportPrivacyPolicy_상수와_같다")
    void 개인정보_기본값은_ExportPrivacyPolicy_상수와_같다() {
        // given — SQL 은 자바 상수를 참조할 수 없어 값을 <복제>한다. 한쪽만 바꾸면 화면·export
        //   산출물과 뷰가 서로 다른 값을 말한다(2026-08-03 실사고). 이 테스트가 두 축을 묶는다.
        long rawSn = seedApprovedOriginal("교통사고");
        jdbc.update("UPDATE LS_DATA_RAW SET ANONY_INCL_YN = NULL, PSDO_INCL_YN = NULL, "
                + "PRVC_INCL_YN = NULL WHERE RAW_SN = ?", rawSn);

        // when
        Map<String, Object> row = viewRow(rawSn);

        // then
        assertThat(row.get("anony_incl_yn")).isEqualTo(ExportPrivacyPolicy.DEID_DEFAULT_ANONYMITY);
        assertThat(row.get("psdo_incl_yn")).isEqualTo(ExportPrivacyPolicy.DEID_DEFAULT_PSEUDONYMITY);
        assertThat(row.get("prvc_incl_yn"))
                .isEqualTo(ExportPrivacyPolicy.DEID_DEFAULT_PRIVACY_INCLUDED);
    }

    @Test
    @DisplayName("개인정보3필드_수동입력값이_기본값보다_우선한다")
    void 개인정보3필드_수동입력값이_기본값보다_우선한다() {
        // given — 검수 과정에서 사람이 입력한 영상 단위 판정(V163)
        long rawSn = seedApprovedOriginal("교통사고");
        jdbc.update("UPDATE LS_DATA_RAW SET ANONY_INCL_YN = 'N', PSDO_INCL_YN = 'Y', "
                + "PRVC_INCL_YN = 'Y' WHERE RAW_SN = ?", rawSn);

        // when
        Map<String, Object> row = viewRow(rawSn);

        // then — 기본값이 사람의 판정을 덮지 않는다.
        assertThat(row.get("anony_incl_yn")).isEqualTo("N");
        assertThat(row.get("psdo_incl_yn")).isEqualTo("Y");
        assertThat(row.get("prvc_incl_yn")).isEqualTo("Y");
    }

    // ---------------------------------------------------------------- 데이터셋명·구축 메타

    @Test
    @DisplayName("DATST_NM_과_DATST_EXPLN_은_같은_값이고_이벤트명_데이터셋_구축_형식")
    void DATST_NM_과_DATST_EXPLN_은_같은_값이고_이벤트명_데이터셋_구축_형식() {
        // given — 관제 datasets.name/description 은 둘 다 NOT NULL 이나 값 규격이 없어, 기존 적재
        //   사례("교통사고 데이터셋 구축")를 따른다(규격서 §5-3).
        long rawSn = seedApprovedOriginal("교통사고");

        // when
        Map<String, Object> row = viewRow(rawSn);

        // then
        assertThat(row.get("datst_nm")).isEqualTo("교통사고 데이터셋 구축");
        assertThat(row.get("datst_expln")).isEqualTo(row.get("datst_nm"));
    }

    @Test
    @DisplayName("이벤트명이_없으면_DATST_NM_은_NULL_이다")
    void 이벤트명이_없으면_DATST_NM_은_NULL_이다() {
        // given — 관제가 이벤트명을 보내지 않은 영상. 저작도구가 임의로 채우지 않는다(규격서 §5-1).
        long rawSn = seedRaw("ORIGINAL");
        seedApproved(rawSn);
        seedSnapshot(rawSn, null, null, "/nas/raw/noname.mp4", LocalDateTime.of(2026, 2, 1, 10, 0));

        // when / then — ' 데이터셋 구축' 만 남은 문자열이 나가면 안 된다.
        Map<String, Object> row = viewRow(rawSn);
        assertThat(row.get("datst_nm")).isNull();
        assertThat(row.get("datst_expln")).isNull();
    }

    /** {@code DATST_NM} 접미사 — 이 길이가 절단 경계를 정한다({@code 200 - SUFFIX.length()}). */
    private static final String DATST_SUFFIX = " 데이터셋 구축";

    /** {@code DATST_NM} 캐스팅 길이 — 행안부 공통표준용어 {@code 데이터셋명} 도메인 명V200. */
    private static final int DATST_MAX_LEN = 200;

    /**
     * {@code DATST_EXPLN} 캐스팅 길이 — 사업표준용어 {@code 데이터셋설명} 도메인 내용V4000
     * (공통표준용어에는 이 용어가 미등록이라 ②순위인 사업표준을 따른다).
     *
     * <p><b>두 컬럼의 길이가 다른 것이 정상이다</b> — 명칭(명)과 내용(내용)은 도메인 그룹이 다르고,
     * 관제 스키마도 {@code datasets.name}=varchar(500) / {@code description}=text 로 갈라져 있다.
     * "값이 같으니 길이도 통일하라"로 되돌리지 말 것.
     */
    private static final int DATST_EXPLN_MAX_LEN = 4000;

    @Test
    @DisplayName("DATST_NM_은_이벤트명_192자까지_보존된다")
    void DATST_NM_은_이벤트명_192자까지_보존된다() {
        // given — 뷰는 결과를 VARCHAR(200) 으로 <명시 캐스팅>한다. 물리명·길이 모두 행안부 공통표준
        //   DATST_NM(명V200) 기준이다(표준 우선순위 ① 행안부 → ② 사업). 사업표준 DATA_SET_NM(명V100)은
        //   채택하지 않는다. 접미사가 8자이므로 이벤트명 192자까지는 정확히 200자로 <전량 보존>된다.
        int boundary = DATST_MAX_LEN - DATST_SUFFIX.length();
        assertThat(boundary).as("접미사 길이가 바뀌면 경계도 바뀐다").isEqualTo(192);
        String evntNm = "가".repeat(boundary);
        long rawSn = seedRaw("ORIGINAL");
        seedApproved(rawSn);
        seedSnapshot(rawSn, null, evntNm, "/nas/raw/b1.mp4", LocalDateTime.of(2026, 2, 1, 10, 0));

        // when
        Map<String, Object> row = viewRow(rawSn);

        // then — 경계 직전: 접미사까지 온전하다.
        assertThat((String) row.get("datst_nm"))
                .hasSize(DATST_MAX_LEN).isEqualTo(evntNm + DATST_SUFFIX);
        assertThat(row.get("datst_expln")).isEqualTo(row.get("datst_nm"));
    }

    @Test
    @DisplayName("DATST_NM_은_이벤트명_193자부터_200자로_절단된다")
    void DATST_NM_은_이벤트명_193자부터_200자로_절단된다() {
        // given — ⚠ 명시 캐스팅 ::VARCHAR(200) 은 <에러 없이 자른다>(SQL 표준: 명시 cast=절단).
        //   즉 이벤트명이 193자를 넘으면 관제에 <잘린 데이터셋명>이 나가고 아무도 모른다.
        //   현실 이벤트명은 '교통사고' 수준이라 위험은 낮지만 경계 자체를 여기서 고정한다.
        String evntNm = "가".repeat(DATST_MAX_LEN - DATST_SUFFIX.length() + 1);   // 193자
        long rawSn = seedRaw("ORIGINAL");
        seedApproved(rawSn);
        seedSnapshot(rawSn, null, evntNm, "/nas/raw/b2.mp4", LocalDateTime.of(2026, 2, 1, 10, 0));

        // when
        Map<String, Object> row = viewRow(rawSn);

        // then — 200자에서 잘리고 접미사 끝('축')이 사라진다. 예외는 발생하지 않는다(조용한 절단).
        String datstNm = (String) row.get("datst_nm");
        assertThat(datstNm).hasSize(DATST_MAX_LEN);
        assertThat(datstNm).isEqualTo((evntNm + DATST_SUFFIX).substring(0, DATST_MAX_LEN));
        assertThat(datstNm).doesNotEndWith(DATST_SUFFIX);

        // and — 설명은 <같은 값이지만 잘리지 않는다>(상한 4000 > 최대 263). 두 컬럼의 길이가 다른 것이
        //   정상이며, 이 지점에서만 값이 갈린다. "값이 같아야 하니 통일하라"로 되돌리지 말 것.
        String datstExpln = (String) row.get("datst_expln");
        assertThat(datstExpln)
                .as("DATST_EXPLN 은 내용V4000 이라 절단이 구조적으로 일어나지 않는다")
                .isEqualTo(evntNm + DATST_SUFFIX)
                .hasSize(evntNm.length() + DATST_SUFFIX.length())
                .endsWith(DATST_SUFFIX);
        assertThat(datstExpln).isNotEqualTo(datstNm);
        assertThat(datstExpln.length()).isLessThan(DATST_EXPLN_MAX_LEN);
    }

    @Test
    @DisplayName("DATA_ETBL_YR_은_검수완료_연도다")
    void DATA_ETBL_YR_은_검수완료_연도다() {
        // given — 구축년도는 산출 시각이 아니라 <검수 완료 연도>로 고정한다(규격서 §7-H).
        long rawSn = seedRaw("ORIGINAL");
        seedApproved(rawSn);
        seedSnapshot(rawSn, null, "교통사고", "/nas/raw/y.mp4", LocalDateTime.of(2025, 12, 31, 23, 59));

        // when / then
        assertThat(viewRow(rawSn).get("data_etbl_yr")).isEqualTo("2025");
    }

    // ---------------------------------------------------------------- 산출물 축

    @Test
    @DisplayName("미export_영상은_OUTPUT_PATH_NM_이_NULL_이지만_행은_존재")
    void 미export_영상은_OUTPUT_PATH_NM_이_NULL_이지만_행은_존재() {
        // given — 승인은 됐으나 아직 산출이 없는 영상
        long rawSn = seedApprovedOriginal("교통사고");

        // when
        Map<String, Object> row = viewRow(rawSn);

        // then — 행은 보존되고 산출 축만 비어 있다.
        assertThat(((Number) row.get("raw_sn")).longValue()).isEqualTo(rawSn);
        assertThat(row.get("output_path_nm")).isNull();
        assertThat(row.get("output_stts_cd")).isNull();
        // 프레임수는 규격서가 NOT NULL 로 공표했다 — 산출이 없으면 0 장이 사실이다.
        assertThat(((Number) row.get("frme_cnt")).intValue()).isZero();
        assertThat(row.get("img_yn")).isEqualTo("N");
        // 반면 구축용량은 "미산출 시 NULL" 이 공표값이다(0 바이트와 미산출은 다르다).
        assertThat(row.get("data_etbl_cpct")).isNull();
    }

    @Test
    @DisplayName("export_된_영상은_경로_프레임수_용량이_최신_산출본에서_나온다")
    void export_된_영상은_경로_프레임수_용량이_최신_산출본에서_나온다() {
        // given — v1 성공 후 v2 성공(재승인). 관제는 최신 버전을 봐야 한다.
        long rawSn = seedApprovedOriginal("교통사고");
        seedExport(rawSn, 1, "/labeling/" + rawSn, "SUCCEEDED", 100, 10_240L);
        seedExport(rawSn, 2, "/labeling/" + rawSn, "SUCCEEDED", 120, 20_480L);

        // when
        Map<String, Object> row = viewRow(rawSn);

        // then
        assertThat(row.get("output_path_nm")).isEqualTo("/labeling/" + rawSn);
        assertThat(row.get("output_stts_cd")).isEqualTo("SUCCEEDED");
        assertThat(((Number) row.get("frme_cnt")).intValue()).isEqualTo(120);
        assertThat(((Number) row.get("data_etbl_cpct")).longValue()).isEqualTo(20_480L);
        assertThat(row.get("img_yn")).isEqualTo("Y");
    }

    @Test
    @DisplayName("비식별_영상이_없으면_VDO_YN_이_N_이고_경로는_NULL")
    void 비식별_영상이_없으면_VDO_YN_이_N_이고_경로는_NULL() {
        // given — 비식별 성공 이력 없음
        long rawSn = seedApprovedOriginal("교통사고");

        // when / then
        Map<String, Object> row = viewRow(rawSn);
        assertThat(row.get("de_idntf_file_path_nm")).isNull();
        assertThat(row.get("vdo_yn")).isEqualTo("N");
    }

    // ---------------------------------------------------------------- 라벨 축

    @Test
    @DisplayName("LBL_TYPE_은_영상에서_쓰인_라벨형태_집합이다")
    void LBL_TYPE_은_영상에서_쓰인_라벨형태_집합이다() {
        // given — 같은 형태가 여러 프레임에 반복돼도 한 번만, 정렬된 순서로 나온다(결정성).
        long rawSn = seedApprovedOriginal("교통사고");
        seedLabel(rawSn, 1, "POLYGON");
        seedLabel(rawSn, 2, "BBOX");
        seedLabel(rawSn, 3, "BBOX");

        // when / then
        assertThat(viewRow(rawSn).get("lbl_type")).isEqualTo("BBOX,POLYGON");
    }

    @Test
    @DisplayName("라벨이_없으면_LBL_TYPE_은_NULL_이고_행은_존재")
    void 라벨이_없으면_LBL_TYPE_은_NULL_이고_행은_존재() {
        // given / when
        Map<String, Object> row = viewRow(seedApprovedOriginal("교통사고"));

        // then — 집계 LATERAL 이 행을 지우지 않는다.
        assertThat(row.get("lbl_type")).isNull();
        assertThat(row.get("lbl_fmt")).isEqualTo("NIA-COCO-JSON");
    }

    // ---------------------------------------------------------------- 신고 구간 노출 정책

    @Test
    @DisplayName("비식별_신고_구간에도_뷰에서_사라지지_않고_경로도_비워지지_않는다")
    void 비식별_신고_구간에도_뷰에서_사라지지_않고_경로도_비워지지_않는다() {
        // given — 검수 완료 후 비식별 누락 신고가 접수된 영상(DE_IDNTF_YN='F').
        //   "검수 완료·통지 건에 대한 관제 접근은 무조건 보장한다"가 확정 정책이다(CLAUDE.md).
        long rawSn = seedRaw("ORIGINAL");
        seedApproved(rawSn);
        seedSnapshot(rawSn, null, "교통사고", "/nas/raw/f.mp4", LocalDateTime.of(2026, 2, 1, 10, 0));
        jdbc.update("UPDATE LS_DATASET_VIDEO_META SET DE_IDENT_YN = 'F' WHERE RAW_SN = ?", rawSn);
        seedExport(rawSn, 1, "/labeling/" + rawSn, "SUCCEEDED", 10, 1_024L);
        seedDeidProcLog(rawSn, "/nas/deid/" + rawSn + "/mask.mp4");

        // when
        Map<String, Object> row = viewRow(rawSn);

        // then — 행도 경로도 그대로 나가고, 관제는 DE_IDNTF_YN 으로 자체 판단한다.
        //   신고 필터를 새로 넣으면 관제가 보던 행이 예고 없이 사라진다 — 결함으로 재분류 금지.
        assertThat(row.get("de_idntf_yn")).isEqualTo("F");
        assertThat(row.get("output_path_nm")).isEqualTo("/labeling/" + rawSn);
        assertThat(row.get("de_idntf_file_path_nm")).isEqualTo("/nas/deid/" + rawSn + "/mask.mp4");
    }

    // ---------------------------------------------------------------- 조인 규칙 고정

    @Test
    @DisplayName("뷰_인입조인은_IngestSourceLink_규칙과_동일하다")
    void 뷰_인입조인은_IngestSourceLink_규칙과_동일하다() {
        // given / when — 앱(Java 상수)과 뷰(SQL)에 같은 규칙이 두 표현으로 존재한다. 한쪽만 바꾸면
        //   파생영상의 인입 조달이 조용히 갈린다.
        String viewDef = jdbc.queryForObject(
                "SELECT pg_get_viewdef('v_completed_video'::regclass, true)", String.class);

        // then — ①부모 폴백 1단계 ②최신 수신 1행 ③원천 개인정보는 원본에서만
        assertThat(viewDef).contains("COALESCE(r.orgnl_raw_sn, r.raw_sn)");
        assertThat(viewDef).contains("ORDER BY ig.rcptn_sn DESC");
        assertThat(viewDef).contains("LIMIT 1");
        assertThat(viewDef)
                .as("SRC_* 3종은 CASE WHEN r.ORGNL_RAW_SN IS NULL 로 원본에만 채운다")
                .contains("r.orgnl_raw_sn IS NULL");
    }
}
