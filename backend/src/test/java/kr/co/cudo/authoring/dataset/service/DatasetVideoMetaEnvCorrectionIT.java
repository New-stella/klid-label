package kr.co.cudo.authoring.dataset.service;

import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
 * Phase 10B DEV_FIX(M-1) — <b>레거시 파생 동결값 정정 백필</b>
 * ({@link DatasetVideoMetaBackfillService#correctDerivedShootingEnvironment()}) 실 DB 통합 테스트.
 *
 * <p>E-ISSUE-42 로 동결 경로의 촬영환경 파생 폴백을 제거했지만, <b>제거 이전</b>에 이미 파생값
 * ({@code NGT}/{@code SUMMER})으로 동결된 스냅샷 행은 그대로 남는다. 그 행은
 * ①{@code V_COMPLETED_VIDEO.DAY_NGT_CD} 로 관제에 그대로 노출되고 ②{@code VideoMetaMapper} 의
 * {@code raw → meta} 폴백을 타고 <b>재-export 되는 새 버전 폴더에도 다시 기록</b>된다.
 * 즉 오염이 과거 산출물에 머물지 않고 신규 산출물로 계속 번진다.
 *
 * <p>정정 대상 판별식(결정적 증거): <b>라이브 {@code LS_DATA_RAW} 의 수동값이 없는데 활성 스냅샷에는
 * 값이 있는</b> 행. 수동 원천이 없는데 동결값이 존재할 경로는 폐기된 파생 폴백뿐이므로 파생값임이
 * 증명된다. 아래 테스트는 이 판별식을 <b>양방향</b>으로 고정한다 — 판별식을 넓히면(날씨 포함,
 * raw 수동값 보유 행 포함) 2·3번 테스트가 실패한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class DatasetVideoMetaEnvCorrectionIT {

    /** 레거시 동결 행의 승인 시각 — 정정(재동결)이 이 값을 보존해야 한다. */
    private static final LocalDateTime ORIGINAL_APPROVED_AT = LocalDateTime.of(2026, 3, 1, 10, 0);

    @Autowired
    private DatasetVideoMetaBackfillService backfillService;

    @Autowired
    private LsDatasetVideoMetaRepository metaRepository;

    private final JdbcTemplate jdbc;

    private final List<Long> seededRawSns = new ArrayList<>();
    private final List<String> seededEvntCds = new ArrayList<>();

    DatasetVideoMetaEnvCorrectionIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanup() {
        for (Long rawSn : seededRawSns) {
            jdbc.update("DELETE FROM LS_MON_NOTI_ACML WHERE RAW_SN = ?", rawSn);
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

    // ---------------------------------------------------------------------------------------
    // 1) 정정됨 — 라이브 수동값 미입력 + 동결값 존재(= 폐기된 파생 폴백 산물)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("라이브_수동값이_없는데_동결값이_있으면_정정되어_null로_재동결된다")
    void correction_nullsOutLegacyDerivedValues() {
        // given — 수동값 미입력(raw NULL) + 파생값으로 동결된 활성 스냅샷
        long rawSn = seedApprovedVideo(null, null, null);
        seedLegacySnapshot(rawSn, "NGT", "SUMMER", null);

        // when
        int corrected = backfillService.correctDerivedShootingEnvironment();

        // then — 활성 스냅샷이 새 행으로 교체되고 두 필드가 null(미상)로 동결된다.
        assertThat(corrected).isGreaterThanOrEqualTo(1);
        LsDatasetVideoMeta active = activeSnapshot(rawSn);
        assertThat(active.getDayNgtCd()).isNull();
        assertThat(active.getSesnCd()).isNull();
        // 검수 완료 일시는 최초 승인 시각을 보존한다(정정 실행 시각으로 덮지 않음).
        assertThat(active.getRvwCmplDt()).isEqualTo(ORIGINAL_APPROVED_AT);
        // 이전 오염 행은 비활성으로 남는다(이력 보존 — 행 삭제 금지).
        assertThat(metaRepository.findByRawSn(rawSn)).hasSize(2);
    }

    // ---------------------------------------------------------------------------------------
    // 2) 정정 안 됨 — 수동값 보존(판별식을 넓히면 실패한다)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("라이브_수동값이_있으면_정정대상이_아니고_동결_수동값이_보존된다")
    void correction_skipsVideoWithLiveManualValues() {
        // given — 작업자가 실제로 입력한 수동값 + 그 값으로 동결된 스냅샷
        long rawSn = seedApprovedVideo("DAY", "WINTER", "맑음");
        seedLegacySnapshot(rawSn, "DAY", "WINTER", "맑음");

        // when
        backfillService.correctDerivedShootingEnvironment();

        // then — 재동결 자체가 일어나지 않아 행이 늘지 않고 값도 그대로다.
        assertThat(metaRepository.findByRawSn(rawSn)).hasSize(1);
        LsDatasetVideoMeta active = activeSnapshot(rawSn);
        assertThat(active.getDayNgtCd()).isEqualTo("DAY");
        assertThat(active.getSesnCd()).isEqualTo("WINTER");
        assertThat(active.getWthrNm()).isEqualTo("맑음");
    }

    @Test
    @DisplayName("승인후_수동입력이_추가된_영상은_정정대상이_아니다")
    void correction_skipsPostApprovalManualInput() {
        // given — 승인 시점엔 미입력이라 스냅샷이 null, 이후 작업자가 수동 입력(raw 만 non-null).
        //         이 방향은 "정상 케이스"이며 동결은 승인 시점 스냅샷이므로 건드리지 않는다.
        long rawSn = seedApprovedVideo("DAY", "WINTER", null);
        seedLegacySnapshot(rawSn, null, null, null);

        // when
        backfillService.correctDerivedShootingEnvironment();

        // then — 재동결 없음(행 1건 유지, 동결값 null 그대로)
        assertThat(metaRepository.findByRawSn(rawSn)).hasSize(1);
        LsDatasetVideoMeta active = activeSnapshot(rawSn);
        assertThat(active.getDayNgtCd()).isNull();
        assertThat(active.getSesnCd()).isNull();
    }

    @Test
    @DisplayName("날씨만_동결된_영상은_정정대상이_아니다")
    void correction_neverTouchesWeatherOnlyRows() {
        // given — 날씨는 애초에 파생 원천이 없어 non-null 이면 진짜 수동값이다.
        //         (주야간/계절은 둘 다 미입력·미동결이라 판별식에 걸리지 않아야 한다.)
        long rawSn = seedApprovedVideo(null, null, null);
        seedLegacySnapshot(rawSn, null, null, "맑음");

        // when
        backfillService.correctDerivedShootingEnvironment();

        // then — 정정 대상이 아니므로 재동결 없음 + 날씨 수동값 보존
        assertThat(metaRepository.findByRawSn(rawSn)).hasSize(1);
        assertThat(activeSnapshot(rawSn).getWthrNm()).isEqualTo("맑음");
    }

    // ---------------------------------------------------------------------------------------
    // 3) 멱등 · 재산출/재통지 배선
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("정정을_두번_실행하면_두번째는_아무것도_하지_않는다")
    void correction_isIdempotentOnReRun() {
        // given
        long rawSn = seedApprovedVideo(null, null, null);
        seedLegacySnapshot(rawSn, "NGT", "SUMMER", null);

        // when — 두 번 실행
        backfillService.correctDerivedShootingEnvironment();
        int secondRun = backfillService.correctDerivedShootingEnvironment();

        // then — 두 번째는 대상 0건(no-op)이고 스냅샷 행도 늘지 않는다.
        assertThat(secondRun).isZero();
        assertThat(metaRepository.findByRawSn(rawSn)).hasSize(2);
        assertThat(activeSnapshot(rawSn).getDayNgtCd()).isNull();
    }

    @Test
    @DisplayName("정정하면_export재생성을_동반한_수정통지가_축적된다")
    void correction_accumulatesRegeneratingModifiedNotification() {
        // given
        long rawSn = seedApprovedVideo(null, null, null);
        seedLegacySnapshot(rawSn, "NGT", "SUMMER", null);

        // when
        backfillService.correctDerivedShootingEnvironment();

        // then — 커밋 후(AFTER_COMMIT) 디바운스 윈도우가 열리고 export 재생성 플래그가 켜진다.
        //   이 플래그가 flush 시 "export 전량 재생성 → SUCCEEDED 이후 통지" 순서를 강제하는 단일 축이다
        //   (CLAUDE.md: 통지는 export 성공 후 발송). 순서 역전이면 관제가 구 버전 폴더를 픽업한다.
        List<Map<String, Object>> windows = jdbc.queryForList(
                "SELECT EXPORT_RPRCS_YN, CHG_DTL_CN FROM LS_MON_NOTI_ACML WHERE RAW_SN = ?", rawSn);
        assertThat(windows).hasSize(1);
        assertThat(windows.get(0).get("export_rprcs_yn")).isEqualTo("Y");
        assertThat(String.valueOf(windows.get(0).get("chg_dtl_cn"))).contains("META_UPDATED");
    }

    // ---------------------------------------------------------------------------------------
    // 시드 헬퍼
    // ---------------------------------------------------------------------------------------

    private LsDatasetVideoMeta activeSnapshot(long rawSn) {
        List<LsDatasetVideoMeta> active =
                metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES);
        assertThat(active).hasSize(1);
        return active.get(0);
    }

    /** APPROVED 영상 + LS_EVNT_TYPE + video.* 메타 시드. 촬영환경 3필드는 인자로 받은 라이브 수동값. */
    private long seedApprovedVideo(String dayNgtCd, String sesnCd, String wthrNm) {
        long nano = System.nanoTime();
        String clipId = "CLIP-" + nano;
        String cctvId = "CCTV-" + nano;
        String lclgvCd = "LG-" + (nano % 100000);
        String evntCd = "EV-" + (nano % 100000);
        seededEvntCds.add(evntCd);

        Long rawSn = jdbc.queryForObject(
                "INSERT INTO LS_DATA_RAW (VMS_CLIP_ID, VMS_CCTV_ID, EVNT_TYPE_CD, LCLGV_CD, "
                        + "PRVC_TYPE_CD, PRVC_YN, DE_IDENT_YN, RAW_FILE_PATH_NM, SHT_DT, VDO_LEN_SEC, "
                        + "ORGNL_RAW_SN, DATA_STTS_CD, DAY_NGT_CD, SESN_CD, WTHR_NM, REG_DT) "
                        + "VALUES (?, ?, ?, ?, 'PRVC', 'Y', 'Y', ?, ?, 30, NULL, 'COMPLETED', ?, ?, ?, ?) "
                        + "RETURNING RAW_SN",
                Long.class,
                clipId, cctvId, evntCd, lclgvCd, "/nas/raw/" + nano + ".mp4",
                // 촬영일시 22:00 — 구 파생 규칙이었다면 NGT 로 채워졌을 시각(정정 후에도 null 이어야 한다).
                LocalDateTime.of(2026, 7, 15, 22, 0),
                dayNgtCd, sesnCd, wthrNm, LocalDateTime.now());
        seededRawSns.add(rawSn);

        jdbc.update("INSERT INTO LS_RAW_DATA_STATUS (RAW_DATA_ID, DATA_STTS_CD, UPD_DT, VER) "
                + "VALUES (?, 'APPROVED', ?, 2)", rawSn, ORIGINAL_APPROVED_AT);

        // 관제 인입 평면값 시드 — CCTV명·좌표·파일형식의 유일한 조달처(V167 — 구 MNG_* 마스터 제거).
        //   ★조인 축이 VMS_CCTV_ID/LCLGV_CD 가 아니라 RAW_SN 이다(IngestSourceLink).
        //   지자체명(LCLGV_NM)은 넣되 동결 스냅샷의 sidoNm/sggNm 은 상수 null 이다 — 인입은 지역명을
        //   1필드로만 주고 그 입도가 계약으로 확정되지 않아 시도 전용 필드에 넣지 않는다.
        seedIngestFlatValues(rawSn, cctvId);
        jdbc.update("INSERT INTO LS_EVNT_TYPE (EVNT_TYPE_CD, EVNT_NM, EVNT_CLSF_CD, CLCT_YN) "
                + "VALUES (?, '보행자 감지', 'A', 'Y')", evntCd);

        jdbc.update("INSERT INTO LS_DATA_META (RAW_SN, META_KEY, META_VL, RTRY_NMTM, REG_DT) "
                + "VALUES (?, 'video.resolution', '1920x1080', 0, ?)", rawSn, LocalDateTime.now());
        return rawSn;
    }

    /**
     * 배포 이전에 동결된 <b>레거시 활성 스냅샷</b>을 직접 적재한다(파생 폴백 시절 산물 재현).
     * 해시는 materialize 가 계산할 새 해시와 절대 겹치지 않도록 고정 접두를 쓴다.
     */
    private void seedLegacySnapshot(long rawSn, String dayNgtCd, String sesnCd, String wthrNm) {
        jdbc.update("INSERT INTO LS_DATASET_VIDEO_META (RAW_SN, SNPSHT_HASH, ACTIVE_YN, "
                        + "DAY_NGT_CD, SESN_CD, WTHR_NM, RVW_CMPL_DT, REG_DT) "
                        + "VALUES (?, ?, 'Y', ?, ?, ?, ?, ?)",
                rawSn, "legacy-" + rawSn, dayNgtCd, sesnCd, wthrNm,
                ORIGINAL_APPROVED_AT, LocalDateTime.now());
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
