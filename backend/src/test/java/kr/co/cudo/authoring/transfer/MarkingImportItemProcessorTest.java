package kr.co.cudo.authoring.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.marking.service.MarkingActivationTxService;
import kr.co.cudo.authoring.transfer.config.MarkingImportProperties;
import kr.co.cudo.authoring.transfer.entity.LsEblcUldJobArtcl;
import kr.co.cudo.authoring.transfer.parser.MarkingDocumentParser;
import kr.co.cudo.authoring.transfer.service.ImportFileStager;
import kr.co.cudo.authoring.transfer.service.MarkingImportAssessor;
import kr.co.cudo.authoring.transfer.service.MarkingImportItemProcessor;
import kr.co.cudo.authoring.transfer.service.MarkingImportJobMeta;
import kr.co.cudo.authoring.transfer.service.MarkingImportJobTxService;
import kr.co.cudo.authoring.video.dto.MarkingImportIngestCommand;
import kr.co.cudo.authoring.video.dto.MarkingImportIngestResult;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.TrainingVideoIngestService;
import kr.co.cudo.authoring.video.service.port.VideoProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 항목 하나를 적재하는 <b>순서</b>를 고정한다 — 이 순서가 무너지면 되돌릴 수 없는 손실이 난다.
 *
 * <h3>왜 순서를 시험으로 붙잡는가</h3>
 * <p>세 가지가 순서에 걸려 있고, 전부 <b>결과만 보고는 알 수 없다</b>.
 * <ol>
 *   <li><b>식별자 중복 확인이 복사보다 먼저</b>여야 한다. 저장 위치가 식별자에서 나오므로, 뒤로
 *       미루면 이미 있는 영상의 파일 자리에 다른 영상을 덮어쓴다. 덮어쓴 뒤에는 <b>되돌릴 수 없다</b>.
 *       그런데 그때도 항목은 「건너뜀」으로 끝나므로 상태만 보면 정상과 구분되지 않는다.</li>
 *   <li><b>속도 대조가 복사·적재보다 먼저</b>여야 한다. 뒤로 미루면 영상은 이미 들어왔는데 마킹만
 *       못 다는 상태가 되어 <b>아무도 처리하지 않는 영상</b>이 남는다.</li>
 *   <li><b>예약 마킹에 고정하는 속도는 영상에서 읽은 값</b>이어야 한다. 문서에서 역산한 값을 고정하면
 *       뒤따르는 추출이 미세하게 어긋난 자리에서 프레임을 뽑는다.</li>
 * </ol>
 * <p>그래서 상태가 아니라 <b>협력자를 불렀는지</b>를 단언한다.
 *
 * <h3>영상 판독기와 적재기는 갈아 끼운다</h3>
 * <p>둘 다 이 클래스의 판단 대상이 아니라 <b>결과를 주는 쪽</b>이다. 실물을 쓰면 외부 실행 파일과
 * 데이터베이스가 있어야 하고, 그러면 여기서 보려는 순서가 그 환경 차이에 묻힌다.
 *
 * @design DOMAIN-017
 * @design ADR-052
 * @design API-217
 * @design AC-1032
 * @design AC-1033
 * @design SEQ-030
 */
class MarkingImportItemProcessorTest {

    /** 표본 문서가 역산해 내는 속도 — 프레임 100↔10초, 400↔20초. */
    private static final double DECLARED_FPS = 30.0;

    private static final double FPS_TOLERANCE = 0.5;

    private static final String CLIP_ID = "sample";

    /** 이 시험이 쓰는 항목 식별번호 — 신호가 <그 항목>에 적히는지 겨눌 수 있게 고정한다. */
    private static final long ARTCL_SN = 4242L;

    /** setUp 이 넣는 되돌리기 임계(분) — 신호 간격이 여기서 파생된다. */
    private static final long STALE_TIMEOUT_MINUTES = 30L;

    @TempDir
    Path tempRoot;

    private Path root;
    private Path markingPath;
    private Path videoPath;

    private VideoRepository videoRepository;
    private ImportFileStager fileStager;
    private MarkingImportJobTxService jobTxService;
    private long fakeNanos;
    private TrainingVideoIngestService ingestService;
    private LsMarkingRepository markingRepository;
    private MarkingActivationTxService activationTxService;
    private FakeProbe probe;
    private MarkingImportItemProcessor processor;

