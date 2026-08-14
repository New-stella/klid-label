package kr.co.cudo.authoring.batch.step;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.AiMockMeta;
import kr.co.cudo.authoring.common.client.dto.Sam2Request;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.config.DeployedEnvironmentDetector;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.label.service.LabelMasterService;
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
 * 배치 SAM2 단계의 mock 응답 <b>fail-closed</b> 판정 (NEW-H1 — YOLO 게이트의 형제 결함).
 *
 * <h3>왜 SAM2 는 사유 면제가 없고 dev 에서도 저장하지 않는가</h3>
 * <p>두 추론기의 mock 형상이 <b>대칭이 아니다</b>:
 * <ul>
 *   <li>YOLO({@code ai-server/app/routers/yolo.py}) — {@code weights_missing}/{@code load_failed}
 *       는 <b>빈 detections</b> 를 내므로 저장될 가짜 라벨이 애초에 없다. 합성 박스를 만드는 것은
 *       {@code env_mock} 뿐이다.</li>
 *   <li>SAM2({@code ai-server/app/routers/sam2.py::_mock_segment}) — <b>사유와 무관하게 항상</b>
 *       합성 사각 폴리곤(score 0.95)을 만든다. 즉 SAM2 에는 "빈 응답이라 안전한 사유"가 없다.</li>
 * </ul>
 * <p>따라서 배포 환경은 사유 무관 차단(FAILED), local/dev 도 <b>저장은 하지 않고 스킵</b>한다
 * (WARN 만). dev 스킵이 YOLO 의 "빈 detections" 와 대칭을 이루는 지점이다 — dev 라고 가짜
 * 폴리곤을 LS_DATA_LBL 에 적재하면 개발 DB 가 학습데이터처럼 오염된다.
 *
 * <p>이 경로는 ai-server 기동 가드({@code app/startup_guard.py})로도 막히지 않는다.
 * {@code weights_missing}/{@code load_failed} 는 {@code AI_MOCK_MODE} 와 무관한 별도 사유라,
 * SAM2 모델만 부분 배포 안 된 폐쇄망 온프렘에서 YOLO 는 실모델로 통과하고 SAM2 단계에서만
 * 조용히 가짜 폴리곤이 저장된다.
 */
class Sam2SegmentStepMockGateTest {

    private AiServerClient aiServerClient;
    private LsDataSrcRepository srcRepository;
    private LsDataLblRepository lblRepository;
    private VideoRepository videoRepository;
    private PresetLabelLookupService presetLabelLookup;
    private LabelMasterService labelMasterService;
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
        videoRepository = mock(VideoRepository.class);
        presetLabelLookup = mock(PresetLabelLookupService.class);
        labelMasterService = mock(LabelMasterService.class);

        when(labelMasterService.findLabelIdByDtctType(anyString())).thenReturn(Optional.empty());
        when(videoRepository.findById(anyLong())).thenReturn(Optional.empty());
        when(presetLabelLookup.togglesFor(any())).thenReturn(Optional.empty());
        when(lblRepository.findBySrcSnAndAutoLblYn(anyLong(), anyString())).thenReturn(List.of());
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

        rawDir = tempDir.resolve("raw");
        Files.createDirectories(rawDir);
        for (long sn : new long[]{10L, 11L, 12L}) {
            Files.write(rawDir.resolve(sn + ".jpg"), new byte[]{(byte) 0xFF, (byte) 0xD8});
        }

        stepLogger = (Logger) LoggerFactory.getLogger(Sam2SegmentStep.class);
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

    private Sam2SegmentStep stepFor(String... activeProfiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(activeProfiles);
        return stepWith(new DeployedEnvironmentDetector(env));
    }

    private Sam2SegmentStep stepWith(DeployedEnvironmentDetector detector) {
        return new Sam2SegmentStep(aiServerClient, srcRepository, lblRepository,
                videoRepository, presetLabelLookup, labelMasterService,
                new ObjectMapper(), rawDir.toString(), detector);
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

    private void frames(Long rawSn, Long... srcSns) {
        List<LsDataSrc> list = new java.util.ArrayList<>();
        for (Long sn : srcSns) {
            list.add(newSrc(sn));
        }
        when(srcRepository.findByRawSnOrderByFrameNoAsc(rawSn)).thenReturn(list);
    }

    /** ai-server {@code _mock_segment} 형상 — 사유와 무관하게 <b>합성 사각 폴리곤</b>(score 0.95). */
    private static Sam2Response mockResponse(String reason) {
        return new Sam2Response(
                List.of(List.of(10.0, 10.0), List.of(20.0, 10.0), List.of(20.0, 20.0), List.of(10.0, 20.0)),
                0.95, true, "mock", reason);
    }

    private static Sam2Response realResponse() {
        return new Sam2Response(
                List.of(List.of(1.0, 2.0), List.of(3.0, 4.0), List.of(5.0, 6.0)),
                0.88, false, AiMockMeta.SOURCE_MODEL, null);
    }

    private List<BbHint> hint(Long srcSn) {
        return List.of(new BbHint(srcSn, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, null));
    }

    private long mockWarnCount() {
        return logAppender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("mock response"))
                .count();
    }

