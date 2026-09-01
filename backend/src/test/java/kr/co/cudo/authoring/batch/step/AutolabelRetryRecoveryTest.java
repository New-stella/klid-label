package kr.co.cudo.authoring.batch.step;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService.AnnotationToggle;
import kr.co.cudo.authoring.batch.policy.PresetResolution;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.AiWorkload;
import kr.co.cudo.authoring.common.client.dto.Sam2Request;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.client.dto.YoloTrackRequest;
import kr.co.cudo.authoring.common.config.DeployedEnvironmentDetector;
import kr.co.cudo.authoring.label.service.FrameBoundsResolver;
import kr.co.cudo.authoring.label.service.LabelMasterService;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ★ 이번 멱등 수정의 <b>존재 이유</b>를 고정하는 교차 스텝 회귀 (@req R1).
 *
 * <h3>막는 실패 모드 — 조용한 미완성 성공</h3>
 * <p>{@link BbHint} 는 {@code BatchContext} <b>인메모리</b>로만 전달되고, YOLO 와 SAM2 는 <b>별개
 * 트랜잭션</b>(스텝 1건 = tx 1건)이다. 그래서 "YOLO 성공(라벨 커밋) → SAM2 실패 → 배치 FAILED →
 * 자동 재시도" 가 현실적으로 발생한다. 이때 YOLO 가 <b>추론까지</b> 건너뛰면:
 * <ol>
 *   <li>hints 0건 → SAM2 가 할 일이 없어 <b>예외 없이 0 반환</b></li>
 *   <li>INTERPOLATE 통과 → 배치가 <b>COMPLETED 로 완주</b></li>
 *   <li>결과: 폴리곤은 없는데 오류 신호가 어디에도 없다</li>
 * </ol>
 * 즉 큰 실패가 무증상 데이터 손실로 바뀐다. 그래서 YOLO 는 <b>적재만</b> 건너뛰고 추론·힌트 발행은 수행한다.
 *
 * <h3>왜 "폴리곤 전용 라벨" 축으로 세우는가 (실측 근거)</h3>
 * <p>{@link Sam2SegmentStep} 의 {@code buildJobs} 는 프레임의 <b>DB 자동 BBOX 행</b>도 읽어 job 을 만든다.
 * 따라서 BBOX 를 남기는 라벨의 폴리곤은 hints 가 0건이어도 DB 에서 재구성된다. 손실이 실제로 확정되는
 * 조합은 <b>같은 프레임에 BBOX 라벨과 폴리곤 전용 라벨이 섞여 있을 때</b>다:
 * <ul>
 *   <li>BBOX 라벨이 {@code LS_DATA_LBL_AI_INFO(YOLO)} 행을 만들어 그 프레임이 <b>skip 대상</b>이 되고,</li>
 *   <li>폴리곤 전용 라벨은 DB 에 BBOX 행이 없어 <b>hints 가 유일한 전달 수단</b>이다.</li>
 * </ul>
 * (폴리곤 전용 라벨만 있는 프레임은 YOLO AI 메타가 아예 생기지 않아 skip 대상이 되지 않고 스스로 복구된다.)
 */
class AutolabelRetryRecoveryTest {

    private static final long RAW_SN = 900L;
    private static final long SRC_SN = 10L;

    @TempDir
    Path tempDir;

    private AiServerClient aiServerClient;
    private LsDataSrcRepository srcRepository;
    private LsDataLblRepository lblRepository;
    private VideoRepository videoRepository;
    private PresetLabelLookupService presetLabelLookup;
    private LabelMasterService labelMasterService;
    private YoloAutolabelStep yoloStep;
    private Sam2SegmentStep sam2Step;