    @BeforeEach
    void setUp() throws IOException {
        root = tempRoot.toRealPath();
        markingPath = root.resolve(CLIP_ID + ".json");
        videoPath = root.resolve(CLIP_ID + ".mp4");
        Files.writeString(markingPath, """
                [{"id":1,"video_name":"%s.mp4","notes":"이벤트",
                  "images":[{"filename":"a.jpg","frame":100,"time":"00:00:10.000"},
                            {"filename":"b.jpg","frame":400,"time":"00:00:20.000"}]}]
                """.formatted(CLIP_ID), StandardCharsets.UTF_8);
        Files.writeString(videoPath, "video", StandardCharsets.UTF_8);

        VideoArtifactRootResolver resolver = new VideoArtifactRootResolver(
                root.toString(), "", root.toString(), root.toString(), root.toString(), "co-locate");
        ImportSourcePolicy policy = new ImportSourcePolicy(resolver);
        MarkingImportProperties properties = new MarkingImportProperties(
                8, 1000, 1000, 100, 500, List.of("mp4"), 2, 3, FPS_TOLERANCE, STALE_TIMEOUT_MINUTES);

        videoRepository = mock(VideoRepository.class);
        fileStager = mock(ImportFileStager.class);
        jobTxService = mock(MarkingImportJobTxService.class);
        ingestService = mock(TrainingVideoIngestService.class);
        markingRepository = mock(LsMarkingRepository.class);
        activationTxService = mock(MarkingActivationTxService.class);
        probe = new FakeProbe(DECLARED_FPS);

        MarkingDocumentParser parser = new MarkingDocumentParser(new ObjectMapper());
        MarkingImportAssessor assessor =
                new MarkingImportAssessor(parser, probe, videoRepository, properties);
        fakeNanos = 0L;
        processor = new MarkingImportItemProcessor(policy, parser, assessor, probe, videoRepository,
                fileStager, ingestService, markingRepository, activationTxService,
                new ObjectMapper(), jobTxService, properties, root.resolve("storage").toString(),
                () -> fakeNanos);
    }

    // ------------------------------------------------------------------ 정상

