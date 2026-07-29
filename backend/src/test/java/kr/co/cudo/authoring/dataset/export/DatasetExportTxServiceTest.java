package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.entity.LsDatasetExport;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder.VideoExportContext;
import kr.co.cudo.authoring.dataset.export.repository.LsDatasetExportRepository;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DatasetExportTxService#loadPreparation} 단위 테스트(Mockito) — 멱등 baseline 조회 계약 검증.
 *
 * <p>검증 초점(HIGH — 멱등 계약 회귀 방지):
 * <ul>
 *   <li>baseline 조회가 <b>SUCCEEDED+PARTIAL</b> 상태 IN 필터로 수행된다(FAILED 제외).</li>
 *   <li>직전 export 가 <b>PARTIAL</b> 이고 그 해시가 현재 contentHash 와 같으면
 *       {@code isUnchangedFromLastExport()}=true → 무수정 재승인이 멱등 skip 된다
 *       (원천 이미지 지속부재 영상의 버전 무한채번 + 이미지 무한 재복사 회귀 방지).</li>
 *   <li>PARTIAL baseline 해시가 현재 해시와 다르면 재산출 진행(false).</li>
 * </ul>
 */
class DatasetExportTxServiceTest {

    private static final long RAW_SN = 42L;

    private LsDataSrcRepository srcRepository;
    private LsDataLblRepository labelRepository;
    private LsDatasetVideoMetaRepository videoMetaRepository;
    private LsLabelRepository labelMasterRepository;
    private VideoRepository videoRepository;
    private LsDatasetExportRepository exportRepository;
    private LsDeidentProcLogRepository deidentProcLogRepository;
    private NiaJsonBuilder niaJsonBuilder;
    private LabelContentHasher contentHasher;

    private DatasetExportTxService txService;

    @BeforeEach
    void setUp() {
        srcRepository = mock(LsDataSrcRepository.class);
        labelRepository = mock(LsDataLblRepository.class);
        videoMetaRepository = mock(LsDatasetVideoMetaRepository.class);
        labelMasterRepository = mock(LsLabelRepository.class);
        videoRepository = mock(VideoRepository.class);
        exportRepository = mock(LsDatasetExportRepository.class);
        deidentProcLogRepository = mock(LsDeidentProcLogRepository.class);
        niaJsonBuilder = mock(NiaJsonBuilder.class);
        contentHasher = mock(LabelContentHasher.class);
        txService = new DatasetExportTxService(srcRepository, labelRepository, videoMetaRepository,
                labelMasterRepository, videoRepository, exportRepository, deidentProcLogRepository,
                niaJsonBuilder, contentHasher, new com.fasterxml.jackson.databind.ObjectMapper(),
                new kr.co.cudo.authoring.video.service.DeidentReportGate(videoRepository));

        // 최소 입력 스텁 — 프레임 1건 + 활성 메타 1건이 있어야 loadPreparation 이 조립을 진행한다.
        LsDataSrc frame = mock(LsDataSrc.class);
        when(frame.getSrcSn()).thenReturn(100L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame));

