package kr.co.cudo.authoring.dataset.service;

import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnno;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnnoReview;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoRepository;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoReviewRepository;
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
 * Phase 2 DEV_FIX — event_annotation 지연 동결 <b>치유</b>({@link DatasetVideoMetaBackfillService#healMissingEventAnnotation()})
 * 실 DB 통합 테스트(HIGH — 사용자 지목 rawSn 24 상황).
 *
 * <p>버그 창에서 이미 검수 승인(APPROVED)됐고 활성 스냅샷은 존재하지만 {@code EVNT_ANNO_CN=NULL} 로 동결돼
 * export 에서 event_annotation 이 영구 누락되는 영상을 소급 치유(autoApprove→재동결)하는지 검증한다.
 * 기존 백필({@code findApprovedWithoutActiveSnapshot})은 활성 스냅샷이 이미 있어 이 영상을 제외한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class DatasetVideoMetaEventAnnoHealIT {

    @Autowired
    private DatasetVideoMetaBackfillService backfillService;

    @Autowired
    private DatasetVideoMetaSnapshotService snapshotService;

    @Autowired
    private LsDatasetVideoMetaRepository metaRepository;

    @Autowired
    private LsEvntAnnoRepository annoRepository;

    @Autowired
    private LsEvntAnnoReviewRepository reviewRepository;

    private final JdbcTemplate jdbc;

    private final List<Long> seededRawSns = new ArrayList<>();
    private final List<String> seededEvntCds = new ArrayList<>();

    DatasetVideoMetaEventAnnoHealIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void cleanup() {
        for (Long rawSn : seededRawSns) {
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
            jdbc.update("DELETE FROM MNG_EX_EVNT_TYPE WHERE EVNT_TYPE_CD = ?", evntCd);
        }
    }

    /** LS_DATA_RAW + MNG_* + video.* 메타 시드 + LS_RAW_DATA_STATUS(APPROVED). 스냅샷은 미생성. */
    private long seedApprovedVideo() {
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
                        + "VALUES (?, ?, ?, ?, 'PRVC', 'Y', 'Y', ?, ?, 30, NULL, 'COMPLETED', ?) "
                        + "RETURNING RAW_SN",
                Long.class,
                clipId, cctvId, evntCd, lclgvCd, "/nas/raw/" + nano + ".mp4",
                LocalDateTime.of(2026, 1, 15, 22, 0), LocalDateTime.now());
        seededRawSns.add(rawSn);

        jdbc.update("INSERT INTO LS_RAW_DATA_STATUS (RAW_DATA_ID, DATA_STTS_CD, UPD_DT, VER) "
                + "VALUES (?, 'APPROVED', ?, 2)", rawSn, LocalDateTime.of(2026, 2, 1, 10, 0));

        // 관제 인입 평면값 시드 — CCTV명·좌표·파일형식의 유일한 조달처(V167 — 구 MNG_* 마스터 제거).
        //   ★조인 축이 VMS_CCTV_ID/LCLGV_CD 가 아니라 RAW_SN 이다(IngestSourceLink).
        //   지자체명(RGN_NM)은 넣되 동결 스냅샷의 sidoNm/sggNm 은 상수 null 이다 — 인입은 지역명을
        //   1필드로만 주고 그 입도가 계약으로 확정되지 않아 시도 전용 필드에 넣지 않는다.
        seedIngestFlatValues(rawSn, cctvId);
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

    /** event_annotation + 검토 row(지정 상태) 시드. */
    private Long seedEventAnno(long rawSn, String reviewStatus) {
        LsEvntAnno anno = annoRepository.saveAndFlush(
                LsEvntAnno.create(rawSn, "{\"caption\":\"보행자가 횡단보도를 건넌다\"}", "vlm"));
        reviewRepository.saveAndFlush(LsEvntAnnoReview.createAuto(
                anno.getEvntAnnoSn(), LsEvntAnnoReview.META_TYPE_VLM, reviewStatus, "system"));
        return anno.getEvntAnnoSn();
    }

    /** 활성 스냅샷의 EVNT_ANNO_CN 을 조회한다(활성 1건 전제). */
    private String activeEventAnnoCn(long rawSn) {
        List<LsDatasetVideoMeta> active =
                metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES);
        assertThat(active).hasSize(1);
        return active.get(0).getEvntAnnoCn();
    }

    private String latestReviewStatus(Long evntAnnoSn) {
        return reviewRepository.findByEvntAnnoSn(evntAnnoSn).get(0).getRvwSttsCd();
    }

    @Test
    @DisplayName("백필치유_이미승인_EVNT_ANNO_CN_null_영상을_autoApprove후_재동결한다")
    void heal_reFreezesApprovedVideoWithNullEventAnno() {
        // given — rawSn 24 상황: APPROVED 영상 + AUTO_GENERATED event_annotation + EVNT_ANNO_CN null 로 동결된 활성 스냅샷
        long rawSn = seedApprovedVideo();
        Long evntAnnoSn = seedEventAnno(rawSn, LsEvntAnnoReview.STTS_AUTO_GENERATED);
        snapshotService.materialize(rawSn); // 미승인 event_annotation → EVNT_ANNO_CN=null 로 동결
        assertThat(activeEventAnnoCn(rawSn)).isNull(); // precondition — 누락 상태 재현

        // when
        int healed = backfillService.healMissingEventAnnotation();

        // then — 활성 스냅샷 EVNT_ANNO_CN 이 채워지고(non-null) review 는 APPROVED 로 전이
        assertThat(healed).isGreaterThanOrEqualTo(1);
        assertThat(activeEventAnnoCn(rawSn)).isNotNull().contains("보행자");
        assertThat(latestReviewStatus(evntAnnoSn)).isEqualTo(LsEvntAnnoReview.STTS_APPROVED);
    }

    @Test
    @DisplayName("백필치유_REJECTED_review_영상은_치유_제외")
    void heal_skipsRejectedReviewVideo() {
        // given — 명시 반려된 event_annotation
        long rawSn = seedApprovedVideo();
        Long evntAnnoSn = seedEventAnno(rawSn, LsEvntAnnoReview.STTS_REJECTED);
        snapshotService.materialize(rawSn); // REJECTED → EVNT_ANNO_CN null 동결
        assertThat(activeEventAnnoCn(rawSn)).isNull();

        // when
        backfillService.healMissingEventAnnotation();

        // then — 치유 제외(여전히 null, review REJECTED 유지)
        assertThat(activeEventAnnoCn(rawSn)).isNull();
        assertThat(latestReviewStatus(evntAnnoSn)).isEqualTo(LsEvntAnnoReview.STTS_REJECTED);
    }

    @Test
    @DisplayName("백필치유_event_annotation_없는_영상은_대상아님")
    void heal_ignoresVideoWithoutEventAnno() {
        // given — event_annotation 자체가 없는 APPROVED 영상(정상 null)
        long rawSn = seedApprovedVideo();
        snapshotService.materialize(rawSn); // event_annotation 없음 → EVNT_ANNO_CN null
        assertThat(activeEventAnnoCn(rawSn)).isNull();

        // when
        backfillService.healMissingEventAnnotation();

        // then — 대상 아님(여전히 null, 스냅샷 1건 유지)
        assertThat(activeEventAnnoCn(rawSn)).isNull();
        assertThat(metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES)).hasSize(1);
    }

    @Test
    @DisplayName("백필치유_이미_EVNT_ANNO_CN_채워진_영상은_멱등_skip")
    void heal_isIdempotentWhenEventAnnoAlreadyFrozen() {
        // given — 승인된 event_annotation 이 이미 EVNT_ANNO_CN 에 동결된 영상
        long rawSn = seedApprovedVideo();
        seedEventAnno(rawSn, LsEvntAnnoReview.STTS_APPROVED);
        snapshotService.materialize(rawSn); // APPROVED → EVNT_ANNO_CN 채워짐
        String before = activeEventAnnoCn(rawSn);
        assertThat(before).isNotNull();
        List<LsDatasetVideoMeta> beforeAll = metaRepository.findByRawSn(rawSn);

        // when — 두 번 실행
        backfillService.healMissingEventAnnotation();
        backfillService.healMissingEventAnnotation();

        // then — 대상에서 제외(스냅샷 버전 append 없이 동일 유지, 멱등)
        assertThat(activeEventAnnoCn(rawSn)).isEqualTo(before);
        assertThat(metaRepository.findByRawSn(rawSn)).hasSameSizeAs(beforeAll);
    }

    @Test
    @DisplayName("findByEvntAnnoSn_중복_review시_최신_RVW_SN_선택")
    void findByEvntAnnoSn_returnsLatestRvwSnFirst() {
        // given — 같은 event_annotation 에 검토 row 2건(UNIQUE 없음 — 중복 시나리오)
        long rawSn = seedApprovedVideo();
        LsEvntAnno anno = annoRepository.saveAndFlush(
                LsEvntAnno.create(rawSn, "{\"caption\":\"x\"}", "vlm"));
        LsEvntAnnoReview older = reviewRepository.saveAndFlush(LsEvntAnnoReview.createAuto(
                anno.getEvntAnnoSn(), LsEvntAnnoReview.META_TYPE_VLM, LsEvntAnnoReview.STTS_REJECTED, "old"));
        LsEvntAnnoReview newer = reviewRepository.saveAndFlush(LsEvntAnnoReview.createAuto(
                anno.getEvntAnnoSn(), LsEvntAnnoReview.META_TYPE_VLM, LsEvntAnnoReview.STTS_PENDING, "new"));

        // when
        List<LsEvntAnnoReview> found = reviewRepository.findByEvntAnnoSn(anno.getEvntAnnoSn());

        // then — 최신 RVW_SN(newer) 이 첫 번째로 결정적 선택된다
        assertThat(found).hasSize(2);
        assertThat(newer.getRvwSn()).isGreaterThan(older.getRvwSn());
        assertThat(found.get(0).getRvwSn()).isEqualTo(newer.getRvwSn());
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
                        + " RCPTN_DT, PROC_STTS_CD, CCTV_NM, WGS84_LAT, WGS84_LOT, FILE_FMT, RGN_NM) "
                        + "VALUES (?, ?, ?, 'clip.mp4', '/nas/raw/clip.mp4', 'ORIGINAL', "
                        + "        CURRENT_TIMESTAMP, 'DONE', ?, ?, ?, 'mp4', ?)",
                rawSn, "ING-" + rawSn, cctvId, "교차로 CCTV",
                37.5665000, 126.9780000, "서울특별시 중구");
    }

}
