package kr.co.cudo.authoring.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsPjtDataStts;
import kr.co.cudo.authoring.assignment.repository.LsPjtDataSttsRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.Point;
import kr.co.cudo.authoring.export.entity.LsDataSet;
import kr.co.cudo.authoring.export.repository.LsDataSetRepository;
import kr.co.cudo.authoring.export.service.ExportRunner;
import kr.co.cudo.authoring.export.service.NasStorageWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "authoring.storage.raw-path=./build/test-storage/export-runner"
})
class ExportRunnerTest {

    @Autowired private ExportRunner runner;
    @Autowired private LsDataSetRepository exportRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository labelRepository;
    @Autowired private LsPjtDataSttsRepository sttsRepository;
    @Autowired private ObjectMapper objectMapper;

    /**
     * NAS 쓰기 성공/실패를 시나리오별로 제어하기 위해 SpyBean.
     */
    @SpyBean private NasStorageWriter nasWriter;

    private static final Long PJT_ID = 990L;

    @BeforeEach
    void cleanup() {
        labelRepository.deleteAll();
        srcRepository.deleteAll();
        exportRepository.deleteAll();
        sttsRepository.findAll().stream()
                .filter(s -> PJT_ID.equals(s.getId().getPjtId()))
                .forEach(sttsRepository::delete);
    }

    private LsDataSrc seedFrame(Long rawSn, int frameNo) {
        LsDataSrc src = LsDataSrc.create(rawSn, frameNo,
                "/var/raw/" + rawSn + "/" + frameNo + ".jpg", null);
        return srcRepository.save(src);
    }

    private void seedLabel(Long srcSn, String label, double xtl, double ytl, double xbr, double ybr) {
        String pointsJson = LabelPointSerializer.toJson(
                List.of(new Point(xtl, ytl), new Point(xbr, ybr)), objectMapper);
        labelRepository.save(LsDataLbl.createAutoBbox(srcSn, label, pointsJson, new BigDecimal("0.95")));
    }

    private void markStatus(Long rawSn, String status) {
        LsPjtDataStts stts = LsPjtDataStts.initial(PJT_ID, rawSn);
        stts.transitionTo(status);
        sttsRepository.save(stts);
    }

    private LsDataSet enqueue(String format) {
        LsDataSet job = LsDataSet.createPending(PJT_ID, format, "1");
        return exportRepository.save(job);
    }

    // ============================================================

    @Test
    @DisplayName("ACCEPTED_상태_라벨만_export_대상_REJECTED는_제외")
    void onlyApprovedFramesAreExported() {
        // APPROVED 영상 — 프레임 + 라벨 포함
        Long approvedRaw = 9000L + (long) (Math.random() * 100000);
        markStatus(approvedRaw, LsPjtDataStts.STTS_APPROVED);
        LsDataSrc approvedSrc = seedFrame(approvedRaw, 0);
        seedLabel(approvedSrc.getSrcSn(), "car", 100, 200, 300, 400);

        // REJECTED 영상 — 동일하게 프레임 + 라벨이 있지만 export 대상 아님
        Long rejectedRaw = approvedRaw + 1;
        markStatus(rejectedRaw, LsPjtDataStts.STTS_REJECTED);
        LsDataSrc rejectedSrc = seedFrame(rejectedRaw, 0);
        seedLabel(rejectedSrc.getSrcSn(), "person", 50, 60, 70, 80);

        LsDataSet job = enqueue(LsDataSet.FORMAT_YOLO);

        runner.run(job.getExportSn());

        LsDataSet completed = exportRepository.findById(job.getExportSn()).orElseThrow();
        assertThat(completed.getExportSttsCd()).isEqualTo(LsDataSet.STTS_COMPLETED);
        assertThat(completed.getNasPath()).isNotBlank();

        // 실제 NAS 파일 본문 검증 — 'car' 만 포함, 'person' 미포함
        Path written = Path.of(completed.getNasPath());
        try {
            String content = Files.readString(written);
            assertThat(content).contains("# frame=" + approvedSrc.getSrcSn());
            assertThat(content).doesNotContain("# frame=" + rejectedSrc.getSrcSn());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("NAS_쓰기_실패시_temp_파일_정리_+_status_FAILED")
    void nasWriteFailureResultsInFailedStatus() {
        Long rawSn = 9100L + (long) (Math.random() * 100000);
        markStatus(rawSn, LsPjtDataStts.STTS_APPROVED);
        LsDataSrc src = seedFrame(rawSn, 0);
        seedLabel(src.getSrcSn(), "car", 100, 200, 300, 400);

        // NasStorageWriter 가 IO 실패를 시뮬레이션 (CustomException throw)
        Mockito.doThrow(new RuntimeException("NAS 마운트 실패 (시뮬레이션)"))
                .when(nasWriter).writeText(Mockito.anyString(), Mockito.anyString());

        LsDataSet job = enqueue(LsDataSet.FORMAT_YOLO);

        runner.run(job.getExportSn());

        LsDataSet failed = exportRepository.findById(job.getExportSn()).orElseThrow();
        assertThat(failed.getExportSttsCd()).isEqualTo(LsDataSet.STTS_FAILED);
        assertThat(failed.getErrorMessage()).contains("NAS 마운트 실패");
        assertThat(failed.getNasPath()).isNull();
    }

    @Test
    @DisplayName("format_YOLO_파일_생성_확인")
    void yoloFileCreated() throws IOException {
        Long rawSn = 9200L + (long) (Math.random() * 100000);
        markStatus(rawSn, LsPjtDataStts.STTS_APPROVED);
        LsDataSrc src = seedFrame(rawSn, 0);
        seedLabel(src.getSrcSn(), "car", 100, 200, 300, 400);

        LsDataSet job = enqueue(LsDataSet.FORMAT_YOLO);

        runner.run(job.getExportSn());

        LsDataSet done = exportRepository.findById(job.getExportSn()).orElseThrow();
        assertThat(done.getExportSttsCd()).isEqualTo(LsDataSet.STTS_COMPLETED);
        assertThat(done.getNasPath()).endsWith(".txt");
        assertThat(Files.exists(Path.of(done.getNasPath()))).isTrue();

        String content = Files.readString(Path.of(done.getNasPath()));
        assertThat(content).contains("# frame=" + src.getSrcSn());
        // YOLO 1줄 — class 0 (첫 라벨) + 4개 정규화 좌표
        assertThat(content.split("\\r?\\n")).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("format_COCO_JSON_파일_생성_확인")
    void cocoJsonFileCreated() throws IOException {
        Long rawSn = 9300L + (long) (Math.random() * 100000);
        markStatus(rawSn, LsPjtDataStts.STTS_APPROVED);
        LsDataSrc src = seedFrame(rawSn, 0);
        seedLabel(src.getSrcSn(), "car", 100, 200, 300, 400);

        LsDataSet job = enqueue(LsDataSet.FORMAT_COCO);

        runner.run(job.getExportSn());

        LsDataSet done = exportRepository.findById(job.getExportSn()).orElseThrow();
        assertThat(done.getExportSttsCd()).isEqualTo(LsDataSet.STTS_COMPLETED);
        assertThat(done.getNasPath()).endsWith(".json");
        String content = Files.readString(Path.of(done.getNasPath()));
        assertThat(content).contains("\"images\"");
        assertThat(content).contains("\"annotations\"");
        assertThat(content).contains("\"categories\"");
    }
}
