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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4 DEV_FIX — 데이터마트 백필({@link DatasetVideoMetaBackfillService}) <b>실 DB 통합 테스트</b>.
 *
 * <p>배포 이전 이미 APPROVED 였던(활성 스냅샷 없는) 영상을 소급 동결해 재정의된 {@code V_COMPLETED_VIDEO}
 * 에서 소실되지 않도록 하는 백필의 정확성·멱등·필터를 검증한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class DatasetVideoMetaBackfillServiceIT {

    @Autowired
    private DatasetVideoMetaBackfillService backfillService;

    @Autowired
    private LsDatasetVideoMetaRepository metaRepository;

    private final JdbcTemplate jdbc;

    private final List<Long> seededRawSns = new ArrayList<>();
    private final List<String> seededCctvIds = new ArrayList<>();
    private final List<String> seededLclgvCds = new ArrayList<>();
    private final List<String> seededEvntCds = new ArrayList<>();

    DatasetVideoMetaBackfillServiceIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanup() {
        for (Long rawSn : seededRawSns) {
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
    }

    /** LS_DATA_RAW + MNG_* + video.* 메타 시드 + LS_RAW_DATA_STATUS(지정 상태·UPD_DT). 스냅샷은 미생성. */
    private long seedApprovedVideoWithoutSnapshot(String sttsCd, LocalDateTime updDt) {
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
                        + "VALUES (?, ?, ?, ?, 'PRVC', 'Y', 'Y', ?, ?, 30, NULL, 'COMPLETED', ?) "
                        + "RETURNING RAW_SN",
                Long.class,
                clipId, cctvId, evntCd, lclgvCd, "/nas/raw/" + nano + ".mp4",
                LocalDateTime.of(2026, 1, 15, 22, 0), LocalDateTime.now());
        seededRawSns.add(rawSn);

        jdbc.update("INSERT INTO LS_RAW_DATA_STATUS (RAW_DATA_ID, DATA_STTS_CD, UPD_DT, VER) "
                + "VALUES (?, ?, ?, 2)", rawSn, sttsCd, updDt);

        jdbc.update("INSERT INTO MNG_RESOURCE_CCTV (VMS_CCTV_ID, CCTV_NM, WGS84_LAT, WGS84_LOT, USE_YN) "
                + "VALUES (?, ?, ?, ?, 'Y')", cctvId, "교차로 CCTV", 37.5665000, 126.9780000);
        jdbc.update("INSERT INTO MNG_EX_LOCAL_GOV (LCLGV_CD, SIDO_NM, SGG_NM, USE_YN) "
                + "VALUES (?, '서울특별시', '중구', 'Y')", lclgvCd);
        jdbc.update("INSERT INTO MNG_EX_EVNT_TYPE (EVNT_TYPE_CD, EVNT_CLS_CD, EVNT_CTGRY_CD, CLCT_EVNT_NM, CLCT_YN) "
                + "VALUES (?, 'A', 'B001', '보행자 감지', 'Y')", evntCd);

        seedMeta(rawSn, "video.codec", "h264");
        seedMeta(rawSn, "video.fps", "25");
        seedMeta(rawSn, "video.resolution", "1920x1080");
        return rawSn;
    }

    private void seedMeta(Long rawSn, String key, String value) {
        jdbc.update("INSERT INTO LS_DATA_META (RAW_SN, META_KEY, META_VL, RTRY_NMTM, REG_DT) "
                + "VALUES (?, ?, ?, 0, ?)", rawSn, key, value, LocalDateTime.now());
    }

    @Test
    @DisplayName("백필_기존APPROVED영상_스냅샷생성_뷰노출")
    void backfill_materializesLegacyApprovedVideo_andExposesInView() {
        // given — 스냅샷 없는 APPROVED 영상 + 과거 승인 시각
        LocalDateTime approvedAt = LocalDateTime.of(2026, 2, 1, 10, 0);
        long rawSn = seedApprovedVideoWithoutSnapshot("APPROVED", approvedAt);

        // when
        int done = backfillService.backfill();

        // then — 활성 스냅샷 1건 생성 + RVW_CMPL_DT 는 과거 승인 시각(now 아님) + MNG 동결
        assertThat(done).isGreaterThanOrEqualTo(1);
        List<LsDatasetVideoMeta> active =
                metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES);
        assertThat(active).hasSize(1);
        LsDatasetVideoMeta m = active.get(0);
        assertThat(m.getRvwCmplDt()).isEqualTo(approvedAt);   // 소급 승인 시각
        assertThat(m.getCctvNm()).isEqualTo("교차로 CCTV");
        assertThat(m.getVdoCdc()).isEqualTo("h264");

        // 재정의된 뷰에도 노출(라이브 APPROVED 게이트 통과).
        Integer inView = jdbc.queryForObject(
                "SELECT COUNT(*) FROM V_COMPLETED_VIDEO WHERE RAW_SN = ?", Integer.class, rawSn);
        assertThat(inView).isEqualTo(1);
    }

    @Test
    @DisplayName("백필_멱등_재실행_중복없음")
    void backfill_isIdempotentOnReRun() {
        // given
        long rawSn = seedApprovedVideoWithoutSnapshot("APPROVED", LocalDateTime.of(2026, 2, 1, 10, 0));

        // when — 두 번 실행
        backfillService.backfill();
        backfillService.backfill();

        // then — 활성 스냅샷 정확히 1건(중복 삽입 없음)
        List<LsDatasetVideoMeta> all = metaRepository.findByRawSn(rawSn);
        assertThat(all).filteredOn(r -> LsDatasetVideoMeta.ACTIVE_YES.equals(r.getActiveYn())).hasSize(1);
        assertThat(all).hasSize(1);
    }

    @Test
    @DisplayName("백필_비APPROVED_영상_제외")
    void backfill_skipsNonApprovedVideo() {
        // given — 라이브 상태가 PENDING(미승인) 인 영상
        long rawSn = seedApprovedVideoWithoutSnapshot("PENDING", LocalDateTime.of(2026, 2, 1, 10, 0));

        // when
        backfillService.backfill();

        // then — 스냅샷이 생성되지 않음
        List<LsDatasetVideoMeta> all = metaRepository.findByRawSn(rawSn);
        assertThat(all).isEmpty();
    }
}
