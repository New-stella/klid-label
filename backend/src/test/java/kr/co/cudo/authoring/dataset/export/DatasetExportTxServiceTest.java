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
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
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
    /** {@code video.vd_description}(@req R10) 조달 원천 — 자기 rawSn 의 LS_DATA_META. */
    private kr.co.cudo.authoring.batch.repository.LsDataMetaRepository dataMetaRepository;

    private DatasetExportTxService txService;

    /** {@code prepareContext} 스텁이 돌려주는 컨텍스트 — 스텁 매칭 실증(동일 인스턴스 비교)용. */
    private VideoExportContext stubbedCtx;

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
        dataMetaRepository = mock(kr.co.cudo.authoring.batch.repository.LsDataMetaRepository.class);
        txService = new DatasetExportTxService(srcRepository, labelRepository, videoMetaRepository,
                labelMasterRepository, videoRepository, exportRepository, deidentProcLogRepository,
                mock(IngestSourceRepository.class), dataMetaRepository,
                niaJsonBuilder, contentHasher, new com.fasterxml.jackson.databind.ObjectMapper(),
                new kr.co.cudo.authoring.video.service.DeidentReportGate(videoRepository),
                mock(kr.co.cudo.authoring.version.service.OutputVersionStamper.class));

        // 최소 입력 스텁 — 프레임 1건 + 활성 메타 1건이 있어야 loadPreparation 이 조립을 진행한다.
        LsDataSrc frame = mock(LsDataSrc.class);
        when(frame.getSrcSn()).thenReturn(100L);
        when(srcRepository.findNotDiscardedByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame));

        LsDatasetVideoMeta meta = mock(LsDatasetVideoMeta.class);
        when(videoMetaRepository.findByRawSnAndActiveYn(eq(RAW_SN), any())).thenReturn(List.of(meta));
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.empty());

        LsDataLbl label = mock(LsDataLbl.class);
        when(label.getSrcSn()).thenReturn(100L);
        when(label.getLabelId()).thenReturn(null); // 마스터 로드 skip
        when(labelRepository.findAllByRawSn(RAW_SN)).thenReturn(List.of(label));

        // 비식별 영상 경로 조회 — mock 의 default 메서드는 실행되지 않아 null 반환 → NPE 방지 위해 명시 스텁.
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(anyLong())).thenReturn(Optional.empty());

        // ⚠ 인자 수는 프로덕션이 호출하는 <오버로드와 정확히 일치>해야 한다(8-arg — ingestEvntId +
        //   vdDescription 포함). 하나라도 적으면 별개 오버로드라 스텁이 매칭되지 않고 ctx 가 조용히
        //   null 이 된다(컴파일은 통과). 매칭 여부는 실행해야만 드러나므로
        //   ctxStubIsActuallyMatched 가 non-null 로 고정한다.
        stubbedCtx = mock(VideoExportContext.class);
        when(niaJsonBuilder.prepareContext(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(stubbedCtx);
    }

    /** 지정 상태·해시를 가진 baseline export mock. */
    private LsDatasetExport exportWith(String status, String hash) {
        LsDatasetExport e = mock(LsDatasetExport.class);
        when(e.getContentHash()).thenReturn(hash);
        when(e.getExportSttsCd()).thenReturn(status);
        return e;
    }

    @Test
    @DisplayName("prepareContext_스텁이_실제_호출과_매칭되어_ctx가_null이_아니다")
    void ctxStubIsActuallyMatched() {
        // given — 정상 준비 경로
        when(contentHasher.hash(any(), any(), any(), any(), any(), any(), any())).thenReturn("H");
        when(exportRepository.findFirstByDataRawSnAndExportSttsCdInOrderByExportVerNoDesc(anyLong(), any()))
                .thenReturn(Optional.empty());

        // when
        Optional<ExportPreparation> prep = txService.loadPreparation(RAW_SN);

        // then — ★이 클래스의 <스텁 매칭 자체>를 고정한다. prepareContext 는 오버로드가 여럿이라
        //   스텁 인자 수가 프로덕션 호출과 어긋나도 <컴파일은 통과>하고, 대신 Mockito 가 스텁을
        //   매칭하지 못해 ctx 가 조용히 null 이 된다(이 클래스 전체가 null ctx 위에서 돌게 된다).
        //   "arity 를 맞췄다"는 코드 읽기로는 알 수 없고 실행해야만 드러나므로 여기서 단정한다.
        assertThat(prep).isPresent();
        assertThat(prep.get().ctx())
                .as("prepareContext 스텁이 매칭되지 않았다 — 프로덕션이 호출하는 오버로드와 인자 수를 맞출 것")
                .isNotNull()
                .isSameAs(stubbedCtx);
    }

    @Test
    @DisplayName("baseline_조회는_SUCCEEDED와_PARTIAL을_모두_포함한다 — FAILED 제외")
    void baselineQueryIncludesSucceededAndPartial() {
        when(contentHasher.hash(any(), any(), any(), any(), any(), any(), any())).thenReturn("H");
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
        when(contentHasher.hash(any(), any(), any(), any(), any(), any(), any())).thenReturn("P");
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
        when(contentHasher.hash(any(), any(), any(), any(), any(), any(), any())).thenReturn("P2");
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
        when(contentHasher.hash(any(), any(), any(), any(), any(), any(), any())).thenReturn("H");
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
        verify(niaJsonBuilder).prepareContext(any(), any(), any(), eaCaptor.capture(), any(), any(), any(),
                any());
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

    // ------------------------------------------------------------ vd_description 배선 (@req R10)

    private void stubBaselineEmpty() {
        when(contentHasher.hash(any(), any(), any(), any(), any(), any(), any())).thenReturn("H");
        when(exportRepository.findFirstByDataRawSnAndExportSttsCdInOrderByExportVerNoDesc(anyLong(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("자기_rawSn_메타의_서술이_해시와_컨텍스트에_함께_실린다")
    void 자기_rawSn_메타의_서술이_해시와_컨텍스트에_함께_실린다() {
        // given — 이 영상의 LS_DATA_META 에 verify 서술이 있다.
        stubBaselineEmpty();
        when(dataMetaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(
                kr.co.cudo.authoring.batch.entity.LsDataMeta.create(
                        RAW_SN, kr.co.cudo.authoring.webhook.service.VlmResultService.META_KEY_DESCRIPTION,
                        "검증 서술 전문")));

        // when
        txService.loadPreparation(RAW_SN);

        // then — ①조회는 자기 rawSn 1회(부모 폴백 없음) ②해시·컨텍스트 <양쪽>에 같은 값이 실린다.
        //   한쪽만 실으면 "저장은 됐는데 산출물이 안 바뀐다"(해시 누락) 또는 "산출물만 바뀌고 멱등
        //   판정이 어긋난다"(컨텍스트 누락)가 된다.
        verify(dataMetaRepository).findByRawSn(RAW_SN);
        ArgumentCaptor<String> hashArg = ArgumentCaptor.forClass(String.class);
        verify(contentHasher).hash(any(), any(), any(), any(), any(), hashArg.capture(), any());
        assertThat(hashArg.getValue()).isEqualTo("검증 서술 전문");

        ArgumentCaptor<String> ctxArg = ArgumentCaptor.forClass(String.class);
        verify(niaJsonBuilder).prepareContext(any(), any(), any(), any(), any(), any(), any(),
                ctxArg.capture());
        assertThat(ctxArg.getValue()).isEqualTo("검증 서술 전문");
    }

    @Test
    @DisplayName("메타가_없으면_서술은_null로_전달된다")
    void 메타가_없으면_서술은_null로_전달된다() {
        // given — 파생영상처럼 자기 메타가 없는 경우. 부모를 뒤지지 않는다.
        stubBaselineEmpty();
        when(dataMetaRepository.findByRawSn(RAW_SN)).thenReturn(List.of());

        // when
        txService.loadPreparation(RAW_SN);

        // then
        ArgumentCaptor<String> ctxArg = ArgumentCaptor.forClass(String.class);
        verify(niaJsonBuilder).prepareContext(any(), any(), any(), any(), any(), any(), any(),
                ctxArg.capture());
        assertThat(ctxArg.getValue()).isNull();
    }
}