    @Test
    @DisplayName("복사하고_영상을_만들고_예약_마킹을_만든_뒤_성공으로_끝낸다")
    void 복사하고_영상을_만들고_예약_마킹을_만든_뒤_성공으로_끝낸다() {
        when(ingestService.ingestMarkingImport(any())).thenReturn(MarkingImportIngestResult.ingested(77L));

        MarkingImportItemProcessor.ItemOutcome outcome = processor.process(item(), meta(), "admin");

        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.rawSn()).isEqualTo(77L);
        assertThat(outcome.reason()).isNull();
        verify(fileStager).copy(any(), anyString(), any());
        verify(markingRepository).save(any(LsMarking.class));
    }

    @Test
    @DisplayName("적재_경로는_원래_자리가_아니라_저작도구_저장소_안이다")
    void 적재_경로는_원래_자리가_아니라_저작도구_저장소_안이다() {
        when(ingestService.ingestMarkingImport(any())).thenReturn(MarkingImportIngestResult.ingested(77L));

        processor.process(item(), meta(), "admin");

        ArgumentCaptor<MarkingImportIngestCommand> command =
                ArgumentCaptor.forClass(MarkingImportIngestCommand.class);
        verify(ingestService).ingestMarkingImport(command.capture());
        // 원래 자리를 그대로 가리키면 그 영상에 딸린 산출물이 남의 폴더 옆에 쌓이고, 그 폴더가
        // 치워지면 이미 적재한 영상이 깨진다(API-217).
        assertThat(command.getValue().rawFilePathNm()).contains("storage").contains(CLIP_ID);
        assertThat(command.getValue().vmsClipId()).isEqualTo(CLIP_ID);
        // 사람이 지정한 값이 그대로 실린다 — 문서의 비고를 유형으로 파싱한 값이 아니다.
        assertThat(command.getValue().evntTypeCd()).isEqualTo("EV01000101");
        assertThat(command.getValue().prvcTypeCd()).isEqualTo("PRVC");
    }

    @Test
    @DisplayName("★예약_마킹에_고정하는_속도는_영상에서_읽은_값이다")
    void 예약_마킹에_고정하는_속도는_영상에서_읽은_값이다() {
        // 문서에서 역산한 값을 고정하면 뒤따르는 추출이 미세하게 어긋난 자리에서 프레임을 뽑는다.
        // 허용 오차 안이라 적재는 되지만, 고정되는 값은 실측이어야 한다(API-216).
        probe.fps = DECLARED_FPS + 0.2;
        when(ingestService.ingestMarkingImport(any())).thenReturn(MarkingImportIngestResult.ingested(77L));

        processor.process(item(), meta(), "admin");

        ArgumentCaptor<LsMarking> marking = ArgumentCaptor.forClass(LsMarking.class);
        verify(markingRepository).save(marking.capture());
        assertThat(marking.getValue().getFps()).isEqualTo(DECLARED_FPS + 0.2);
        assertThat(marking.getValue().getSttsCd()).isEqualTo(LsMarking.STATUS_RESERVED);
        assertThat(marking.getValue().getMarkModeCd()).isEqualTo(LsMarking.MODE_MANUAL);
        assertThat(marking.getValue().getMarkCn()).contains("100").contains("400");
    }

    @Test
    @DisplayName("영상에서_속도를_읽지_못하면_역산값으로_물러서고_둘_다_없으면_비운다")
    void 영상에서_속도를_읽지_못하면_역산값으로_물러서고_둘_다_없으면_비운다() {
        probe.fps = null;
        when(ingestService.ingestMarkingImport(any())).thenReturn(MarkingImportIngestResult.ingested(77L));

        processor.process(item(), meta(), "admin");

        ArgumentCaptor<LsMarking> marking = ArgumentCaptor.forClass(LsMarking.class);
        verify(markingRepository).save(marking.capture());
        assertThat(marking.getValue().getFps()).isEqualTo(DECLARED_FPS);
    }

    // ------------------------------------------------------------------ 순서 (핵심)

    @Test
    @DisplayName("★이미_들어와_있는_식별자는_복사도_적재도_하지_않고_건너뛴다")
    void 이미_들어와_있는_식별자는_복사도_적재도_하지_않고_건너뛴다() {
        // ★저장 위치가 식별자에서 나온다. 확인을 복사 뒤로 미루면 <이미 있는 영상의 파일 자리에>
        //  다른 영상을 덮어쓰고, 그 손실은 되돌릴 수 없다. 상태만 보면 정상 건너뜀과 구분되지 않으므로
        //  <복사기를 부르지 않았다>를 단언한다.
        LsDataRaw existing = mock(LsDataRaw.class);
        when(existing.getRawSn()).thenReturn(42L);
        when(videoRepository.findByVmsClipId(CLIP_ID)).thenReturn(Optional.of(existing));

        MarkingImportItemProcessor.ItemOutcome outcome = processor.process(item(), meta(), "admin");

        assertThat(outcome.status()).isEqualTo(LsEblcUldJobArtcl.ARTCL_STTS_SKIPPED);
        // 무엇과 부딪혔는지 모르면 사람이 되짚을 수 없다.
        assertThat(outcome.rawSn()).isEqualTo(42L);
        assertThat(outcome.reason()).contains("이미");
        verifyNoInteractions(fileStager);
        verifyNoInteractions(ingestService);
        verifyNoInteractions(markingRepository);
    }

    @Test
    @DisplayName("★속도가_크게_어긋나면_복사도_적재도_하지_않고_멈춘다")
    void 속도가_크게_어긋나면_복사도_적재도_하지_않고_멈춘다() {
        // ★적재 뒤로 미루면 영상은 이미 들어왔는데 마킹만 못 다는 상태가 되어 <아무도 처리하지 않는
        //  영상>이 남는다. 그 영상은 비식별만 끝난 채 멈춘다.
        probe.fps = DECLARED_FPS + FPS_TOLERANCE + 0.1;

        MarkingImportItemProcessor.ItemOutcome outcome = processor.process(item(), meta(), "admin");

        // 짝을 못 찾은 것이 아니라 처리하다 멈춘 것이므로 실패다 — 건너뜀과 구분한다.
        assertThat(outcome.status()).isEqualTo(LsEblcUldJobArtcl.ARTCL_STTS_FAILED);
        assertThat(outcome.reason()).contains("속도");
        verifyNoInteractions(fileStager);
        verifyNoInteractions(ingestService);
    }

    @Test
    @DisplayName("★적재_직후_같은_식별자가_들어온_것이_드러나도_복사본을_지우지_않는다")
    void 적재_직후_같은_식별자가_들어온_것이_드러나도_복사본을_지우지_않는다() {
        // ★저장 위치가 식별자에서 나오므로 그 자리는 <이미 있던 영상>의 파일 자리이기도 하다.
        //  정리한다고 지우면 남의 영상 파일을 지운다.
        when(ingestService.ingestMarkingImport(any())).thenReturn(MarkingImportIngestResult.duplicate(42L));

        MarkingImportItemProcessor.ItemOutcome outcome = processor.process(item(), meta(), "admin");

        assertThat(outcome.status()).isEqualTo(LsEblcUldJobArtcl.ARTCL_STTS_SKIPPED);
        assertThat(outcome.rawSn()).isEqualTo(42L);
        verify(fileStager, never()).cleanupQuietly(any());
        verifyNoInteractions(markingRepository);
    }

    // ------------------------------------------------------------------ 처리 중 신호

    @Test
    @DisplayName("★복사가_시작되면_처리_중_신호를_적는다")
    void 복사가_시작되면_처리_중_신호를_적는다() {
        // ★영상이 수백 MB~수 GB 라 복사 하나가 되돌리기 임계를 넘길 수 있다. 그 동안 갱신 시각이
        //  집은 순간에 멈춰 있으면 되돌리기가 <지금 돌고 있는 처리를 빼앗아> 같은 영상을 두 번 옮긴다.
        streamProgress(1);
        when(ingestService.ingestMarkingImport(any())).thenReturn(MarkingImportIngestResult.ingested(77L));

        processor.process(item(), meta(), "admin");

        verify(jobTxService).heartbeat(ARTCL_SN);
    }

    @Test
    @DisplayName("★신호는_간격_안에서는_다시_적지_않고_간격을_넘으면_다시_적는다")
    void 신호는_간격_안에서는_다시_적지_않고_간격을_넘으면_다시_적는다() {
        // 덩어리마다 적으면 큰 파일 하나가 수천 번의 쓰기를 만들어 다른 처리의 커넥션을 잠식한다.
        // 반대로 아예 안 적으면 방어가 성립하지 않는다. 거르는 축은 <시간>이다.
        long interval = TimeUnit.SECONDS.toNanos(
                MarkingImportItemProcessor.heartbeatIntervalSec(STALE_TIMEOUT_MINUTES));
        // 0(첫 신호) → 간격 직전(무시) → 간격 도달(적음) → 그 직후(무시) → 두 배(적음)
        streamProgressAt(0L, interval - 1, interval, interval + 1, interval * 2);
        when(ingestService.ingestMarkingImport(any())).thenReturn(MarkingImportIngestResult.ingested(77L));

        processor.process(item(), meta(), "admin");

        verify(jobTxService, times(3)).heartbeat(ARTCL_SN);
    }

    @Test
    @DisplayName("★신호_간격은_되돌리기_임계에서_파생되며_위아래로_잘린다")
    void 신호_간격은_되돌리기_임계에서_파생되며_위아래로_잘린다() {
        // ★상수로 박으면 임계만 줄였을 때 신호가 그보다 뜸해져 <정상 처리가 멈춘 것으로 판정>된다.
        //  임계의 1/4 을 취해 임계 안에 신호가 최소 세 번은 들어가게 한다.
        assertThat(MarkingImportItemProcessor.heartbeatIntervalSec(4)).isEqualTo(60L);
        assertThat(MarkingImportItemProcessor.heartbeatIntervalSec(2)).isEqualTo(30L);
        // 위아래 상한에서 잘린다 — 임계를 길게 잡아도 방치가 길어지지 않고, 짧게 잡아도 쓰기가 폭주하지 않는다.
        assertThat(MarkingImportItemProcessor.heartbeatIntervalSec(600))
                .isEqualTo(MarkingImportItemProcessor.HEARTBEAT_MAX_INTERVAL_SEC);
        assertThat(MarkingImportItemProcessor.heartbeatIntervalSec(0))
                .isEqualTo(MarkingImportItemProcessor.HEARTBEAT_MIN_INTERVAL_SEC);
        // 임계가 음수여도 하한으로 잘려 무해하다 — 임계 자체의 하한은 설정이 따로 지킨다.
        assertThat(MarkingImportItemProcessor.heartbeatIntervalSec(-10))
                .isEqualTo(MarkingImportItemProcessor.HEARTBEAT_MIN_INTERVAL_SEC);
    }

    @Test
    @DisplayName("신호를_적지_못해도_복사와_적재는_계속된다")
    void 신호를_적지_못해도_복사와_적재는_계속된다() {
        // 신호는 복사를 돕는 것이지 복사의 조건이 아니다. 여기서 예외가 새면 멀쩡한 적재가 실패한다.
        streamProgress(1);
        org.mockito.Mockito.doThrow(new IllegalStateException("원장 쓰기 실패"))
                .when(jobTxService).heartbeat(ARTCL_SN);
        when(ingestService.ingestMarkingImport(any())).thenReturn(MarkingImportIngestResult.ingested(77L));

        assertThat(processor.process(item(), meta(), "admin").succeeded()).isTrue();
    }

    // ------------------------------------------------------------------ 건너뜀·실패

    @Test
    @DisplayName("짝을_찾지_못한_항목은_아무것도_하지_않고_건너뛴다")
    void 짝을_찾지_못한_항목은_아무것도_하지_않고_건너뛴다() {
        MarkingImportItemProcessor.ItemOutcome outcome =
                processor.process(itemWithoutVideo(), meta(), "admin");

        assertThat(outcome.status()).isEqualTo(LsEblcUldJobArtcl.ARTCL_STTS_SKIPPED);
        assertThat(outcome.reason()).contains("영상");
        verifyNoInteractions(fileStager);
        verifyNoInteractions(ingestService);
    }

    @Test
    @DisplayName("적재가_값을_거부하면_복사본을_되돌리고_실패로_끝낸다")
    void 적재가_값을_거부하면_복사본을_되돌리고_실패로_끝낸다() {
        // 다시 해도 같은 결과인 영구 사유다 — 복사본만 남으면 아무도 가리키지 않는 파일이 쌓인다.
        when(ingestService.ingestMarkingImport(any()))
                .thenThrow(new IllegalArgumentException("이벤트 유형 코드 형식이 올바르지 않습니다."));

        MarkingImportItemProcessor.ItemOutcome outcome = processor.process(item(), meta(), "admin");

        assertThat(outcome.status()).isEqualTo(LsEblcUldJobArtcl.ARTCL_STTS_FAILED);
        verify(fileStager).cleanupQuietly(any());
        verifyNoInteractions(markingRepository);
    }

    @Test
    @DisplayName("예상하지_못한_실패도_예외로_올리지_않고_사유만_남긴다")
    void 예상하지_못한_실패도_예외로_올리지_않고_사유만_남긴다() {
        // 예외가 올라가면 일꾼이 멈춰 남은 항목이 통째로 지연된다. 또 원인 메시지를 그대로 사유에
        // 담지 않는다(CWE-209).
        when(ingestService.ingestMarkingImport(any()))
                .thenThrow(new IllegalStateException("kr.co.cudo.internal.Boom at line 42"));

        MarkingImportItemProcessor.ItemOutcome outcome = processor.process(item(), meta(), "admin");

        assertThat(outcome.status()).isEqualTo(LsEblcUldJobArtcl.ARTCL_STTS_FAILED);
        assertThat(outcome.reason()).doesNotContain("kr.co.cudo").doesNotContain("Boom");
    }

    // ------------------------------------------------------------------ 따라잡기

    @Test
    @DisplayName("★예약을_걸기_전에_비식별이_끝났으면_여기서_깨운다")
    void 예약을_걸기_전에_비식별이_끝났으면_여기서_깨운다() {
        // ★비식별은 적재 신호를 받아 비동기로 도므로 예약보다 먼저 끝날 수 있다. 그때 깨우는 쪽은
        //  예약을 보지 못했고, 이 확인이 없으면 그 예약은 <아무도 손대지 않는 채> 남는다.
        LsDataRaw ingested = mock(LsDataRaw.class);
        when(ingested.getDataSttsCd()).thenReturn(LsDataRaw.DATA_STTS_MARKING_READY);
        when(ingestService.ingestMarkingImport(any())).thenReturn(MarkingImportIngestResult.ingested(77L));
        when(videoRepository.findById(77L)).thenReturn(Optional.of(ingested));
        when(activationTxService.activateReserved(77L)).thenReturn(Optional.of(5L));

        processor.process(item(), meta(), "admin");

        verify(activationTxService).activateReserved(77L);
    }

    @Test
    @DisplayName("★예약을_걸기_전에_비식별이_실패했으면_여기서_마감한다")
    void 예약을_걸기_전에_비식별이_실패했으면_여기서_마감한다() {
        LsDataRaw ingested = mock(LsDataRaw.class);
        when(ingested.getDeIdntfYn()).thenReturn("F");
        when(ingestService.ingestMarkingImport(any())).thenReturn(MarkingImportIngestResult.ingested(77L));
        when(videoRepository.findById(77L)).thenReturn(Optional.of(ingested));

        processor.process(item(), meta(), "admin");

        verify(activationTxService).closeReservations(eqLong(77L), anyString());
    }

    @Test
    @DisplayName("비식별이_아직_돌고_있으면_깨우지도_마감하지도_않는다")
    void 비식별이_아직_돌고_있으면_깨우지도_마감하지도_않는다() {
        // 여기서 깨우면 브리지가 자기 가드에 막혀 skip 으로 판정하고, 그 skip 이 방금 깨운 마킹을
        // 종결시킨다 — 예약이 조용히 사라진다.
        LsDataRaw ingested = mock(LsDataRaw.class);
        when(ingested.getDataSttsCd()).thenReturn(LsDataRaw.STATUS_PENDING);
        when(ingested.getDeIdntfYn()).thenReturn("N");
        when(ingestService.ingestMarkingImport(any())).thenReturn(MarkingImportIngestResult.ingested(77L));
        when(videoRepository.findById(77L)).thenReturn(Optional.of(ingested));

        processor.process(item(), meta(), "admin");

        verifyNoInteractions(activationTxService);
    }

    @Test
    @DisplayName("따라잡기가_실패해도_적재는_성공으로_끝난다")
    void 따라잡기가_실패해도_적재는_성공으로_끝난다() {
        // 적재는 이미 끝났고 되돌리지 않는다. 여기서 예외를 올리면 정상 적재가 실패로 마감된다.
        when(ingestService.ingestMarkingImport(any())).thenReturn(MarkingImportIngestResult.ingested(77L));
        when(videoRepository.findById(77L)).thenThrow(new IllegalStateException("조회 실패"));

        assertThat(processor.process(item(), meta(), "admin").succeeded()).isTrue();
    }

    // ------------------------------------------------------------------ 보조

    private static long eqLong(long value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    /** 복사기가 진행 신호를 {@code count} 번 흘리도록 꾸민다(시각은 흐르지 않는다). */
    private void streamProgress(int count) {
        long[] at = new long[count];
        streamProgressAt(at);
    }

    /**
     * 복사기가 <b>주어진 시각들</b>에 진행 신호를 흘리도록 꾸민다.
     *
     * <p>시각을 시험이 정하므로 간격이 지켜지는지를 <b>기다리지 않고</b> 확인할 수 있다.
     */
    private void streamProgressAt(long... nanos) {
        org.mockito.Mockito.doAnswer(invocation -> {
            ImportFileStager.CopyProgress progress = invocation.getArgument(2);
            for (int i = 0; i < nanos.length; i++) {
                fakeNanos = nanos[i];
                progress.onCopied(i);
            }
            return null;
        }).when(fileStager).copy(any(), anyString(), any());
    }

    private LsEblcUldJobArtcl item() {
        return withSn(LsEblcUldJobArtcl.pending(1L, markingPath.toString(), videoPath.toString()));
    }

    private LsEblcUldJobArtcl itemWithoutVideo() {
        return withSn(LsEblcUldJobArtcl.pending(1L, markingPath.toString(), null));
    }

    /**
     * 식별번호를 심는다 — 원장에서 나온 항목에는 항상 있는 값이지만 새로 만든 것에는 없다.
     *
     * <p>신호를 적는 대상이 그 식별번호이므로, 비워 두면 이 시험이 실제 호출 형태와 달라진다.
     */
    private static LsEblcUldJobArtcl withSn(LsEblcUldJobArtcl item) {
        org.springframework.test.util.ReflectionTestUtils.setField(item, "eblcUldJobArtclSn", ARTCL_SN);
        return item;
    }

    private static MarkingImportJobMeta meta() {
        return new MarkingImportJobMeta("EV01000101", "4113500000", "CCTV_IT_0001", "PRVC", null, null);
    }

    /** 갈아 끼운 영상 판독기. */
    private static final class FakeProbe implements VideoProbe {
        private Double fps;

        private FakeProbe(Double fps) {
            this.fps = fps;
        }

        @Override
        public VideoMeta probe(Path video) {
            return new VideoMeta(1920, 1080, "h264", fps, null, 60_000L, null);
        }
    }
}
