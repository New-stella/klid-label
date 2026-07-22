package kr.co.cudo.authoring.dataset.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.entity.LsDatasetExport;
import kr.co.cudo.authoring.dataset.export.repository.LsDatasetExportRepository;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검수 승인 → 학습데이터 파일 산출의 <b>end-to-end 통합 테스트</b>(PostgreSQL Testcontainer).
 *
 * <p>{@link DatasetExportServiceIT} 는 파일 부재 환경이라 written=0 이었고 채번·상태·멱등만 검증했다.
 * 이 테스트는 <b>실제 프레임 이미지 fixture 가 존재하는</b> 환경에서 승인 산출을 디스크 파일까지
 * end-to-end 로 검증한다 — orgnl/deid 각 폴더의 이미지+JSON 페어, JSON 8키·description·좌표·anonymity,
 * LS_DATASET_EXPORT SUCCEEDED+FRAME_CNT, 그리고 v1/v2 버전 누적/보존.
 *
 * <h3>비동기 처리 방식 — 동기 직접 호출 선택 (근거)</h3>
 * 실서비스 트리거는 {@code ReviewApprovedEvent}(AFTER_COMMIT) → {@link DatasetExportBridge}
 * → {@code @Async batchAsyncExecutor} → {@link AsyncDatasetExportRunner#runAsync}(=
 * {@link DatasetExportService#export}) 다. 그 <b>비동기 배선</b>은 Phase 4 단위/IT
 * (AsyncDatasetExportRunnerTest·DatasetExportBridgeTest·DatasetExportServiceIT)가 이미 커버한다.
 * 본 E2E 의 목적은 <b>파일 산출물(디스크) 정합 검증</b>이므로, 스레드풀·AFTER_COMMIT 타이밍에서
 * 오는 flakiness(Awaitility 폴링·검수 승인 전체 플로우 재현)를 배제하기 위해
 * {@link DatasetExportService#export(long)} 를 <b>동기 직접 호출</b>한다. 이는 러너가 최종적으로
 * 호출하는 바로 그 진입점이며, 산출 로직·파일쓰기 경로는 실서비스와 동일하다.
 */
@SpringBootTest
@ActiveProfiles("local")
class DatasetExportE2EIT {

    /** 프레임 fixture 개수(영상당). */
    private static final int FRAME_COUNT = 2;

    /** @DynamicPropertySource 는 static 이라 인스턴스 @TempDir 보다 먼저 계산돼야 한다 — static 임시 루트. */
    private static final Path STORAGE_ROOT = createStorageRoot();
    private static final Path LABELING_ROOT = STORAGE_ROOT.resolve("labeling");
    private static final Path RAW_ROOT = STORAGE_ROOT.resolve("raw");
    private static final Path DEID_ROOT = STORAGE_ROOT.resolve("deid");

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("dataset-export-e2e");
        } catch (IOException e) {
            throw new IllegalStateException("E2E 임시 스토리지 생성 실패", e);
        }
    }

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        registry.add("authoring.storage.labeling-path", LABELING_ROOT::toString);
        registry.add("authoring.storage.raw-path", RAW_ROOT::toString);
        registry.add("authoring.storage.deidentified-path", DEID_ROOT::toString);
    }

    @Autowired
    private DatasetExportService exportService;
    @Autowired
    private VideoRepository videoRepository;
    @Autowired
    private LsDataSrcRepository srcRepository;
    @Autowired
    private LsDataLblRepository labelRepository;
    @Autowired
    private LsDatasetVideoMetaRepository videoMetaRepository;
    @Autowired
    private LsDatasetExportRepository exportRepository;
    @Autowired
    private LsLabelRepository labelMasterRepository;

    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 이 테스트가 생성한 rawSn/labelId — @AfterEach 에서 FK 안전 순서로 정리한다(공유 컨테이너 오염 방지). */
    private final List<Long> createdRawSns = new ArrayList<>();
    private final List<Long> createdLabelIds = new ArrayList<>();

    DatasetExportE2EIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    /**
     * 이 테스트가 커밋한 데이터를 정리한다. txTemplate 는 자체 커밋하므로 롤백되지 않아,
     * 특히 {@code LS_DATA_LBL → LS_LABEL} FK 잔여가 다른 테스트의 {@code delete from LS_LABEL} 을
     * 깨뜨린다(공유 Testcontainer). FK 자식→부모 순으로 삭제한다.
     */
    @AfterEach
    void cleanup() {
        txTemplate.executeWithoutResult(s -> {
            for (Long rawSn : createdRawSns) {
                labelRepository.deleteAll(labelRepository.findAllByRawSn(rawSn));
                srcRepository.deleteAll(srcRepository.findByRawSnOrderByFrameNoAsc(rawSn));
                videoMetaRepository.deleteAll(videoMetaRepository.findByRawSn(rawSn));
                exportRepository.deleteAll(exportRepository.findAll().stream()
                        .filter(e -> rawSn.equals(e.getDataRawSn())).toList());
                videoRepository.findById(rawSn).ifPresent(videoRepository::delete);
            }
            labelMasterRepository.deleteAllById(createdLabelIds);
        });
        createdRawSns.clear();
        createdLabelIds.clear();
    }

    /** 활성 메타 + FRAME_COUNT 프레임(원본/비식별 이미지 fixture 실파일 포함) + BBOX 라벨을 시딩한다. */
    private long seedVideoWithFrameFiles(String pointCn, String description) {
        return seedVideoWithFrameFiles(pointCn, description, null);
    }

    /** 위와 동일하되 활성 메타에 동결 event_annotation({@code evntAnnoCn})을 함께 심는다(null 허용). */
    private long seedVideoWithFrameFiles(String pointCn, String description, String evntAnnoCn) {
        Long labelId = txTemplate.execute(s -> labelMasterRepository.save(
                LsLabel.create("car-" + System.nanoTime(), "#ff0000", "BBOX", 1, "tester")).getLabelId());
        createdLabelIds.add(labelId);
        long seededRawSn = txTemplate.execute(s -> {
            LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                    "clip-" + System.nanoTime(), "cctv-1", "EVT", "GOV",
                    LsDataRaw.PRVC_TYPE_PRVC, "raw/path.mp4", null, 60));
            Long rawSn = raw.getRawSn();
            videoMetaRepository.save(LsDatasetVideoMeta.builder()
                    .rawSn(rawSn)
                    .snpshtHash("h-" + rawSn)
                    .activeYn(LsDatasetVideoMeta.ACTIVE_YES)
                    .vdoWdth(1920)
                    .vdoHgt(1080)
                    .rawFilePathNm("raw/path.mp4")
                    .evntAnnoCn(evntAnnoCn)
                    .regDt(LocalDateTime.now())
                    .build());
            for (int i = 0; i < FRAME_COUNT; i++) {
                String rawRel = "frames/raw/" + rawSn + "/frame-" + i + ".jpg";
                String deidRel = "frames/deid/" + rawSn + "/frame-" + i + ".jpg";
                writeDummyImage(RAW_ROOT.resolve(rawRel));
                writeDummyImage(DEID_ROOT.resolve(deidRel));
                LsDataSrc frame = LsDataSrc.create(rawSn, i, rawRel, LocalDateTime.now());
                frame.attachDeidPath(deidRel);
                frame.updateDescription(description);
                frame = srcRepository.save(frame);
                labelRepository.save(LsDataLbl.createManual(
                        frame.getSrcSn(), "BBOX", labelId, "car", pointCn, null));
            }
            return rawSn;
        });
        createdRawSns.add(seededRawSn);
        return seededRawSn;
    }

    private void addLabel(long rawSn, String pointCn) {
        txTemplate.executeWithoutResult(s -> {
            LsDataSrc frame = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn).get(0);
            labelRepository.save(LsDataLbl.createManual(
                    frame.getSrcSn(), "BBOX", null, "bus", pointCn, null));
        });
    }

    private static void writeDummyImage(Path path) {
        try {
            Files.createDirectories(path.getParent());
            // 원천 이미지 바이트 — Writer 는 Files.copy 로 그대로 복사하므로 내용 검증은 불필요.
            Files.write(path, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
        } catch (IOException e) {
            throw new IllegalStateException("더미 이미지 생성 실패", e);
        }
    }

    private Path versionDir(long rawSn, int version, ExportKind kind) {
        return LABELING_ROOT.resolve(String.valueOf(rawSn))
                .resolve("v" + version)
                .resolve(kind.segment());
    }

    @Test
    @DisplayName("승인_산출시_orgnl_deid_각폴더에_이미지와_JSON페어가_실제로_기록된다")
    void exportWritesImageAndJsonPairsToBothKinds() throws IOException {
        long rawSn = seedVideoWithFrameFiles("[[1,2],[3,4]]", "사람 2명 보행");

        exportService.export(rawSn);

        Path orgnlDir = versionDir(rawSn, 1, ExportKind.ORIGINAL);
        Path deidDir = versionDir(rawSn, 1, ExportKind.DEIDENTIFIED);
        for (int i = 0; i < FRAME_COUNT; i++) {
            assertThat(orgnlDir.resolve("frame-" + i + ".jpg")).exists().isRegularFile();
            assertThat(orgnlDir.resolve("frame-" + i + ".json")).exists().isRegularFile();
            assertThat(deidDir.resolve("frame-" + i + ".jpg")).exists().isRegularFile();
            assertThat(deidDir.resolve("frame-" + i + ".json")).exists().isRegularFile();
        }
    }

    @Test
    @DisplayName("산출JSON은_최상위8키와_description_좌표를_반영한다")
    void exportJsonHasEightTopKeysDescriptionAndCoordinates() throws IOException {
        long rawSn = seedVideoWithFrameFiles("[[1,2],[3,4]]", "사람 2명 보행");

        exportService.export(rawSn);

        JsonNode doc = objectMapper.readTree(
                versionDir(rawSn, 1, ExportKind.ORIGINAL).resolve("frame-0.json").toFile());
        assertThat(doc.has("info")).isTrue();
        assertThat(doc.has("dataset")).isTrue();
        assertThat(doc.has("licences")).isTrue();
        assertThat(doc.has("video")).isTrue();
        assertThat(doc.has("image")).isTrue();
        assertThat(doc.has("annotations")).isTrue();
        assertThat(doc.has("categories")).isTrue();
        assertThat(doc.has("type")).isTrue();

        // image.description = frmExpln 반영
        assertThat(doc.path("image").path("description").asText()).isEqualTo("사람 2명 보행");

        // annotations bbox = [minX, minY, w, h] = [1, 2, 2, 2] (pointCn [[1,2],[3,4]])
        JsonNode bbox = doc.path("annotations").get(0).path("bbox");
        assertThat(bbox.isArray()).isTrue();
        assertThat(bbox.get(0).asDouble()).isEqualTo(1.0);
        assertThat(bbox.get(1).asDouble()).isEqualTo(2.0);
        assertThat(bbox.get(2).asDouble()).isEqualTo(2.0);
        assertThat(bbox.get(3).asDouble()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("orgnl_JSON은_anonymity_N이고_deid_JSON은_anonymity_Y다")
    void anonymityDiffersByKind() throws IOException {
        long rawSn = seedVideoWithFrameFiles("[[1,2],[3,4]]", "설명");

        exportService.export(rawSn);

        JsonNode orgnl = objectMapper.readTree(
                versionDir(rawSn, 1, ExportKind.ORIGINAL).resolve("frame-0.json").toFile());
        JsonNode deid = objectMapper.readTree(
                versionDir(rawSn, 1, ExportKind.DEIDENTIFIED).resolve("frame-0.json").toFile());
        assertThat(orgnl.path("image").path("anonymity").asText()).isEqualTo("N");
        assertThat(deid.path("image").path("anonymity").asText()).isEqualTo("Y");
    }

    @Test
    @DisplayName("승인_산출시_LS_DATASET_EXPORT_v1이_SUCCEEDED이고_FRAME_CNT가_산출건수와_일치한다")
    void exportRecordSucceededWithFrameCount() {
        long rawSn = seedVideoWithFrameFiles("[[1,2],[3,4]]", "설명");

        exportService.export(rawSn);

        LsDatasetExport latest = txTemplate.execute(s ->
                exportRepository.findFirstByDataRawSnOrderByExportVerNoDesc(rawSn).orElseThrow());
        assertThat(latest.getExportVerNo()).isEqualTo(1);
        assertThat(latest.getExportSttsCd()).isEqualTo(LsDatasetExport.STATUS_SUCCEEDED);
        // orgnl FRAME_COUNT + deid FRAME_COUNT (프레임마다 원본·비식별 이미지 fixture 존재)
        assertThat(latest.getFrameCnt()).isEqualTo(FRAME_COUNT * 2);
    }

    /** 위키 §24.3.1 event_annotation payload(후보 키 c1..cn). */
    private static final String EVENT_ANNO_PAYLOAD =
            "{\"event_class\":\"assault\",\"question\":\"무슨 일?\","
                    + "\"caption\":{\"c1\":{\"caption_text\":\"두 사람이 다툰다\",\"cot\":[\"1단계\",\"2단계\"]}},"
                    + "\"answer\":\"폭행\","
                    + "\"evidence\":{\"c1\":{\"evidence_text\":\"주먹\",\"obj_id\":[\"o1\",\"o2\"]}}}";

    @Test
    @DisplayName("export_각_프레임_frameN_json에_최상위_event_annotation_키가_c1cn_형태로_포함된다")
    void exportFramesIncludeTopLevelEventAnnotationC1Form() throws IOException {
        long rawSn = seedVideoWithFrameFiles("[[1,2],[3,4]]", "설명", EVENT_ANNO_PAYLOAD);

        exportService.export(rawSn);

        // orgnl/deid 각 프레임 JSON 모두 최상위 event_annotation(동결본 pass-through)을 자기완결로 포함.
        for (ExportKind kind : List.of(ExportKind.ORIGINAL, ExportKind.DEIDENTIFIED)) {
            for (int i = 0; i < FRAME_COUNT; i++) {
                JsonNode doc = objectMapper.readTree(
                        versionDir(rawSn, 1, kind).resolve("frame-" + i + ".json").toFile());
                JsonNode ea = doc.get("event_annotation");
                assertThat(ea).isNotNull();
                assertThat(ea.path("event_class").asText()).isEqualTo("assault");
                assertThat(ea.path("caption").path("c1").path("caption_text").asText())
                        .isEqualTo("두 사람이 다툰다");
                assertThat(ea.path("caption").path("c1").path("cot")).hasSize(2);
                assertThat(ea.path("evidence").path("c1").path("evidence_text").asText()).isEqualTo("주먹");
                assertThat(ea.path("evidence").path("c1").path("obj_id").get(0).asText()).isEqualTo("o1");
            }
        }
    }

    @Test
    @DisplayName("event_annotation_없는_영상_export시_event_annotation키는_null이다")
    void exportEventAnnotationNullWhenAbsent() throws IOException {
        long rawSn = seedVideoWithFrameFiles("[[1,2],[3,4]]", "설명"); // evntAnnoCn = null

        exportService.export(rawSn);

        JsonNode doc = objectMapper.readTree(
                versionDir(rawSn, 1, ExportKind.ORIGINAL).resolve("frame-0.json").toFile());
        assertThat(doc.has("event_annotation")).isTrue();
        assertThat(doc.get("event_annotation").isNull()).isTrue();
    }

    @Test
    @DisplayName("event_annotation_포함_재export시_동일_동결본이면_멱등skip되어_v2가_없다")
    void reExportWithSameFrozenEventAnnotationIsIdempotent() {
        long rawSn = seedVideoWithFrameFiles("[[1,2],[3,4]]", "설명", EVENT_ANNO_PAYLOAD);
        exportService.export(rawSn); // v1
        exportService.export(rawSn); // 동일 동결본 → 멱등 skip

        List<LsDatasetExport> exports = txTemplate.execute(s ->
                exportRepository.findAll().stream().filter(e -> e.getDataRawSn().equals(rawSn)).toList());
        assertThat(exports).hasSize(1);
        assertThat(exports.get(0).getExportVerNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("라벨수정후_재승인은_v2폴더를_신규생성하고_v1폴더를_보존한다")
    void modifiedReapproveCreatesV2FolderAndKeepsV1() throws IOException {
        long rawSn = seedVideoWithFrameFiles("[[1,2],[3,4]]", "설명");
        exportService.export(rawSn);        // v1
        addLabel(rawSn, "[[9,9],[8,8]]");    // 라벨 추가 → 해시 변경
        exportService.export(rawSn);        // v2

        // v1 보존
        assertThat(versionDir(rawSn, 1, ExportKind.ORIGINAL).resolve("frame-0.json")).exists();
        assertThat(versionDir(rawSn, 1, ExportKind.DEIDENTIFIED).resolve("frame-0.json")).exists();
        // v2 신규
        assertThat(versionDir(rawSn, 2, ExportKind.ORIGINAL).resolve("frame-0.json")).exists();
        assertThat(versionDir(rawSn, 2, ExportKind.DEIDENTIFIED).resolve("frame-0.json")).exists();

        // v2 orgnl frame-0 은 라벨 2건(car, bus) 반영
        JsonNode v2doc = objectMapper.readTree(
                versionDir(rawSn, 2, ExportKind.ORIGINAL).resolve("frame-0.json").toFile());
        assertThat(v2doc.path("annotations")).hasSize(2);

        List<LsDatasetExport> exports = txTemplate.execute(s ->
                exportRepository.findAll());
        long forRaw = exports.stream().filter(e -> e.getDataRawSn().equals(rawSn)).count();
        assertThat(forRaw).isEqualTo(2);
    }
}