    // ── 배포 환경(stg/prd) — 사유 무관 차단 ─────────────────────────────

    @Test
    @DisplayName("배포환경에서_SAM2_mock_응답이면_스텝이_FAILED된다")
    void deployedEnvBlocksMockRegardlessOfReason() {
        // given / when / then — weights_missing / load_failed / env_mock 전부 동일하게 차단
        for (String reason : List.of("weights_missing", "load_failed", AiMockMeta.REASON_ENV_MOCK)) {
            frames(1L, 10L);
            when(aiServerClient.segment(any(Sam2Request.class)))
                    .thenReturn(Mono.just(mockResponse(reason)));

            assertThatThrownBy(() -> stepFor("stg").run(1L, hint(10L)))
                    .as("사유 %s 도 차단되어야 한다", reason)
                    .isInstanceOf(CustomException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXTERNAL_API_ERROR)
                    .hasMessageContaining(reason);
        }
        // 가짜 폴리곤은 한 건도 저장되지 않는다.
        verify(lblRepository, never()).saveAll(any());
        verify(srcRepository, never()).bumpLabelVersionIn(any());
    }

    @Test
    @DisplayName("배포표식_ENV가_prd면_dev_프로파일이어도_SAM2_mock이_차단된다")
    void deployedEnvMarkerWins() {
        // given
        frames(2L, 10L);
        when(aiServerClient.segment(any(Sam2Request.class)))
                .thenReturn(Mono.just(mockResponse("weights_missing")));
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("dev");
        env.setProperty("ENV", "prd");

        // when / then
        assertThatThrownBy(() -> stepWith(new DeployedEnvironmentDetector(env)).run(2L, hint(10L)))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("mock_메타_생략_응답도_배포환경에서는_fail_closed로_차단된다")
    void deployedEnvBlocksOmittedMockMeta() {
        // given — mock=false + source 미전송(AiMockMeta 규약상 신뢰 불가)
        frames(3L, 10L);
        when(aiServerClient.segment(any(Sam2Request.class)))
                .thenReturn(Mono.just(new Sam2Response(List.of(List.of(1.0, 2.0)), 0.9, false, null, null)));

        // when / then
        assertThatThrownBy(() -> stepFor("prd").run(3L, hint(10L)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("배포환경에서_SAM2_null_응답을_받으면_스텝이_FAILED된다")
    void deployedEnvAbortsOnNullResponse() {
        // given — 빈 200 바디·무본문 프록시 응답 등으로 body 가 통째로 비는 경우
        frames(4L, 10L, 11L);
        when(aiServerClient.segment(any(Sam2Request.class))).thenReturn(Mono.empty());

        // when / then — 첫 프레임에서 중단, 남은 프레임은 추론하지 않는다.
        assertThatThrownBy(() -> stepFor("prd").run(4L, List.of(
                new BbHint(10L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, null),
                new BbHint(11L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, null))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXTERNAL_API_ERROR);
        verify(aiServerClient, times(1)).segment(any(Sam2Request.class));
        verify(srcRepository, never()).bumpLabelVersionIn(any());
    }

    @Test
    @DisplayName("배포환경에서_polygon이_null인_응답도_스텝이_FAILED된다")
    void deployedEnvAbortsOnNullPolygon() {
        // given — source="model" 이라 untrusted() 는 false 인데 본문이 결측인 형상
        frames(5L, 10L);
        when(aiServerClient.segment(any(Sam2Request.class)))
                .thenReturn(Mono.just(new Sam2Response(null, 0.5, false, AiMockMeta.SOURCE_MODEL, null)));

        // when / then
        assertThatThrownBy(() -> stepFor("prd").run(5L, hint(10L)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.EXTERNAL_API_ERROR);
    }

    // ── local/dev — 저장은 하지 않되 완주 ───────────────────────────────

    @Test
    @DisplayName("local_dev_프로파일에서는_SAM2_mock_응답이어도_가짜_폴리곤을_저장하지_않고_스킵한다")
    void devSkipsMockPolygonWithoutSaving() {
        // given — ai-server 는 사유와 무관하게 합성 사각 폴리곤을 낸다(YOLO 의 빈 detections 와 다름).
        frames(6L, 10L);
        when(aiServerClient.segment(any(Sam2Request.class)))
                .thenReturn(Mono.just(mockResponse("weights_missing")));

        // when — 예외 없이 완주(개발 동선 보존)
        int savedDev = stepFor("dev").run(6L, hint(10L));
        int savedLocal = stepFor("local").run(6L, hint(10L));

        // then — 저장 0건 + WARN 만. 가짜 폴리곤이 LS_DATA_LBL 로 들어가지 않는다.
        assertThat(savedDev).isZero();
        assertThat(savedLocal).isZero();
        verify(lblRepository, never()).saveAll(any());
        verify(lblRepository, never()).saveAll(any());
        verify(srcRepository, never()).bumpLabelVersionIn(any());
        assertThat(mockWarnCount()).isGreaterThanOrEqualTo(2L);
    }

    @Test
    @DisplayName("local_dev_프로파일에서는_env_mock_사유여도_동일하게_저장하지_않는다")
    void devSkipsEnvMockPolygonToo() {
        // given — YOLO 는 dev 에서 env_mock 합성 박스를 허용하지만 SAM2 는 사유 면제가 없다.
        frames(7L, 10L);
        when(aiServerClient.segment(any(Sam2Request.class)))
                .thenReturn(Mono.just(mockResponse(AiMockMeta.REASON_ENV_MOCK)));

        // when
        int saved = stepFor("dev").run(7L, hint(10L));

        // then
        assertThat(saved).isZero();
        verify(lblRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("local_dev에서는_SAM2_null_응답을_받아도_기존과_동일하게_스킵한다")
    void devSkipsNullResponse() {
        // given
        frames(8L, 10L, 11L);
        when(aiServerClient.segment(any(Sam2Request.class))).thenReturn(Mono.empty());

        // when / then — 프레임을 건너뛰고 완주(기존 동작 보존)
        assertThatCode(() -> stepFor("dev").run(8L, List.of(
                new BbHint(10L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, null),
                new BbHint(11L, "person", List.of(1.0, 2.0, 3.0, 4.0), 0.92, null))))
                .doesNotThrowAnyException();
        verify(aiServerClient, times(2)).segment(any(Sam2Request.class));
        verify(lblRepository, never()).saveAll(any());
    }

    // ── 회귀 — 정상 실추론 응답 ─────────────────────────────────────────

    @Test
    @DisplayName("정상_SAM2_실추론_응답은_배포환경에서도_기존과_동일하게_저장된다")
    void realResponseStillPersistedOnDeployedEnv() {
        // given
        frames(9L, 10L);
        when(aiServerClient.segment(any(Sam2Request.class)))
                .thenReturn(Mono.just(realResponse()));

        // when
        int saved = stepFor("prd").run(9L, hint(10L));

        // then
        assertThat(saved).isEqualTo(1);
        verify(lblRepository, times(1)).saveAll(any());
        assertThat(mockWarnCount()).isZero();
    }

    // ── 계약 드리프트 가드 ───────────────────────────────────────────────

    @Test
    @DisplayName("SAM2_mock은_사유와_무관하게_합성_폴리곤을_만든다는_전제가_ai_server와_일치한다")
    void sam2MockAlwaysSynthesizesPolygon() throws IOException {
        // 이 스텝이 dev 에서도 저장을 막는 근거는 "SAM2 mock 은 빈 응답을 내지 않는다" 이다.
        // ai-server 가 이 형상을 바꾸면 근거 주석이 사실과 어긋나므로 계약으로 고정한다.
        Path sam2Router = Path.of("..", "ai-server", "app", "routers", "sam2.py");
        if (!Files.exists(sam2Router)) {
            // 분리 CI(ai-server 미체크아웃) — 사유 상수만 고정한다.
            assertThat(AiMockMeta.REASON_ENV_MOCK).isEqualTo("env_mock");
            return;
        }
        String source = Files.readString(sam2Router);
        assertThat(source)
                .as("_mock_segment 는 사유 인자를 받아 항상 폴리곤을 만든다")
                .contains("def _mock_segment(")
                .contains("polygon = [[x1, y1], [x2, y1], [x2, y2], [x1, y2]]");
    }
}
