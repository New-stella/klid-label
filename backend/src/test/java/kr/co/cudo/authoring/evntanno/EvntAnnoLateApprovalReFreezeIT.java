package kr.co.cudo.authoring.evntanno;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.DatasetExportService;
import kr.co.cudo.authoring.dataset.export.ExportFileNaming;
import kr.co.cudo.authoring.dataset.export.ExportKind;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.dataset.service.DatasetVideoMetaSnapshotService;
import kr.co.cudo.authoring.evntanno.service.EvntAnnoReviewService;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>event_annotation 지연 승인 재동결</b> 순서 의존 통합 테스트(PostgreSQL Testcontainer) — HIGH 결함 수정.
 *
 * <p>검증 대상: 영상 검수 승인이 event_annotation 승인보다 <b>먼저</b> 일어나면, 그 시점 동결본은
 * {@code EVNT_ANNO_CN=null} 이다. 이후 event_annotation 이 뒤늦게 승인될 때 재동결이 없으면 export JSON 의
 * {@code event_annotation} 이 영구 null 로 조용히 누락된다. {@link EvntAnnoReviewService#approve} 가
 * 영상이 이미 APPROVED 면 재동결을 트리거해 이를 막는지, 역순/반려에서는 중복 트리거가 없는지 확인한다.
 *
 * <p>결정성 확보 — {@code authoring.dataset-export.enabled=false} 로 AFTER_COMMIT 비동기 export 브릿지를
 * 끄고(그 배선은 {@code DatasetExportBridgeTest} 가 커버), export 반영은 {@link DatasetExportService#export}
 * 를 동기 직접 호출해 검증한다({@code DatasetExportE2EIT} 와 동일 근거).
 */
@SpringBootTest
@ActiveProfiles("local")
class EvntAnnoLateApprovalReFreezeIT {

    private static final int FRAME_COUNT = 1;

    private static final String EVENT_ANNO_PAYLOAD =
            "{\"event_class\":\"assault\",\"question\":\"무슨 일?\","
                    + "\"caption\":{\"c1\":{\"caption_text\":\"다툼\",\"cot\":[\"a\",\"b\"]}},"
                    + "\"answer\":\"폭행\","
                    + "\"evidence\":{\"c1\":{\"evidence_text\":\"주먹\",\"obj_id\":[\"o1\"]}}}";

    private static final Path STORAGE_ROOT = createStorageRoot();
    private static final Path LABELING_ROOT = STORAGE_ROOT.resolve("labeling");
    private static final Path RAW_ROOT = STORAGE_ROOT.resolve("raw");
    /** 원본 영상 디렉터리(관제 NAS 모사) — co-locate 산출 base. */
    private static final Path VIDEO_DIR = RAW_ROOT.resolve("videos");
    private static final Path DEID_ROOT = STORAGE_ROOT.resolve("deid");

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("evntanno-refreeze-it");
        } catch (IOException e) {
            throw new IllegalStateException("임시 스토리지 생성 실패", e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("authoring.storage.labeling-path", LABELING_ROOT::toString);
        registry.add("authoring.storage.raw-path", RAW_ROOT::toString);
        registry.add("authoring.storage.deidentified-path", DEID_ROOT::toString);
        // 비동기 export 브릿지 비활성 — 동기 export 호출과의 race 배제(결정성).
        registry.add("authoring.dataset-export.enabled", () -> "false");
    }

    @Autowired private DatasetVideoMetaSnapshotService snapshotService;
    @Autowired private EvntAnnoReviewService reviewService;
    @Autowired private DatasetExportService exportService;
    @Autowired private LsDatasetVideoMetaRepository metaRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private LsLabelRepository labelMasterRepository;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final java.util.List<Long> seededRawSns = new java.util.ArrayList<>();
    private final java.util.List<Long> seededLabelIds = new java.util.ArrayList<>();
    private final java.util.List<String> seededEvntCds = new java.util.ArrayList<>();

    EvntAnnoLateApprovalReFreezeIT(
            @Qualifier("controlDataSource") DataSource dataSource,
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    @AfterEach
    void cleanup() {
        for (Long rawSn : seededRawSns) {
            jdbc.update("DELETE FROM LS_DATASET_EXPORT WHERE DATA_RAW_SN = ?", rawSn);
            jdbc.update("DELETE FROM LS_DATA_LBL WHERE SRC_SN IN "
                    + "(SELECT SRC_SN FROM LS_DATA_SRC WHERE RAW_SN = ?)", rawSn);
            jdbc.update("DELETE FROM LS_DATA_SRC WHERE RAW_SN = ?", rawSn);
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
        if (!seededLabelIds.isEmpty()) {
            txTemplate.executeWithoutResult(s -> labelMasterRepository.deleteAllById(seededLabelIds));
        }
        for (String evntCd : seededEvntCds) {
            jdbc.update("DELETE FROM LS_EVNT_TYPE WHERE EVNT_TYPE_CD = ?", evntCd);
        }
    }

    private TokenClaims reviewer() {
        return new TokenClaims("11", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    /** materialize 소스(LS_DATA_RAW + MNG_* + LS_DATA_META video.*) 를 시드하고 rawSn 반환. */
    private long seedSource() {
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
                clipId, cctvId, evntCd, lclgvCd, originalVideoPath(nano),
                LocalDateTime.of(2026, 1, 15, 22, 0), LocalDateTime.now());
        seededRawSns.add(rawSn);

        // 관제 인입 평면값 시드 — CCTV명·좌표·파일형식의 유일한 조달처(V167 — 구 MNG_* 마스터 제거).
        //   ★조인 축이 VMS_CCTV_ID/LCLGV_CD 가 아니라 RAW_SN 이다(IngestSourceLink).
        //   지자체명(RGN_NM)은 넣되 동결 스냅샷의 sidoNm/sggNm 은 상수 null 이다 — 인입은 지역명을
        //   1필드로만 주고 그 입도가 계약으로 확정되지 않아 시도 전용 필드에 넣지 않는다.
        seedIngestFlatValues(rawSn, cctvId);
        jdbc.update("INSERT INTO LS_EVNT_TYPE (EVNT_TYPE_CD, EVNT_NM, EVNT_CLSF_CD, CLCT_YN) "
                + "VALUES (?, '보행자', 'A', 'Y')", evntCd);

        seedMeta(rawSn, "video.resolution", "1920x1080");
        seedMeta(rawSn, "video.fps", "25");
        return rawSn;
    }

    private void seedMeta(Long rawSn, String key, String value) {
        jdbc.update("INSERT INTO LS_DATA_META (RAW_SN, META_KEY, META_VL, RTRY_NMTM, REG_DT) "
                + "VALUES (?, ?, ?, 0, ?)", rawSn, key, value, LocalDateTime.now());
    }

    /** event_annotation payload + 검토상태 시드. 반환은 EVNT_ANNO_SN. */
    private long seedEventAnnotation(Long rawSn, String rvwSttsCd) {
        Long annoSn = jdbc.queryForObject(
                "INSERT INTO LS_EVNT_ANNO (RAW_SN, ANNO_CN, REG_ID, REG_DT) "
                        + "VALUES (?, CAST(? AS jsonb), 'tester', ?) RETURNING EVNT_ANNO_SN",
                Long.class, rawSn, EVENT_ANNO_PAYLOAD, LocalDateTime.now());
        jdbc.update("INSERT INTO LS_EVNT_ANNO_REVIEW (EVNT_ANNO_SN, RVW_STTS_CD, META_TYPE_CD, REG_DT, VER) "
                + "VALUES (?, ?, 'VLM', ?, 0)", annoSn, rvwSttsCd, LocalDateTime.now());
        return annoSn;
    }

    /** LS_RAW_DATA_STATUS row 시드(작업/검수 워크플로우 상태). */
    private void seedRawDataStatus(Long rawSn, String sttsCd) {
        jdbc.update("INSERT INTO LS_RAW_DATA_STATUS (RAW_DATA_ID, DATA_STTS_CD, STP_CYCL, IGI_CYCL, UPD_DT, VER) "
                + "VALUES (?, ?, 0, 0, ?, 0)", rawSn, sttsCd, LocalDateTime.now());
    }

    private void updateRawDataStatus(Long rawSn, String sttsCd) {
        jdbc.update("UPDATE LS_RAW_DATA_STATUS SET DATA_STTS_CD = ?, UPD_DT = ? WHERE RAW_DATA_ID = ?",
                sttsCd, LocalDateTime.now(), rawSn);
    }

    /** 프레임(원본/비식별 이미지 fixture) + BBOX 라벨 시드 — export 산출 대상. */
    private void seedFramesWithLabels(long rawSn) {
        Long labelId = txTemplate.execute(s -> labelMasterRepository.save(
                LsLabel.create("car-" + System.nanoTime(), "#ff0000", "BBOX", 1, "tester")).getLabelId());
        seededLabelIds.add(labelId);
        txTemplate.executeWithoutResult(s -> {
            for (int i = 0; i < FRAME_COUNT; i++) {
                String rawRel = "frames/raw/" + rawSn + "/frame-" + i + ".jpg";
                String deidRel = "frames/deid/" + rawSn + "/frame-" + i + ".jpg";
                writeDummyImage(RAW_ROOT.resolve(rawRel));
                writeDummyImage(DEID_ROOT.resolve(deidRel));
                LsDataSrc frame = LsDataSrc.create(rawSn, i, rawRel, LocalDateTime.now());
                frame.attachDeidPath(deidRel);
                frame.updateDescription("설명");
                frame = srcRepository.save(frame);
                labelRepository.save(LsDataLbl.createManual(
                        frame.getSrcSn(), "BBOX", labelId, "car", "[[1,2],[3,4]]", null));
            }
        });
    }

    private static void writeDummyImage(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
        } catch (IOException e) {
            throw new IllegalStateException("더미 이미지 생성 실패", e);
        }
    }

    /**
     * 원본 영상 절대경로 — Phase 5A co-locate 산출 base({@code dirname(원본)}) 의 원천이므로
     * 허용 마운트 루트(raw-path) 하위여야 한다.
     */
    private static String originalVideoPath(long nano) {
        Path video = VIDEO_DIR.resolve("clip-" + nano + ".mp4");
        try {
            Files.createDirectories(VIDEO_DIR);
            Files.write(video, new byte[]{0x00, 0x11});
        } catch (IOException e) {
            throw new IllegalStateException("더미 원본 영상 생성 실패", e);
        }
        return video.toString();
    }

    /** Phase 5A — 산출 루트는 원본 영상 디렉터리 하위 {@code {rawSn}/} 이다. */
    private Path versionDir(long rawSn, int version, ExportKind kind) {
        return VIDEO_DIR.resolve(String.valueOf(rawSn))
                .resolve("v" + version)
                .resolve(kind.segment());
    }

    private String activeEvntAnnoCn(long rawSn) {
        return txTemplate.execute(s ->
                metaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES).get(0).getEvntAnnoCn());
    }

    private int snapshotVersionCount(long rawSn) {
        return txTemplate.execute(s -> metaRepository.findByRawSn(rawSn)).size();
    }

    @Test
    @DisplayName("영상승인후_event_annotation_지연승인시_스냅샷_재동결되어_export에_반영된다")
    void lateApproval_reFreezesSnapshotAndReflectsInExport() throws IOException {
        // given — 영상이 먼저 검수 승인(APPROVED)됐고, 그 시점 event_annotation 은 PENDING 이었다.
        long rawSn = seedSource();
        seedEventAnnotation(rawSn, "PENDING");
        seedRawDataStatus(rawSn, "APPROVED");
        seedFramesWithLabels(rawSn);
        // 영상 승인 시점 동결(PENDING 이라 EVNT_ANNO_CN=null 로 동결됨)
        txTemplate.executeWithoutResult(s -> snapshotService.materialize(rawSn));
        assertThat(activeEvntAnnoCn(rawSn)).as("영상 선승인 시점 동결본은 null").isNull();

        // when — event_annotation 지연 승인
        reviewService.approve(rawSn, reviewer());

        // then — 활성 스냅샷이 재동결되어 event_annotation 이 채워진다(수정 전이면 null 로 FAIL).
        assertThat(activeEvntAnnoCn(rawSn)).as("지연 승인 후 재동결본").isNotNull().contains("assault");
        // null 동결본(비활성) + 재동결본(활성) → 스냅샷 2버전.
        assertThat(snapshotVersionCount(rawSn)).isEqualTo(2);

        // 그리고 export JSON 에 실제로 반영된다(재동결본 pass-through).
        exportService.export(rawSn);
        JsonNode doc = objectMapper.readTree(
                versionDir(rawSn, 1, ExportKind.ORIGINAL).resolve(ExportFileNaming.jsonFileName(0)).toFile());
        JsonNode ea = doc.get("event");
        assertThat(ea).isNotNull();
        assertThat(ea.path("event_class").asText()).isEqualTo("assault");
        assertThat(ea.path("caption").path("c1").path("caption_text").asText()).isEqualTo("다툼");
    }

    @Test
    @DisplayName("event_annotation_먼저승인_후_영상승인시_정상_동결되고_재동결_중복안됨")
    void eventAnnotationFirstThenVideoApprove_normalFreezeNoDuplicate() {
        // given — 영상은 아직 미승인(IN_REVIEW), event_annotation 은 PENDING.
        long rawSn = seedSource();
        seedEventAnnotation(rawSn, "PENDING");
        seedRawDataStatus(rawSn, "IN_REVIEW");

        // when(1) — event_annotation 먼저 승인. 영상 미승인이라 재동결 트리거 없음.
        reviewService.approve(rawSn, reviewer());

        // then(1) — 아직 스냅샷이 생성되지 않았다(재동결 미트리거).
        assertThat(snapshotVersionCount(rawSn)).isZero();

        // when(2) — 이후 영상 검수 승인(materialize) — 이미 승인된 event_annotation 을 정상 캡처.
        updateRawDataStatus(rawSn, "APPROVED");
        txTemplate.executeWithoutResult(s -> snapshotService.materialize(rawSn));

        // then(2) — 활성 스냅샷 1건에 event_annotation 정상 동결, 중복 버전 없음(단일 버전).
        assertThat(activeEvntAnnoCn(rawSn)).isNotNull().contains("assault");
        assertThat(snapshotVersionCount(rawSn)).isEqualTo(1);
    }

    // ---- F: 영상 검수 승인 시 event_annotation 자동 확정(autoApproveOnVideoApproval) + 동결 ----

    @Test
    @DisplayName("영상승인_자동전이_후_같은tx_materialize시_EVNT_ANNO_CN이_동결된다")
    void autoApprove_thenMaterializeInSameTx_freezesEvntAnnoCn() {
        // given — 영상 검수 승인 진행 중(IN_REVIEW), event_annotation 은 AUTO_GENERATED(미승인).
        long rawSn = seedSource();
        seedEventAnnotation(rawSn, "AUTO_GENERATED");
        seedRawDataStatus(rawSn, "IN_REVIEW");

        // when — ReviewService.approve 와 동일하게 자동 승인 → 같은 트랜잭션에서 materialize.
        txTemplate.executeWithoutResult(s -> {
            reviewService.autoApproveOnVideoApproval(rawSn, reviewer());
            snapshotService.materialize(rawSn);
        });

        // then — 자동 승인 flush 로 materialize 가 APPROVED event_annotation 을 관측 → 동결본에 반영.
        assertThat(activeEvntAnnoCn(rawSn)).as("자동 승인 후 동결본").isNotNull().contains("assault");
        assertThat(snapshotVersionCount(rawSn)).isEqualTo(1);
    }

    @Test
    @DisplayName("REJECTED_event_annotation은_자동승인_제외되어_동결안됨")
    void autoApprove_rejectedIsExcluded_notFrozen() {
        // given — event_annotation 이 REVIEWER 에게 명시 반려(REJECTED)된 상태.
        long rawSn = seedSource();
        seedEventAnnotation(rawSn, "REJECTED");
        seedRawDataStatus(rawSn, "IN_REVIEW");

        // when
        txTemplate.executeWithoutResult(s -> {
            reviewService.autoApproveOnVideoApproval(rawSn, reviewer());
            snapshotService.materialize(rawSn);
        });

        // then — 반려 존중: 자동 승인되지 않아 EVNT_ANNO_CN 은 여전히 null.
        assertThat(activeEvntAnnoCn(rawSn)).isNull();
    }

    @Test
    @DisplayName("event_annotation_없는_영상_자동전이는_정상_no_op_동결")
    void autoApprove_noEventAnnotation_isNoOp() {
        // given — event_annotation 이 없는 영상.
        long rawSn = seedSource();
        seedRawDataStatus(rawSn, "IN_REVIEW");

        // when / then — 예외 없이 정상 동결(EVNT_ANNO_CN null).
        txTemplate.executeWithoutResult(s -> {
            reviewService.autoApproveOnVideoApproval(rawSn, reviewer());
            snapshotService.materialize(rawSn);
        });
        assertThat(snapshotVersionCount(rawSn)).isEqualTo(1);
        assertThat(activeEvntAnnoCn(rawSn)).isNull();
    }

    @Test
    @DisplayName("이미_APPROVED면_멱등_재전이없이_동결된다")
    void autoApprove_alreadyApproved_isIdempotentAndFrozen() {
        // given — event_annotation 이 이미 개별 승인(APPROVED)된 상태.
        long rawSn = seedSource();
        seedEventAnnotation(rawSn, "APPROVED");
        seedRawDataStatus(rawSn, "IN_REVIEW");

        // when — 멱등 skip(재전이 없이 통과) 후 materialize 가 APPROVED 를 동결.
        txTemplate.executeWithoutResult(s -> {
            reviewService.autoApproveOnVideoApproval(rawSn, reviewer());
            snapshotService.materialize(rawSn);
        });

        // then — 정상 동결.
        assertThat(activeEvntAnnoCn(rawSn)).isNotNull().contains("assault");
        assertThat(snapshotVersionCount(rawSn)).isEqualTo(1);
    }

    @Test
    @DisplayName("반려는_export_재동결을_트리거하지_않는다")
    void reject_doesNotTriggerReFreeze() {
        // given — 영상 승인(APPROVED) + event_annotation PENDING, 선동결(null).
        long rawSn = seedSource();
        seedEventAnnotation(rawSn, "PENDING");
        seedRawDataStatus(rawSn, "APPROVED");
        txTemplate.executeWithoutResult(s -> snapshotService.materialize(rawSn));
        assertThat(activeEvntAnnoCn(rawSn)).isNull();

        // when — event_annotation 반려
        reviewService.reject(rawSn, "근거 불충분", reviewer());

        // then — 재동결 없음: 활성 스냅샷 여전히 null, 버전 1건 그대로.
        assertThat(activeEvntAnnoCn(rawSn)).isNull();
        assertThat(snapshotVersionCount(rawSn)).isEqualTo(1);
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