    private final AtomicLong lblSnSeq = new AtomicLong(1);
    /** 실제 저장된 라벨 전량 — 중복 적재 여부를 건수로 판정한다. */
    private final List<LsDataLbl> persisted = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        aiServerClient = mock(AiServerClient.class);
        srcRepository = mock(LsDataSrcRepository.class);
        lblRepository = mock(LsDataLblRepository.class);
        videoRepository = mock(VideoRepository.class);
        presetLabelLookup = mock(PresetLabelLookupService.class);
        BatchStatusService batchStatusService = mock(BatchStatusService.class);
        labelMasterService = mock(LabelMasterService.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        FrameBoundsResolver frameBoundsResolver = mock(FrameBoundsResolver.class);

        when(systemConfigService.getInt(any())).thenReturn(null);
        when(labelMasterService.findLabelIdByDtctType(anyString())).thenReturn(Optional.empty());
        when(frameBoundsResolver.resolve(any())).thenReturn(Optional.of(new int[]{1280, 720}));
        when(videoRepository.findById(anyLong())).thenReturn(Optional.empty());

        when(lblRepository.save(any(LsDataLbl.class))).thenAnswer(inv -> {
            LsDataLbl arg = inv.getArgument(0);
            setField(arg, "lblSn", lblSnSeq.getAndIncrement());
            persisted.add(arg);
            return arg;
        });
        when(lblRepository.saveAll(any())).thenAnswer(inv -> {
            Iterable<LsDataLbl> in = inv.getArgument(0);
            List<LsDataLbl> out = new ArrayList<>();
            for (LsDataLbl l : in) {
                out.add(lblRepository.save(l));
            }
            return out;
        });

        // 프리셋: person = 폴리곤 전용(BBOX 미저장) · car = BOTH. 같은 프레임에 섞여 있는 형상이다.
        when(presetLabelLookup.resolve(any())).thenReturn(PresetResolution.resolved(Map.of(
                "person", new AnnotationToggle(false, true),
                "car", new AnnotationToggle(true, true))));

        Path rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);
        Files.write(rawDir.resolve(SRC_SN + ".jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});

        DeployedEnvironmentDetector devEnv = devEnvironment();
        yoloStep = new YoloAutolabelStep(aiServerClient, srcRepository, lblRepository,
                videoRepository, presetLabelLookup, batchStatusService, systemConfigService, labelMasterService,
                frameBoundsResolver, new ObjectMapper(), rawDir.toString(), devEnv);
        sam2Step = new Sam2SegmentStep(aiServerClient, srcRepository, lblRepository,
                videoRepository, presetLabelLookup, labelMasterService, new ObjectMapper(),
                rawDir.toString(), devEnv);

        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(newSrc(SRC_SN)));
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH)))
                .thenReturn(Mono.just(new YoloResponse(List.of(
                        new YoloResponse.Detection("person", List.of(10.0, 20.0, 30.0, 40.0), 0.92),
                        new YoloResponse.Detection("car", List.of(50.0, 60.0, 70.0, 80.0), 0.81)))));
        when(aiServerClient.segment(any(Sam2Request.class), eq(AiWorkload.BATCH)))
                .thenReturn(Mono.just(new Sam2Response(
                        List.of(List.of(1.0, 2.0), List.of(3.0, 4.0), List.of(5.0, 6.0)), 0.9)));
    }

    private static DeployedEnvironmentDetector devEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        return new DeployedEnvironmentDetector(env);
    }

    private static LsDataSrc newSrc(Long srcSn) {
        LsDataSrc src = LsDataSrc.create(RAW_SN, srcSn, srcSn + ".jpg", null);
        setField(src, "srcSn", srcSn);
        return src;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** 저장된 라벨 중 지정 타입·라벨명 건수. */
    private long countPersisted(String lblTypeCd, String labelNm) {
        return persisted.stream()
                .filter(l -> lblTypeCd.equals(l.getLblTypeCd()) && labelNm.equals(l.getLabelNm()))
                .count();
    }

    @Test
    @DisplayName("YOLO_성공_후_SAM2_만_실패해_재시도되면_폴리곤이_복구된다")
    void polygonRecoveredWhenOnlySam2FailedAndPipelineRetried() {
        // ── 1회차: 최초 실행. YOLO 는 car BBOX 만 적재하고(person 은 폴리곤 전용) 두 힌트를 발행한다.
        when(lblRepository.findDistinctSrcSnsByRawSnAndLblSrcCd(RAW_SN, LsDataLbl.SRC_YOLO))
                .thenReturn(List.of());
        List<BbHint> firstHints = yoloStep.run(RAW_SN);

        assertThat(firstHints).extracting(BbHint::label).containsExactly("person", "car");
        assertThat(countPersisted(LsDataLbl.TYPE_BBOX, "car")).isEqualTo(1);
        assertThat(countPersisted(LsDataLbl.TYPE_BBOX, "person"))
                .as("폴리곤 전용 라벨은 BBOX 를 남기지 않는다 — hints 가 유일한 전달 수단이다")
                .isZero();
        int persistedAfterFirstYolo = persisted.size();
        // …그리고 SAM2 가 실패했다고 가정한다(폴리곤 0건 · 배치 FAILED → 자동 재시도 큐 재무장).

        // ── 2회차(재시도): 파이프라인이 선두부터 다시 돈다.
        //    이제 그 프레임에는 YOLO AI 메타가 있고(car BBOX 유래), SAM2 메타는 없다.
        when(lblRepository.findDistinctSrcSnsByRawSnAndLblSrcCd(RAW_SN, LsDataLbl.SRC_YOLO))
                .thenReturn(List.of(SRC_SN));
        when(lblRepository.findDistinctSrcSnsByRawSnAndLblSrcCd(RAW_SN, LsDataLbl.SRC_SAM2))
                .thenReturn(List.of());
        // 1회차에 커밋된 car BBOX 행이 DB 에 있다(SAM2 의 buildJobs 가 읽는 축).
        LsDataLbl persistedCarBbox = persisted.stream()
                .filter(l -> "car".equals(l.getLabelNm()) && LsDataLbl.TYPE_BBOX.equals(l.getLblTypeCd()))
                .findFirst().orElseThrow();
        when(lblRepository.findBySrcSnAndAutoLblYn(SRC_SN, LsDataLbl.AUTO_YES))
                .thenReturn(List.of(persistedCarBbox));

        List<BbHint> retryHints = yoloStep.run(RAW_SN);

        // then(YOLO) — 중복 적재 0건이면서 힌트는 최초 실행과 동일하게 재발행된다.
        assertThat(persisted)
                .as("재시도가 자동 라벨을 중복 적재하면 안 된다")
                .hasSize(persistedAfterFirstYolo);
        assertThat(retryHints)
                .as("★ 이 힌트가 0건이면 SAM2 가 조용히 빈손으로 완주한다(이번 수정의 존재 이유)")
                .extracting(BbHint::label).containsExactly("person", "car");

        // when(SAM2) — 재시도의 힌트로 분할을 수행한다.
        int savedPolygons = sam2Step.run(RAW_SN, retryHints);

        // then(SAM2) — 폴리곤 전용 라벨(person)의 폴리곤이 <b>복구</b>된다.
        assertThat(savedPolygons).isEqualTo(2);
        assertThat(countPersisted(LsDataLbl.TYPE_POLYGON, "person"))
                .as("hints 가 유일한 전달 수단인 폴리곤 전용 라벨이 복구돼야 한다")
                .isEqualTo(1);
        assertThat(countPersisted(LsDataLbl.TYPE_POLYGON, "car")).isEqualTo(1);
        // 기존 BBOX 는 삭제·중복 없이 그대로다(사람이 수정했을 수 있다).
        assertThat(countPersisted(LsDataLbl.TYPE_BBOX, "car")).isEqualTo(1);
        verify(lblRepository, never()).deleteAllByIdInBatch(any());
        // V6 — 멱등 skip 은 <b>아무것도 지우지 않는다</b>. 생산이력이 같은 행이 되어 구 검증 축
        //   (AI 메타 삭제 미호출)이 사라졌으므로 라벨 삭제 미호출로 옮긴다.
        verify(lblRepository, never()).deleteAllByIdInBatch(any());
    }

    /**
     * 대칭 가드 — SAM2 까지 끝난 뒤의 재시도는 <b>양쪽 모두</b> 적재 0건이고 SAM2 외부 호출도 0건이다
     * (SAM2 는 하위 단계에 입력을 주지 않으므로 완전 skip 이 안전하다).
     */
    @Test
    @DisplayName("SAM2_까지_끝난_뒤의_재시도는_양쪽_모두_중복_적재하지_않는다")
    void fullyProcessedRetryPersistsNothing() {
        when(lblRepository.findDistinctSrcSnsByRawSnAndLblSrcCd(RAW_SN, LsDataLbl.SRC_YOLO))
                .thenReturn(List.of(SRC_SN));
        when(lblRepository.findDistinctSrcSnsByRawSnAndLblSrcCd(RAW_SN, LsDataLbl.SRC_SAM2))
                .thenReturn(List.of(SRC_SN));

        List<BbHint> hints = yoloStep.run(RAW_SN);
        int savedPolygons = sam2Step.run(RAW_SN, hints);

        assertThat(savedPolygons).isZero();
        assertThat(persisted).isEmpty();
        verify(lblRepository, never()).saveAll(any());
        verify(aiServerClient, never()).segment(any(Sam2Request.class), any(AiWorkload.class));
        // YOLO 추론은 도는 것이 정상이다 — 그 대가로 조용한 손실을 없앴다(인지·수용).
        verify(aiServerClient, times(1)).predictYoloTrack(any(YoloTrackRequest.class), eq(AiWorkload.BATCH));
        verify(srcRepository, never()).bumpLabelVersionIn(any());
    }
}