        LsDatasetVideoMeta meta = mock(LsDatasetVideoMeta.class);
        when(videoMetaRepository.findByRawSnAndActiveYn(eq(RAW_SN), any())).thenReturn(List.of(meta));
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.empty());

        LsDataLbl label = mock(LsDataLbl.class);
        when(label.getSrcSn()).thenReturn(100L);
        when(label.getLabelId()).thenReturn(null); // 마스터 로드 skip
        when(labelRepository.findAllByRawSn(RAW_SN)).thenReturn(List.of(label));

        // 비식별 영상 경로 조회 — mock 의 default 메서드는 실행되지 않아 null 반환 → NPE 방지 위해 명시 스텁.
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(anyLong())).thenReturn(Optional.empty());

        when(niaJsonBuilder.prepareContext(any(), any(), any(), any(), any()))
                .thenReturn(mock(VideoExportContext.class));
    }

    /** 지정 상태·해시를 가진 baseline export mock. */
    private LsDatasetExport exportWith(String status, String hash) {
        LsDatasetExport e = mock(LsDatasetExport.class);
        when(e.getContentHash()).thenReturn(hash);
        when(e.getExportSttsCd()).thenReturn(status);
        return e;
    }

    @Test
    @DisplayName("baseline_조회는_SUCCEEDED와_PARTIAL을_모두_포함한다 — FAILED 제외")
    void baselineQueryIncludesSucceededAndPartial() {
        when(contentHasher.hash(any(), any(), any(), any())).thenReturn("H");
        when(exportRepository.findFirstByDataRawSnAndExportSttsCdInOrderByExportVerNoDesc(anyLong(), any()))
                .thenReturn(Optional.empty());

        txService.loadPreparation(RAW_SN);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(exportRepository)
                .findFirstByDataRawSnAndExportSttsCdInOrderByExportVerNoDesc(eq(RAW_SN), captor.capture());
        assertThat(captor.getValue())
                .containsExactlyInAnyOrder(
                        LsDatasetExport.STATUS_SUCCEEDED, LsDatasetExport.STATUS_PARTIAL)
                .doesNotContain(LsDatasetExport.STATUS_FAILED);
    }

    @Test
    @DisplayName("직전_PARTIAL해시가_현재해시와_같으면_멱등skip된다 — 무한채번 회귀 방지 핵심 가드")
    void partialBaselineSameHashIsIdempotentSkip() {
        when(contentHasher.hash(any(), any(), any(), any())).thenReturn("P");
        // 직전 export 가 PARTIAL 이고 해시가 현재와 동일 → baseline 으로 반환되어 skip 되어야 한다.
        // (exportWith 는 별도 stub 이라 when(...).thenReturn 인자 내에서 호출하면 UnfinishedStubbing — 먼저 조립.)
        LsDatasetExport baseline = exportWith(LsDatasetExport.STATUS_PARTIAL, "P");
        when(exportRepository.findFirstByDataRawSnAndExportSttsCdInOrderByExportVerNoDesc(anyLong(), any()))
                .thenReturn(Optional.of(baseline));

        Optional<ExportPreparation> prep = txService.loadPreparation(RAW_SN);

        assertThat(prep).isPresent();
        assertThat(prep.get().lastExportedHash()).isEqualTo("P");
        assertThat(prep.get().isUnchangedFromLastExport()).isTrue();
    }

    @Test
    @DisplayName("직전_PARTIAL해시가_현재해시와_다르면_재산출_진행한다")
    void partialBaselineDifferentHashReExports() {
        when(contentHasher.hash(any(), any(), any(), any())).thenReturn("P2");
        LsDatasetExport baseline = exportWith(LsDatasetExport.STATUS_PARTIAL, "P1");
        when(exportRepository.findFirstByDataRawSnAndExportSttsCdInOrderByExportVerNoDesc(anyLong(), any()))
                .thenReturn(Optional.of(baseline));

        Optional<ExportPreparation> prep = txService.loadPreparation(RAW_SN);

        assertThat(prep).isPresent();
        assertThat(prep.get().isUnchangedFromLastExport()).isFalse();
    }

    @Test
    @DisplayName("동결_event_annotation이_잘못된JSON이면_null로_fail_secure되고_export는_계속된다")
    void malformedFrozenEventAnnotationFailsSecureToNull() {
        when(contentHasher.hash(any(), any(), any(), any())).thenReturn("H");
        when(exportRepository.findFirstByDataRawSnAndExportSttsCdInOrderByExportVerNoDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
        // 활성 메타의 EVNT_ANNO_CN 이 파싱 불가한 jsonb 원문(방어코드 경로) — readTree 가 JsonProcessingException.
        LsDatasetVideoMeta malformed = mock(LsDatasetVideoMeta.class);
        when(malformed.getEvntAnnoCn()).thenReturn("{invalid json");
        when(videoMetaRepository.findByRawSnAndActiveYn(eq(RAW_SN), any())).thenReturn(List.of(malformed));

        Optional<ExportPreparation> prep = txService.loadPreparation(RAW_SN);

        // export 를 깨지 않고 계속 진행하며(prep present), event_annotation 은 null 로 pass-through(omit).
        assertThat(prep).isPresent();
        ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> eaCaptor =
                ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(niaJsonBuilder).prepareContext(any(), any(), any(), eaCaptor.capture(), any());
        assertThat(eaCaptor.getValue()).isNull();
    }

    @Test
    @DisplayName("sweepStalePending은_후보를_원자_클레임으로_회수하고_성공건수만_반환한다")
    void sweepStalePendingClaimsAndReturnsClaimedCount() {
        // given — 후보 2건. 그 중 1건은 다른 노드가 이미 회수해 클레임 0행(경합).
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        when(exportRepository.findStalePendingAnchors(eq(cutoff), anyInt()))
                .thenReturn(List.of(11L, 22L));
        when(exportRepository.claimStalePending(11L, cutoff)).thenReturn(1);
        when(exportRepository.claimStalePending(22L, cutoff)).thenReturn(0);

        // when
        int swept = txService.sweepStalePending(cutoff);

        // then — 클레임 성공분(1건)만 센다. 엔티티 setter 가 아니라 조건부 UPDATE 로 회수한다.
        assertThat(swept).isEqualTo(1);
        verify(exportRepository).claimStalePending(11L, cutoff);
        verify(exportRepository).claimStalePending(22L, cutoff);
    }

    @Test
    @DisplayName("sweepStalePending은_조회에_상한을_적용한다")
    void sweepStalePendingAppliesLimit() {
        // given
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        when(exportRepository.findStalePendingAnchors(eq(cutoff), anyInt())).thenReturn(List.of());

        // when
        txService.sweepStalePending(cutoff);

        // then — 무제한 조회 금지: 양수 상한이 전달된다
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(exportRepository).findStalePendingAnchors(eq(cutoff), limit.capture());
        assertThat(limit.getValue()).isPositive();
    }

    @Test
    @DisplayName("sweepStalePending은_stale가_없으면_0을_반환한다")
    void sweepStalePendingReturnsZeroWhenNone() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        when(exportRepository.findStalePendingAnchors(eq(cutoff), anyInt())).thenReturn(List.of());

        assertThat(txService.sweepStalePending(cutoff)).isZero();
    }
}
