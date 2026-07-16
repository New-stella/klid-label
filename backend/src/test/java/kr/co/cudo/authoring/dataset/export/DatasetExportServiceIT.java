package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.entity.LsDatasetExport;
import kr.co.cudo.authoring.dataset.export.repository.LsDatasetExportRepository;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DatasetExportService} 통합 테스트(PostgreSQL Testcontainer) — 실제 DB 재조회 로딩 경로와
 * 버전 누적/무수정 재승인 멱등을 검증한다.
 *
 * <p>원천 프레임 이미지 파일은 존재하지 않으므로 {@code FrameSource} 가 모두 skip 하여 written=0 이지만,
 * LS_DATASET_EXPORT 레코드 채번·상태 전이·콘텐츠 해시 영속·멱등 판정은 실 DB 로 검증된다(파일 쓰기
 * 자체는 {@code DatasetExportWriterTest} 가 커버).
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = "authoring.storage.labeling-path=build/tmp/dataset-export-it")
class DatasetExportServiceIT {

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

    private final TransactionTemplate txTemplate;

    DatasetExportServiceIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    private long seedVideoWithLabel(String pointCn) {
        return txTemplate.execute(s -> {
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
                    .regDt(LocalDateTime.now())
                    .build());
            LsDataSrc frame = srcRepository.save(
                    LsDataSrc.create(rawSn, 0, "raw/f0.jpg", LocalDateTime.now()));
            labelRepository.save(LsDataLbl.createManual(
                    frame.getSrcSn(), "BBOX", null, "car", pointCn, null));
            return rawSn;
        });
    }

    private void addLabel(long rawSn, String pointCn) {
        txTemplate.executeWithoutResult(s -> {
            LsDataSrc frame = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn).get(0);
            labelRepository.save(LsDataLbl.createManual(
                    frame.getSrcSn(), "BBOX", null, "bus", pointCn, null));
        });
    }

    private void updateFrameDescription(long rawSn, String description) {
        txTemplate.executeWithoutResult(s -> {
            LsDataSrc frame = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn).get(0);
            frame.updateDescription(description);
            srcRepository.save(frame);
        });
    }

    /** 활성 메타의 개인정보 유형 정정(재-materialize 모사) — 활성 행 교체. */
    private void changePrivacyType(long rawSn, String prvcTypeCd) {
        txTemplate.executeWithoutResult(s -> {
            videoMetaRepository.deleteAll(
                    videoMetaRepository.findByRawSnAndActiveYn(rawSn, LsDatasetVideoMeta.ACTIVE_YES));
            // 활성 메타는 raw_sn 당 1건만 허용(uk_ls_dataset_video_meta_raw_active) — INSERT 전 DELETE 선반영.
            videoMetaRepository.flush();
            videoMetaRepository.save(LsDatasetVideoMeta.builder()
                    .rawSn(rawSn)
                    .snpshtHash("h2-" + rawSn)
                    .activeYn(LsDatasetVideoMeta.ACTIVE_YES)
                    .prvcTypeCd(prvcTypeCd)
                    .vdoWdth(1920)
                    .vdoHgt(1080)
                    .rawFilePathNm("raw/path.mp4")
                    .regDt(LocalDateTime.now())
                    .build());
        });
    }

    /** 최신 버전으로 FAILED export 레코드를 직접 삽입(멱등 판정 회귀 재현용). */
    private void insertFailedVersion(long rawSn, int verNo) {
        txTemplate.executeWithoutResult(s -> {
            LsDatasetExport failed = LsDatasetExport.create(
                    rawSn, verNo, "labeling/" + rawSn + "/v" + verNo, "other-hash-" + verNo);
            failed.markFailed();
            exportRepository.save(failed);
        });
    }

    @Test
    @DisplayName("승인_산출시_LS_DATASET_EXPORT_v1이_SUCCEEDED로_기록되고_해시가_영속된다")
    void exportCreatesSucceededV1() {
        long rawSn = seedVideoWithLabel("[[1,2],[3,4]]");

        exportService.export(rawSn);

        Optional<LsDatasetExport> latest = txTemplate.execute(s ->
                exportRepository.findFirstByDataRawSnOrderByExportVerNoDesc(rawSn));
        assertThat(latest).isPresent();
        assertThat(latest.get().getExportVerNo()).isEqualTo(1);
        assertThat(latest.get().getExportSttsCd()).isEqualTo(LsDatasetExport.STATUS_SUCCEEDED);
        assertThat(latest.get().getContentHash()).isNotBlank();
    }

    @Test
    @DisplayName("무수정_재승인은_멱등_skip되어_새_버전을_만들지_않는다")
    void unchangedReapproveIsIdempotent() {
        long rawSn = seedVideoWithLabel("[[1,2],[3,4]]");
        exportService.export(rawSn);
        exportService.export(rawSn); // 라벨 변경 없음 → skip

        long count = txTemplate.execute(s -> exportRepository.countByDataRawSn(rawSn));
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("수정후_재승인은_v2를_신규생성하고_v1은_보존된다")
    void modifiedReapproveCreatesV2AndKeepsV1() {
        long rawSn = seedVideoWithLabel("[[1,2],[3,4]]");
        exportService.export(rawSn);          // v1
        addLabel(rawSn, "[[9,9],[8,8]]");     // 라벨 추가 → 해시 변경
        exportService.export(rawSn);          // v2

        long count = txTemplate.execute(s -> exportRepository.countByDataRawSn(rawSn));
        assertThat(count).isEqualTo(2);
        boolean v1Kept = txTemplate.execute(s ->
                exportRepository.existsByDataRawSnAndExportVerNo(rawSn, 1));
        boolean v2Created = txTemplate.execute(s ->
                exportRepository.existsByDataRawSnAndExportVerNo(rawSn, 2));
        assertThat(v1Kept).isTrue();
        assertThat(v2Created).isTrue();
    }

    @Test
    @DisplayName("직전_export가_FAILED여도_그이전_SUCCEEDED해시로_멱등판정")
    void idempotentAgainstEarlierSucceededDespiteLatestFailed() {
        long rawSn = seedVideoWithLabel("[[1,2],[3,4]]");
        exportService.export(rawSn);        // v1 SUCCEEDED (hash H)
        insertFailedVersion(rawSn, 2);      // v2 FAILED (최신)

        exportService.export(rawSn);        // 라벨/프레임/메타 무변경 → v1 해시로 멱등 skip

        long count = txTemplate.execute(s -> exportRepository.countByDataRawSn(rawSn));
        assertThat(count).isEqualTo(2);     // v1(SUCCEEDED)+v2(FAILED)만, v3 미생성
        boolean v3Absent = txTemplate.execute(s ->
                !exportRepository.existsByDataRawSnAndExportVerNo(rawSn, 3));
        assertThat(v3Absent).isTrue();
    }

    @Test
    @DisplayName("프레임설명(frmExpln)_수정후_재승인은_v2를_생성한다")
    void frameDescriptionEditCreatesV2() {
        long rawSn = seedVideoWithLabel("[[1,2],[3,4]]");
        exportService.export(rawSn);                 // v1
        updateFrameDescription(rawSn, "사람 2명 보행"); // 라벨 무변경, frmExpln만 변경
        exportService.export(rawSn);                 // 해시 변경 → v2

        long count = txTemplate.execute(s -> exportRepository.countByDataRawSn(rawSn));
        assertThat(count).isEqualTo(2);
    }

    @Test
    @DisplayName("개인정보플래그(prvcTypeCd)_수정후_재승인은_v2를_생성한다")
    void privacyTypeEditCreatesV2() {
        long rawSn = seedVideoWithLabel("[[1,2],[3,4]]");
        exportService.export(rawSn);          // v1 (meta prvcTypeCd=null → raw PRVC 폴백)
        changePrivacyType(rawSn, "PSDO");     // 라벨 무변경, 개인정보 유형만 변경
        exportService.export(rawSn);          // 해시 변경 → v2

        long count = txTemplate.execute(s -> exportRepository.countByDataRawSn(rawSn));
        assertThat(count).isEqualTo(2);
    }
}
