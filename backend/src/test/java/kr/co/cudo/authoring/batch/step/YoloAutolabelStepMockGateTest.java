package kr.co.cudo.authoring.batch.step;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataLblAiInfo;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.AiMockMeta;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.client.dto.YoloTrackRequest;
import kr.co.cudo.authoring.common.config.DeployedEnvironmentDetector;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.label.service.FrameBoundsResolver;
import kr.co.cudo.authoring.label.service.LabelMasterService;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 배포 환경(stg/prd) YOLOX mock 응답 <b>실행시점 fail-closed</b> 판정 (G-ISSUE-02).
 *
 * <h3>왜 실행시점인가</h3>
 * <p>ai-server 는 별도 프로세스라 기동 순서가 보장되지 않는다. 기동시점(@PostConstruct)에 조회하면
 * "아직 안 뜬 정상 상황"과 "가중치가 없는 비정상 상황"을 구분하지 못해 배포마다 랜덤 기동 실패가 난다.
 * 스텝은 이미 프레임마다 {@code untrusted()}/{@code mockReason()} 를 받고 있으므로,
 * <b>네트워크 추가 호출 없이</b> 그 응답만으로 판정한다.
 *
 * <h3>차단 축이 기존 '검출 단위 드롭'과 다른 이유</h3>
 * <p>기존 정책(퇴화 박스·형식 위반)은 <b>검출 1건</b>의 이상이라 스킵하고 나머지를 저장한다. 반면
 * mock 응답은 <b>모델 자체가 없다</b>는 신호라 그 영상의 라벨 전체가 무의미하다. 정상 프레임과
 * mock 프레임이 한 영상에 섞이면 "라벨이 0건인 프레임"이 정상 결과와 구분되지 않으므로,
 * 첫 감지 즉시 예외로 스텝 전체를 FAILED 시킨다(all-or-nothing).
 */
class YoloAutolabelStepMockGateTest {

    private AiServerClient aiServerClient;
    private LsDataSrcRepository srcRepository;
    private LsDataLblRepository lblRepository;
    private LsDataLblAiInfoRepository aiInfoRepository;
    private VideoRepository videoRepository;
    private PresetLabelLookupService presetLabelLookup;
    private SystemConfigService systemConfigService;
    private LabelMasterService labelMasterService;
    private FrameBoundsResolver frameBoundsResolver;
    private Path rawDir;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger stepLogger;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        aiServerClient = mock(AiServerClient.class);
        srcRepository = mock(LsDataSrcRepository.class);
        lblRepository = mock(LsDataLblRepository.class);
        aiInfoRepository = mock(LsDataLblAiInfoRepository.class);
        videoRepository = mock(VideoRepository.class);
        presetLabelLookup = mock(PresetLabelLookupService.class);
        systemConfigService = mock(SystemConfigService.class);
        labelMasterService = mock(LabelMasterService.class);
        frameBoundsResolver = mock(FrameBoundsResolver.class);

        when(frameBoundsResolver.resolve(any())).thenReturn(Optional.of(new int[]{1280, 720}));
        when(systemConfigService.getInt(any())).thenReturn(null);
        when(labelMasterService.findLabelIdByDtctType(anyString())).thenReturn(Optional.empty());
        when(videoRepository.findById(anyLong())).thenReturn(Optional.empty());
        when(presetLabelLookup.togglesFor(any())).thenReturn(Optional.empty());
        when(lblRepository.saveAll(any())).thenAnswer(inv -> {
            List<LsDataLbl> out = new java.util.ArrayList<>();
            for (LsDataLbl l : (Iterable<LsDataLbl>) inv.getArgument(0)) {
                Field f = LsDataLbl.class.getDeclaredField("lblSn");
                f.setAccessible(true);
                f.set(l, 1L);
                out.add(l);
            }
            return out;
        });
        when(aiInfoRepository.saveAll(any())).thenAnswer(inv -> {
            List<LsDataLblAiInfo> out = new java.util.ArrayList<>();
            for (LsDataLblAiInfo a : (Iterable<LsDataLblAiInfo>) inv.getArgument(0)) {
                out.add(a);
            }
            return out;
        });

        rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);
        for (long sn : new long[]{10L, 11L, 12L}) {
            Files.write(rawDir.resolve(sn + ".jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});
        }

        stepLogger = (Logger) LoggerFactory.getLogger(YoloAutolabelStep.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        stepLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        if (stepLogger != null && logAppender != null) {
            stepLogger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────

    private YoloAutolabelStep stepFor(String... activeProfiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(activeProfiles);
        return new YoloAutolabelStep(aiServerClient, srcRepository, lblRepository, aiInfoRepository,
                videoRepository, presetLabelLookup, systemConfigService, labelMasterService,
                frameBoundsResolver, new ObjectMapper(), rawDir.toString(),
                new DeployedEnvironmentDetector(env));
    }

    private LsDataSrc newSrc(Long srcSn) {
        LsDataSrc src = LsDataSrc.create(1L, srcSn.intValue(), srcSn + ".jpg", null);
        try {
            Field f = LsDataSrc.class.getDeclaredField("srcSn");
            f.setAccessible(true);
            f.set(src, srcSn);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return src;
    }

    private static YoloResponse mockResponse(String reason) {
        return new YoloResponse(List.of(), true, "mock", reason);
    }

    private static YoloResponse realResponse() {
        return new YoloResponse(
                List.of(new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.92)),
                false, AiMockMeta.SOURCE_MODEL, null);
    }

    private void frames(Long rawSn, Long... srcSns) {
        List<LsDataSrc> list = new java.util.ArrayList<>();
        for (Long sn : srcSns) {
            list.add(newSrc(sn));
        }
        when(srcRepository.findByRawSnOrderByFrameNoAsc(rawSn)).thenReturn(list);
    }

    private long mockWarnCount() {
        return logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("mock response detected"))
                .count();
    }

    // ── 배포 환경(stg/prd) — 차단 ───────────────────────────────────────

    @Test
    @DisplayName("stg_프로파일에서_YOLOX_weights_missing_응답이면_스텝이_FAILED된다")
    void stgBlocksWeightsMissing() {
        // given
        frames(1L, 10L);
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class)))
                .thenReturn(Mono.just(mockResponse("weights_missing")));

        // when / then — 스텝 전체 실패(오케스트레이터가 FAILED 로 마킹)
        assertThatThrownBy(() -> stepFor("stg").run(1L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXTERNAL_API_ERROR)
                .hasMessageContaining("weights_missing");
        verify(lblRepository, never()).saveAll(any());
        verify(srcRepository, never()).bumpLabelVersionIn(any());
    }

    @Test
    @DisplayName("prd_프로파일에서_load_failed_응답도_동일하게_차단된다")
    void prdBlocksLoadFailed() {
        // given
        frames(2L, 10L);
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class)))
                .thenReturn(Mono.just(mockResponse("load_failed")));

        // when / then
        assertThatThrownBy(() -> stepFor("prd").run(2L))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("load_failed");
    }

    @Test
    @DisplayName("mock_메타_생략_응답도_배포환경에서는_fail_closed로_차단된다")
    void prdBlocksOmittedMockMeta() {
        // given — mock=false + source 미전송(AiMockMeta 규약상 신뢰 불가, mockReason=null)
        frames(3L, 10L);
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class)))
                .thenReturn(Mono.just(new YoloResponse(List.of(), false, null, null)));

        // when / then
        assertThatThrownBy(() -> stepFor("prd").run(3L))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("영상_중간에_mock으로_전환되면_이전_정상_프레임_포함해_스텝_전체가_FAILED된다")
    void midVideoSwitchAbortsWholeStep() {
        // given — 3프레임: 1번 정상 → 2번 mock(재기동) → 3번은 호출조차 되면 안 된다
        frames(4L, 10L, 11L, 12L);
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class)))
                .thenReturn(Mono.just(realResponse()))
                .thenReturn(Mono.just(mockResponse("weights_missing")))
                .thenReturn(Mono.just(realResponse()));

        // when / then
        assertThatThrownBy(() -> stepFor("prd").run(4L))
                .isInstanceOf(CustomException.class);
        // 첫 mock 즉시 중단 — 남은 프레임은 추론하지 않는다.
        verify(aiServerClient, times(2)).predictYoloTrack(any(YoloTrackRequest.class));
        // 라벨셋 버전 bump 는 정상 완주 시에만 — 부분 상태를 남기지 않는다.
        // (이미 저장된 1번 프레임 라벨은 run() 의 REQUIRES_NEW 트랜잭션 롤백으로 사라진다.)
        verify(srcRepository, never()).bumpLabelVersionIn(any());
    }

    @Test
    @DisplayName("env_mock_사유여도_배포환경에서는_차단된다")
    void envMockIsBlockedOnDeployedEnv() {
        // given — env_mock 은 <b>가장 위험한</b> 사유다. weights_missing/load_failed 는 빈 detections 를
        //   내지만 env_mock 은 합성 person 박스(중앙, score=0.9)를 만들어 <b>가짜 라벨이 저장</b>된다
        //   (ai-server/app/routers/yolo.py::_mock_track). 게다가 AI_MOCK_MODE 는 가중치 확인보다
        //   먼저 평가되어 실제로 가중치가 없어도 사유가 env_mock 으로 보고되므로, 면제를 두면
        //   "가중치 미배포"가 면제 사유로 위장된다.
        frames(5L, 10L);
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class)))
                .thenReturn(Mono.just(new YoloResponse(
                        List.of(new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.9)),
                        true, "mock", "env_mock")));

        // when / then — 사유와 무관하게 차단(합성 라벨은 한 건도 저장되지 않는다)
        assertThatThrownBy(() -> stepFor("stg").run(5L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXTERNAL_API_ERROR)
                .hasMessageContaining("env_mock");
        verify(lblRepository, never()).saveAll(any());
        verify(srcRepository, never()).bumpLabelVersionIn(any());
    }

    @Test
    @DisplayName("local_dev_프로파일에서는_env_mock이_여전히_허용된다")
    void envMockStillAllowedOnDevProfile() {
        // given — 모델 없이 배치를 돌려보는 정상 개발 동선은 유지한다
        frames(9L, 10L);
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class)))
                .thenReturn(Mono.just(new YoloResponse(
                        List.of(new YoloResponse.Detection("person", List.of(1.0, 2.0, 3.0, 4.0), 0.9)),
                        true, "mock", "env_mock")));

        // when / then — WARN 만 남기고 진행
        assertThatCode(() -> stepFor("dev").run(9L)).doesNotThrowAnyException();
        assertThatCode(() -> stepFor("local").run(9L)).doesNotThrowAnyException();
        assertThat(mockWarnCount()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("배포표식_ENV가_prd면_dev_프로파일이어도_차단된다")
    void deployedEnvMarkerWins() {
        // given
        frames(6L, 10L);
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class)))
                .thenReturn(Mono.just(mockResponse("weights_missing")));
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        env.setProperty("ENV", "prd");
        YoloAutolabelStep step = new YoloAutolabelStep(aiServerClient, srcRepository, lblRepository,
                aiInfoRepository, videoRepository, presetLabelLookup, systemConfigService,
                labelMasterService, frameBoundsResolver, new ObjectMapper(), rawDir.toString(),
                new DeployedEnvironmentDetector(env));

        // when / then
        assertThatThrownBy(() -> step.run(6L)).isInstanceOf(CustomException.class);
    }

    // ── local/dev — 기존 동작 보존 ──────────────────────────────────────

    @Test
    @DisplayName("local_dev_프로파일에서는_weights_missing이어도_기존과_동일하게_WARN만_남기고_진행한다")
    void devKeepsWarnOnlyBehaviour() {
        // given
        frames(7L, 10L);
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class)))
                .thenReturn(Mono.just(mockResponse("weights_missing")));

        // when / then
        assertThatCode(() -> stepFor("dev").run(7L)).doesNotThrowAnyException();
        assertThat(mockWarnCount()).isGreaterThanOrEqualTo(1L);

        assertThatCode(() -> stepFor("local").run(7L)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("정상_모델_응답은_배포환경에서도_그대로_저장된다")
    void realResponseNotBlockedOnDeployedEnv() {
        // given
        frames(8L, 10L);
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class)))
                .thenReturn(Mono.just(realResponse()));

        // when
        List<BbHint> hints = stepFor("prd").run(8L);

        // then
        assertThat(hints).hasSize(1);
        assertThat(mockWarnCount()).isZero();
    }

    // ── 빈/형식위반 응답도 같은 게이트를 지난다 ─────────────────────────────

    @Test
    @DisplayName("배포환경에서_null_응답을_받으면_스텝이_FAILED된다")
    void deployedEnvAbortsOnNullResponse() {
        // given — 빈 200 바디·무본문 프록시 응답 등으로 body 가 통째로 비는 경우
        frames(20L, 10L, 11L);
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class)))
                .thenReturn(Mono.empty());

        // when / then — 구 구현은 게이트에 닿기 전에 continue 로 빠져나가 "라벨 0건 성공" 이 됐다.
        assertThatThrownBy(() -> stepFor("prd").run(20L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXTERNAL_API_ERROR);
        // 첫 프레임에서 중단 — 남은 프레임은 추론하지 않는다.
        verify(aiServerClient, times(1)).predictYoloTrack(any(YoloTrackRequest.class));
        verify(srcRepository, never()).bumpLabelVersionIn(any());
    }

    @Test
    @DisplayName("배포환경에서_detections가_null인_응답도_스텝이_FAILED된다")
    void deployedEnvAbortsOnNullDetections() {
        // given — source="model" 이라 untrusted() 는 false 인데 본문이 결측인 형상
        frames(21L, 10L);
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class)))
                .thenReturn(Mono.just(new YoloResponse(null, false, AiMockMeta.SOURCE_MODEL, null)));

        // when / then
        assertThatThrownBy(() -> stepFor("prd").run(21L))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("local_dev에서는_null_응답을_받아도_기존과_동일하게_스킵한다")
    void devSkipsNullResponse() {
        // given
        frames(22L, 10L, 11L);
        when(aiServerClient.predictYoloTrack(any(YoloTrackRequest.class)))
                .thenReturn(Mono.empty());

        // when / then — 프레임을 건너뛰고 완주(기존 동작 보존)
        assertThatCode(() -> stepFor("dev").run(22L)).doesNotThrowAnyException();
        verify(aiServerClient, times(2)).predictYoloTrack(any(YoloTrackRequest.class));
        verify(lblRepository, never()).saveAll(any());
    }

    // ── 계약 드리프트 가드 ───────────────────────────────────────────────

    @Test
    @DisplayName("env_mock_사유값이_ai_server_와_일치한다")
    void envMockReasonMatchesAiServer() throws IOException {
        // env_mock 은 더 이상 <b>면제</b> 사유가 아니지만(위 envMockIsBlockedOnDeployedEnv),
        // 사유 문자열 자체는 ai-server 와의 진단 계약으로 남는다 — 값이 어긋나면 로그·운영
        // 안내가 실제 형상과 다른 사유를 가리킨다.
        Path yoloRouter = Path.of("..", "ai-server", "app", "routers", "yolo.py");
        if (!Files.exists(yoloRouter)) {
            // 분리 CI(ai-server 미체크아웃) — 상수 자체만 고정한다.
            assertThat(AiMockMeta.REASON_ENV_MOCK).isEqualTo("env_mock");
            return;
        }
        assertThat(Files.readString(yoloRouter))
                .as("ai-server 가 사유값을 바꾸면 운영 진단이 어긋난다")
                .contains("\"" + AiMockMeta.REASON_ENV_MOCK + "\"");
    }
}
