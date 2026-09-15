package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.auth.JwtTestSupport;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.Sam2Response;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.label.service.AutolabelOnlineService;
import kr.co.cudo.authoring.label.service.Sam2SegmentService;
import kr.co.cudo.authoring.label.service.YoloTrackService;
import kr.co.cudo.authoring.portal.service.PortalStoragePathGuard;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 포털 AI 보조 창구를 <b>실 보안 체인 + 실 DB</b> 로 고정한다 — 인가(작업 대상) · 채널 격리 · 비식별 판정 미적용 ·
 * 입력 이미지 해석 · 부재 창구.
 *
 * <h3>★ 새 스프링 컨텍스트를 만들지 않는다</h3>
 * <p>구성은 {@code AiCancelApiTest}·{@code PortalSam2RemovedTest} 와 <b>같은 키</b>({@code @SpringBootTest} +
 * {@code @AutoConfigureMockMvc} + {@code @ActiveProfiles("local")}, 목 빈·동적 프로퍼티 없음)다
 * ({@code TestContextDiversityRatchetTest}). 그래서 ①추론 서버 대역은 {@code @MockBean} 이 아니라 추론 본체 세 서비스의
 * 클라이언트 필드를 시험 동안만 목으로 바꿔 끼우고 끝나면 되돌리며 ②저장소는 {@code @DynamicPropertySource} 로 옮기지
 * 않고 <b>기본 설정 경로 아래 고유 하위 디렉터리</b>에 픽스처를 만든 뒤 지운다(선례 {@code PortalFrameImageCacheControlTest}).
 *
 * <p>요청량 제한 초과(429)는 설정 한도(60/분)를 채우는 요청을 만들지 않고 {@code PortalAiAssistControllerRateLimitTest}
 * 가 좁은 한도로 고정한다.
 *
 * @design API-254, API-255, API-256, API-257, API-258, AC-1108
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional("controlTransactionManager")
class PortalAiAssistApiIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsRawDataStatusRepository statusRepository;
    @Autowired private PortalUploadAssetRepository assetRepository;
    @Autowired private LsLabelRepository labelRepository;
    @Autowired private PortalStoragePathGuard portalPathGuard;
    @Autowired private AutolabelOnlineService autolabelOnlineService;
    @Autowired private Sam2SegmentService sam2SegmentService;
    @Autowired private YoloTrackService yoloTrackService;

    @Value("${authoring.jwt.secret}") private String secret;
    @Value("${authoring.jwt.issuer}") private String issuer;
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}") private String storageDeidPath;

    /** 추론 서버 대역 — 본체 세 서비스의 필드에만 시험 동안 끼운다(컨텍스트 키 불변). */
    private AiServerClient aiServerClient;
    private final Map<Object, Object> swappedClients = new IdentityHashMap<>();
    /** 시험이 만든 파일 루트 — 끝나면 지운다. */
    private final List<Path> createdRoots = new java.util.ArrayList<>();

    private Path deidBase;
    private Path portalBase;

    private String owner;
    private String ownerToken;
    private String otherToken;

    @BeforeEach
    void setUp() {
        // 사용자마다 요청량 한도가 따로라 시험마다 새 subject 를 쓴다(한 컨텍스트에서 한도가 누적되지 않게).
        owner = "portal-ai-" + System.nanoTime();
        ownerToken = JwtTestSupport.token(secret, owner, "PORTAL_USER", "PORTAL", issuer, 60);
        otherToken = JwtTestSupport.token(secret, owner + "-other", "PORTAL_USER", "PORTAL", issuer, 60);
        if (labelRepository.findByDtctTypeCdAndUseYn("person", "Y").isEmpty()) {
            labelRepository.save(kr.co.cudo.authoring.label.entity.LsLabel.create(
                    "person", "#E74C3C", "BBOX", 0, "person", "test"));
        }

        deidBase = Paths.get(storageDeidPath).toAbsolutePath().normalize();
        portalBase = portalPathGuard.baseDir().resolve("portal-ai-it-" + System.nanoTime());
        createdRoots.add(portalBase);

        aiServerClient = mock(AiServerClient.class);
        for (Object bean : List.of(autolabelOnlineService, sam2SegmentService, yoloTrackService)) {
            Object target = AopTestUtils.getUltimateTargetObject(bean);
            swappedClients.put(target, ReflectionTestUtils.getField(target, "aiServerClient"));
            ReflectionTestUtils.setField(target, "aiServerClient", aiServerClient);
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        // 공유 컨텍스트의 싱글턴이다 — 되돌리지 않으면 뒤따르는 시험이 목을 물려받는다.
        swappedClients.forEach((target, original) -> ReflectionTestUtils.setField(target, "aiServerClient", original));
        swappedClients.clear();
        for (Path root : createdRoots) {
            if (Files.exists(root)) {
                try (Stream<Path> walk = Files.walk(root)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        }
        createdRoots.clear();
    }

    // ============================================================ 픽스처

    private static Path png(Path file, int w, int h) throws IOException {
        Files.createDirectories(file.getParent());
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        return file;
    }

    /** 데이터마트 영상(승인 여부 선택) + 비식별 프레임 {@code count} 장. {rawSn, srcSn...} */
    private long[] datamartFrames(boolean approved, int count) throws IOException {
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                "CLIP-PAI-" + System.nanoTime(), "cctv", "FALL", "lgv",
                LsDataRaw.PRVC_TYPE_ANONY, "/raw/v.mp4", LocalDateTime.now(), 30));
        long rawSn = raw.getRawSn();
        if (approved) {
            LsRawDataStatus status = LsRawDataStatus.initial(rawSn);
            status.transitionTo(LsRawDataStatus.STTS_APPROVED);
            statusRepository.save(status);
        }
        String dir = StorageSubtreePolicy.deidFramesDir(rawSn);
        // 이전 실행이 남긴 같은 번호 디렉터리(또는 실제 저장소)가 이미 있으면 정리 대상에서 뺀다 —
        // 재귀 삭제가 이 시험이 만들지 않은 프레임까지 지우지 않게 한다. 대가로 그 경우엔 픽스처가 남는다.
        Path deidDir = deidBase.resolve(dir);
        if (!Files.exists(deidDir)) {
            createdRoots.add(deidDir);
        }
        long[] out = new long[count + 1];
        out[0] = rawSn;
        for (int n = 0; n < count; n++) {
            String rel = dir + "/" + n + ".png";
            png(deidBase.resolve(rel), 320, 240);
            LsDataSrc frame = LsDataSrc.create(rawSn, (long) n, "/raw/frames/" + n + ".png", LocalDateTime.now());
            frame.attachDeidPath(rel);
            out[n + 1] = srcRepository.save(frame).getSrcSn();
        }
        return out;
    }

    private long[] datamartFrame(boolean approved) throws IOException {
        return datamartFrames(approved, 1);
    }

    /** 본인 업로드 자산 + 프레임 1장. {rawSn, srcSn} */
    private long[] uploadFrame(String portalUserNo) throws IOException {
        long rawSn = assetRepository.insertUploaded(portalUserNo, portalBase.resolve("v.mp4").toString(),
                "v.mp4", "video/mp4", 100L);
        Path img = png(portalBase.resolve("frames/" + rawSn + "/0.png"), 200, 100);
        return new long[]{rawSn, srcRepository.save(
                LsDataSrc.create(rawSn, 0L, img.toAbsolutePath().toString(), LocalDateTime.now())).getSrcSn()};
    }

    private void stubDetection() {
        when(aiServerClient.predictYoloTrack(any())).thenReturn(Mono.just(new YoloResponse(List.of(
                new YoloResponse.Detection("person", List.of(10.0, 10.0, 40.0, 60.0), 0.9, 1)))));
    }

    private void stubSegment() {
        when(aiServerClient.segment(any())).thenReturn(Mono.just(new Sam2Response(
                List.of(List.of(10.0, 10.0), List.of(50.0, 10.0), List.of(30.0, 60.0)), 0.9)));
    }

    // ============================================================ AI 탐지 인가

    @Test
    @DisplayName("★본인_업로드_프레임_AI_탐지_200_검출좌표_반환")
    void uploadFrameAutolabelOk() throws Exception {
        long[] f = uploadFrame(owner);
        stubDetection();

        mockMvc.perform(post("/v1/portal/frames/" + f[1] + "/autolabel")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detectedCount").value(1));
    }

    @Test
    @DisplayName("★데이터마트_노출_프레임_AI_탐지_200")
    void datamartFrameAutolabelOk() throws Exception {
        long[] f = datamartFrame(true);
        stubDetection();

        mockMvc.perform(post("/v1/portal/frames/" + f[1] + "/autolabel")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detectedCount").value(1));
    }

    @Test
    @DisplayName("★남의_업로드_프레임_AI_탐지_403_추론_호출_0")
    void otherUsersUploadForbidden() throws Exception {
        long[] f = uploadFrame(owner);

        mockMvc.perform(post("/v1/portal/frames/" + f[1] + "/autolabel")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isForbidden());
        verify(aiServerClient, never()).predictYoloTrack(any());
    }

    @Test
    @DisplayName("★데이터마트_미노출_영상_프레임_AI_탐지_403")
    void unexposedVideoForbidden() throws Exception {
        long[] f = datamartFrame(false);

        mockMvc.perform(post("/v1/portal/frames/" + f[1] + "/autolabel")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("프레임_부재_AI_탐지_404")
    void absentFrameNotFound() throws Exception {
        mockMvc.perform(post("/v1/portal/frames/987654321/autolabel")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("★비식별_신고_상태여도_포털_AI_탐지는_412로_막지_않는다")
    void deidentReportDoesNotBlockPortalAi() throws Exception {
        long[] f = datamartFrame(true);
        LsDataRaw raw = videoRepository.findById(f[0]).orElseThrow();
        raw.markDeidentified("F");
        videoRepository.saveAndFlush(raw);
        stubDetection();

        mockMvc.perform(post("/v1/portal/frames/" + f[1] + "/autolabel")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
        // 대조 — 같은 프레임의 포털 이미지 서빙은 신고 게이트로 412 다(서빙 동작은 이 변경에서 바뀌지 않는다).
        mockMvc.perform(get("/v1/portal/frames/" + f[1] + "/image")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isPreconditionFailed());
    }

    // ============================================================ 채널 격리

    @Test
    @DisplayName("★INTERNAL_토큰으로_포털_AI_창구_403")
    void internalTokenForbiddenOnPortalWindows() throws Exception {
        String internal = JwtTestSupport.token(secret, "1", "REVIEWER", "INTERNAL", issuer, 60);
        mockMvc.perform(post("/v1/portal/frames/1/autolabel").header("Authorization", "Bearer " + internal))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/portal/ai-defaults").header("Authorization", "Bearer " + internal))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/portal/ai-requests/x/cancel").header("Authorization", "Bearer " + internal))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("★PORTAL_토큰으로_내부_AI_창구는_여전히_403")
    void portalTokenStillForbiddenOnInternalWindows() throws Exception {
        for (String suffix : List.of("autolabel", "sam2-segment", "sam2-track", "yolo-track")) {
            mockMvc.perform(post("/v1/frames/1/" + suffix).header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"srcSn\":1,\"nextSrcSns\":[]}"))
                    .andExpect(status().isForbidden());
        }
        mockMvc.perform(get("/v1/ai-defaults").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());
    }

    // ============================================================ 자동 추적 · 분할

    @Test
    @DisplayName("★자동_추적_후속_프레임이_다른_영상이면_400_추론_호출_0")
    void crossVideoTrack400() throws Exception {
        long[] a = uploadFrame(owner);
        long[] b = uploadFrame(owner);

        mockMvc.perform(post("/v1/portal/frames/" + a[1] + "/yolo-track")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"srcSn\":" + a[1] + ",\"nextSrcSns\":[" + b[1] + "]}"))
                .andExpect(status().isBadRequest());
        verify(aiServerClient, never()).predictYoloTrack(any());
    }

    @Test
    @DisplayName("자동_추적_같은_영상_본인_업로드_200")
    void sameVideoTrackOk() throws Exception {
        long[] a = uploadFrame(owner);
        long second = srcRepository.save(LsDataSrc.create(a[0], 1L,
                png(portalBase.resolve("frames/" + a[0] + "/1.png"), 200, 100).toAbsolutePath().toString(),
                LocalDateTime.now())).getSrcSn();
        stubDetection();

        mockMvc.perform(post("/v1/portal/frames/" + a[1] + "/yolo-track")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"srcSn\":" + a[1] + ",\"nextSrcSns\":[" + second + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.frames.length()").value(2));
    }

    @Test
    @DisplayName("AI_분할_본인_업로드_200_남의_자산_403")
    void segmentOwnerOkOtherForbidden() throws Exception {
        long[] f = uploadFrame(owner);
        stubSegment();
        String body = "{\"srcSn\":" + f[1] + ",\"points\":[[20,20]]}";

        mockMvc.perform(post("/v1/portal/frames/" + f[1] + "/sam2-segment")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.polygon.length()").value(3));
        mockMvc.perform(post("/v1/portal/frames/" + f[1] + "/sam2-segment")
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("★데이터마트_노출_프레임_AI_분할_200")
    void datamartFrameSegmentOk() throws Exception {
        long[] f = datamartFrame(true);
        stubSegment();

        mockMvc.perform(post("/v1/portal/frames/" + f[1] + "/sam2-segment")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"srcSn\":" + f[1] + ",\"points\":[[20,20]]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.polygon.length()").value(3));
    }

    @Test
    @DisplayName("★데이터마트_노출_영상_AI_자동_추적_200")
    void datamartVideoTrackOk() throws Exception {
        long[] f = datamartFrames(true, 2);
        stubDetection();

        mockMvc.perform(post("/v1/portal/frames/" + f[1] + "/yolo-track")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"srcSn\":" + f[1] + ",\"nextSrcSns\":[" + f[2] + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.frames.length()").value(2));
    }

    // ============================================================ 조회 · 취소 · 부재 창구

    @Test
    @DisplayName("포털_ai_defaults_200_PORTAL")
    void aiDefaultsOk() throws Exception {
        mockMvc.perform(get("/v1/portal/ai-defaults").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("포털_AI_취소_진행_중이_아닌_식별자는_200_cancelled_false")
    void cancelUnknownIsFalse() throws Exception {
        mockMvc.perform(post("/v1/portal/ai-requests/not-running/cancel")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cancelled").value(false));
    }

    @Test
    @DisplayName("★포털_선택_객체_추적_sam2_track_창구는_없다_404")
    void portalSam2TrackAbsent() throws Exception {
        mockMvc.perform(post("/v1/portal/frames/9999/sam2-track")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"srcSn\":1}"))
                .andExpect(status().is(not(equalTo(200))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("포털_스켈레톤_키포인트_전용_AI_창구는_없다_404")
    void portalKeypointWindowAbsent() throws Exception {
        for (String suffix : List.of("keypoint", "skeleton", "keypoint-detect")) {
            mockMvc.perform(post("/v1/portal/frames/9999/" + suffix)
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
        }
    }
}
