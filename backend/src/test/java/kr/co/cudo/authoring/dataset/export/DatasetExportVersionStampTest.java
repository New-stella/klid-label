package kr.co.cudo.authoring.dataset.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.dataset.export.entity.LsDatasetExport;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder;
import kr.co.cudo.authoring.dataset.export.repository.LsDatasetExportRepository;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.version.service.OutputVersionStamper;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VER_NO 실채번 — 산출 원장({@code LS_DATASET_EXPORT.OUTPUT_VER_NO})의 번호를 승인 스냅샷
 * ({@code LS_LABEL_VERSION.VER_NO})에 <b>산출 마감과 같은 트랜잭션</b>에서 찍는지 고정한다.
 *
 * <h3>왜 승인 트랜잭션이 아니라 여기인가</h3>
 * 산출 버전 번호는 {@code DatasetExportTxService.insertNextVersion}(@Async export 안, REQUIRES_NEW)
 * 에서 비로소 채번된다. 검수 승인 트랜잭션 시점에는 <b>알 수 없다</b> — 그때 예측해 넣으면 산출이
 * 실패·차단돼 그 번호의 폴더가 만들어지지 않았을 때 <b>존재하지 않는 버전</b>을 가리키게 된다.
 * 그래서 <b>성공/부분 마감과 같은 트랜잭션</b>에서 찍어 "번호가 찍힌 스냅샷 ⇔ 실재하는 산출 폴더"를
 * 원자적으로 유지한다.
 *
 * @design D5
 * @req R6
 */
@ExtendWith(MockitoExtension.class)
class DatasetExportVersionStampTest {

    private static final long RAW_SN = 9L;
    private static final long EXPORT_SN = 700L;

    @Mock private LsDatasetExportRepository exportRepository;
    @Mock private DeidentReportGate deidentReportGate;
    @Mock private OutputVersionStamper outputVersionStamper;

    private DatasetExportTxService txService;

    @BeforeEach
    void setUp() {
        txService = new DatasetExportTxService(
                mock(LsDataSrcRepository.class), mock(LsDataLblRepository.class),
                mock(LsDatasetVideoMetaRepository.class),
                mock(VideoRepository.class), exportRepository,
                new NiaExportContextAssembler(
                        mock(LsDatasetVideoMetaRepository.class), mock(VideoRepository.class),
                        mock(IngestSourceRepository.class), mock(LsDataMetaRepository.class),
                        mock(LsDeidentProcLogRepository.class), mock(LsLabelRepository.class),
                        mock(NiaJsonBuilder.class), new ObjectMapper()),
                mock(LabelContentHasher.class), deidentReportGate,
                outputVersionStamper);
    }

    @Test
    @DisplayName("산출_성공_마감_시_그_산출_버전번호를_승인_스냅샷에_찍는다")
    void 산출_성공_마감_시_그_산출_버전번호를_승인_스냅샷에_찍는다() {
        when(deidentReportGate.isUnderDeidentReportLocked(RAW_SN)).thenReturn(false);
        when(exportRepository.findById(EXPORT_SN)).thenReturn(Optional.of(export(2)));

        boolean finalized = txService.finalizeUnlessUnderDeidentReport(
                RAW_SN, EXPORT_SN, 10, false, 1_024L);

        assertThat(finalized).isTrue();
        verify(outputVersionStamper).stamp(RAW_SN, 2);
    }

    @Test
    @DisplayName("일부_산출_마감도_버전번호를_찍는다")
    void 일부_산출_마감도_버전번호를_찍는다() {
        when(deidentReportGate.isUnderDeidentReportLocked(RAW_SN)).thenReturn(false);
        when(exportRepository.findById(EXPORT_SN)).thenReturn(Optional.of(export(5)));

        txService.finalizeUnlessUnderDeidentReport(RAW_SN, EXPORT_SN, 3, true, null);

        verify(outputVersionStamper).stamp(RAW_SN, 5);
    }

    @Test
    @DisplayName("신고_구간이라_마감이_차단되면_버전번호를_찍지_않는다")
    void 신고_구간이라_마감이_차단되면_버전번호를_찍지_않는다() {
        when(deidentReportGate.isUnderDeidentReportLocked(RAW_SN)).thenReturn(true);

        boolean finalized = txService.finalizeUnlessUnderDeidentReport(
                RAW_SN, EXPORT_SN, 10, false, 1_024L);

        assertThat(finalized).isFalse();
        verify(outputVersionStamper, never()).stamp(anyLong(), anyInt());
    }

    private LsDatasetExport export(int verNo) {
        return LsDatasetExport.create(RAW_SN, verNo, "/nas/videos/9", "hash");
    }
}
