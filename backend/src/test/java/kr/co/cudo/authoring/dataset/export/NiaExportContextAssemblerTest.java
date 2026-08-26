package kr.co.cudo.authoring.dataset.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.json.CategoryMapper;
import kr.co.cudo.authoring.dataset.export.json.LabelToAnnotationMapper;
import kr.co.cudo.authoring.dataset.export.json.NiaJsonBuilder;
import kr.co.cudo.authoring.dataset.export.json.VideoMetaMapper;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRow;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link NiaExportContextAssembler} — 산출 경로와 포털 산출이 공유하는 컨텍스트 조달기의 계약 가드.
 *
 * <p>이 조달을 복제하면 반드시 깨지는 두 지점을 고정한다:
 * <ol>
 *   <li><b>조달 순서</b> — 외부 산출물 이관 영상은 관제 인입 행이 없어 원천 축 개인정보가 메타에 원문
 *       보관돼 있다. 원천 축을 메타 로드 <b>앞</b>에서 판정하면 그 값이 전부 null 로 산출된다.</li>
 *   <li><b>파생영상 판정</b> — 파생·영상행 부재는 {@link SourcePrivacyMeta#NONE} 이며 원천 3필드가
 *       null 인 것이 정상이다(상수를 대신 싣지 않는다).</li>
 * </ol>
 */
class NiaExportContextAssemblerTest {

    private static final long RAW_SN = 55L;

    private LsDatasetVideoMetaRepository videoMetaRepository;
    private VideoRepository videoRepository;
    private IngestSourceRepository ingestSourceRepository;
    private LsDataMetaRepository dataMetaRepository;
    private LsDeidentProcLogRepository deidentProcLogRepository;
    private LsLabelRepository labelMasterRepository;

    private NiaExportContextAssembler assembler;

    @BeforeEach
    void setUp() {
        videoMetaRepository = mock(LsDatasetVideoMetaRepository.class);
        videoRepository = mock(VideoRepository.class);
        ingestSourceRepository = mock(IngestSourceRepository.class);
        dataMetaRepository = mock(LsDataMetaRepository.class);
        deidentProcLogRepository = mock(LsDeidentProcLogRepository.class);
        labelMasterRepository = mock(LsLabelRepository.class);

        when(dataMetaRepository.findByRawSn(anyLong())).thenReturn(List.of());
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(anyLong())).thenReturn(Optional.empty());

        ObjectMapper objectMapper = new ObjectMapper();
        assembler = new NiaExportContextAssembler(
                videoMetaRepository, videoRepository, ingestSourceRepository, dataMetaRepository,
                deidentProcLogRepository, labelMasterRepository,
                new NiaJsonBuilder(new LabelToAnnotationMapper(objectMapper), new VideoMetaMapper(),
                        new CategoryMapper()),
                objectMapper);
    }

    @Test
    @DisplayName("이관_영상의_원천_개인정보는_메타_원문에서_조달된다 — 조달 순서 역전 시 전부 null 이 된다")
    void importedVideoResolvesSourcePrivacyFromMeta() {
        LsDataRaw raw = mock(LsDataRaw.class);
        when(raw.getOrgnlRawSn()).thenReturn(null);
        when(raw.getSrcType()).thenReturn(LsDataRaw.SRC_TYPE_IMPORTED);
        // 이관 영상은 관제 인입 행이 구조적으로 없다.
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(null);
        when(dataMetaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(
                LsDataMeta.create(RAW_SN, ExportPrivacyPolicy.IMPORT_SOURCE_ANONYMITY_KEY, "N"),
                LsDataMeta.create(RAW_SN, ExportPrivacyPolicy.IMPORT_SOURCE_PSEUDONYMITY_KEY, "Y"),
                LsDataMeta.create(RAW_SN, ExportPrivacyPolicy.IMPORT_SOURCE_PRIVACY_INCLUDED_KEY, "Y")));

        NiaExportContext ctx = assembler.assemble(RAW_SN, meta(), raw, List.of());

        assertThat(ctx.srcPrivacy())
                .as("이관 영상의 원천 축이 NONE 이면 조달 순서가 역전됐거나 이관 분기가 빠진 것이다")
                .isNotSameAs(SourcePrivacyMeta.NONE);
        assertThat(ctx.srcPrivacy().sourceExists()).isTrue();
        assertThat(ctx.srcPrivacy().anonyInclYn()).isEqualTo("N");
        assertThat(ctx.srcPrivacy().psdoInclYn()).isEqualTo("Y");
        assertThat(ctx.srcPrivacy().prvcInclYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("파생영상은_원천_축이_NONE_이다 — 상수를 대신 싣지 않는다")
    void derivativeVideoHasNoSourceAxis() {
        LsDataRaw derivative = mock(LsDataRaw.class);
        when(derivative.getOrgnlRawSn()).thenReturn(11L);
        IngestSourceRow row = ingestRow(); // ★ when(...) 인자 안에서 mock 을 만들면 미완료 스텁으로 터진다
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(row);

        NiaExportContext ctx = assembler.assemble(RAW_SN, meta(), derivative, List.of());

        assertThat(ctx.srcPrivacy()).isSameAs(SourcePrivacyMeta.NONE);
    }

    @Test
    @DisplayName("영상행이_없으면_원천_축이_NONE_이다 — 파생 여부를 알 수 없으므로 지어내지 않는다")
    void missingRawRowHasNoSourceAxis() {
        IngestSourceRow row = ingestRow(); // ★ when(...) 인자 안에서 mock 을 만들면 미완료 스텁으로 터진다
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(row);

        NiaExportContext ctx = assembler.assemble(RAW_SN, meta(), null, List.of());

        assertThat(ctx.srcPrivacy()).isSameAs(SourcePrivacyMeta.NONE);
    }

    @Test
    @DisplayName("원본_영상은_관제_인입값을_원천_축으로_싣고_event_id_도_인입에서_조달한다")
    void originalVideoUsesIngestValues() {
        LsDataRaw raw = mock(LsDataRaw.class);
        when(raw.getOrgnlRawSn()).thenReturn(null);
        when(raw.getSrcType()).thenReturn("CONTROL");
        IngestSourceRow row = ingestRow(); // ★ when(...) 인자 안에서 mock 을 만들면 미완료 스텁으로 터진다
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(row);

        NiaExportContext ctx = assembler.assemble(RAW_SN, meta(), raw, List.of());

        assertThat(ctx.srcPrivacy().anonyInclYn()).isEqualTo("N");
        assertThat(ctx.srcPrivacy().prvcInclYn()).isEqualTo("Y");
        assertThat(ctx.videoContext().ingestEvntId()).isEqualTo("ABA_0001");
    }

    @Test
    @DisplayName("비식별_영상_경로가_없으면_null_이다 — 원본 경로로 폴백하지 않는다")
    void missingDeidentPathStaysNull() {
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(null);

        NiaExportContext ctx = assembler.assemble(RAW_SN, meta(), null, List.of());

        assertThat(ctx.deidVideoPath()).isNull();
        assertThat(ctx.videoContext().deidVideoPath()).isNull();
    }

    @Test
    @DisplayName("비식별_영상_경로는_최신_성공_비식별_이력의_적재값을_그대로_쓴다")
    void deidentPathComesFromLatestSuccessLog() {
        LsDeidentProcLog procLog = mock(LsDeidentProcLog.class);
        when(procLog.getDeIdntfFilePathNm()).thenReturn("/nas/deid/videos/55/055-mask.mp4");
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(RAW_SN)).thenReturn(Optional.of(procLog));
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(null);

        NiaExportContext ctx = assembler.assemble(RAW_SN, meta(), null, List.of());

        assertThat(ctx.deidVideoPath()).isEqualTo("/nas/deid/videos/55/055-mask.mp4");
    }

    @Test
    @DisplayName("라벨_마스터는_중복_제거된_식별자로_한_번만_조회한다 — null 식별자는 버린다")
    void labelMastersLoadedOnceWithDistinctIds() {
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(null);
        LsLabel master = mock(LsLabel.class);
        when(master.getLabelId()).thenReturn(11L);
        when(master.getLabelNm()).thenReturn("사람");
        when(master.getLabelTypeCd()).thenReturn("BBOX");
        when(labelMasterRepository.findAllById(any())).thenReturn(List.of(master));

        List<Long> withDuplicatesAndNulls = new java.util.ArrayList<>();
        withDuplicatesAndNulls.add(11L);
        withDuplicatesAndNulls.add(null);
        withDuplicatesAndNulls.add(11L);

        NiaExportContext ctx = assembler.assemble(RAW_SN, meta(), null, withDuplicatesAndNulls);

        assertThat(NiaExportContextAssembler.distinctLabelIds(withDuplicatesAndNulls))
                .containsExactly(11L);
        assertThat(ctx.videoContext().categories()).hasSize(1);
    }

    @Test
    @DisplayName("라벨_식별자가_하나도_없으면_마스터를_조회하지_않는다")
    void noLabelIdsSkipsMasterLookup() {
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(null);

        NiaExportContext ctx = assembler.assemble(RAW_SN, meta(), null, List.of());

        assertThat(ctx.videoContext().categories()).isEmpty();
        org.mockito.Mockito.verify(labelMasterRepository, org.mockito.Mockito.never()).findAllById(any());
    }

    @Test
    @DisplayName("활성_영상_메타가_없으면_조달을_건너뛴다 — 동결본 없이는 같은 구조의 문서를 만들 근거가 없다")
    void noActiveMetaSkips() {
        when(videoMetaRepository.findByRawSnAndActiveYn(eq(RAW_SN), any())).thenReturn(List.of());

        assertThat(assembler.assemble(RAW_SN, List.of())).isEmpty();
    }

    @Test
    @DisplayName("메타_직접조회_오버로드는_활성_메타와_영상행을_읽어_같은_컨텍스트를_만든다")
    void selfLoadingOverloadReadsMetaAndRaw() {
        LsDatasetVideoMeta meta = meta();
        when(videoMetaRepository.findByRawSnAndActiveYn(eq(RAW_SN), any())).thenReturn(List.of(meta));
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.empty());
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(null);

        Optional<NiaExportContext> ctx = assembler.assemble(RAW_SN, List.of());

        assertThat(ctx).isPresent();
        assertThat(ctx.get().meta()).isSameAs(meta);
        assertThat(ctx.get().raw()).isNull();
        assertThat(ctx.get().videoContext()).isNotNull();
    }

    @Test
    @DisplayName("동결_이벤트_어노테이션이_깨져_있어도_산출을_깨지_않고_null_로_둔다")
    void malformedFrozenEventAnnotationFailsSecure() {
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(null);
        LsDatasetVideoMeta broken = mock(LsDatasetVideoMeta.class);
        when(broken.getRawSn()).thenReturn(RAW_SN);
        when(broken.getEvntAnnoCn()).thenReturn("{not-json");

        NiaExportContext ctx = assembler.assemble(RAW_SN, broken, null, List.of());

        assertThat(ctx.videoContext().eventAnnotation()).isNull();
    }

    // ── 고정 입력 ──────────────────────────────────────────────────────────────

    private static LsDatasetVideoMeta meta() {
        return LsDatasetVideoMeta.builder()
                .rawSn(RAW_SN)
                .rawFilePathNm("/nas/raw/55/original.mp4")
                .build();
    }

    private static IngestSourceRow ingestRow() {
        IngestSourceRow row = mock(IngestSourceRow.class);
        when(row.getEvntId()).thenReturn("ABA_0001");
        when(row.getSrcAnonyInclYn()).thenReturn("N");
        when(row.getSrcPsdoInclYn()).thenReturn("N");
        when(row.getSrcPrvcInclYn()).thenReturn("Y");
        return row;
    }
}
