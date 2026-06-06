package kr.co.cudo.authoring.dev.service;

import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.test.AutolabelTestService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.dev.dto.AutolabelTestRequest;
import kr.co.cudo.authoring.dev.dto.AutolabelTestResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.MngResourceCctvRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * DevAutolabelTestService 단위 테스트.
 *
 * <p>실제 파일 IO 는 JUnit {@link TempDir} 로 격리. 파이프라인 trigger 는
 * {@link AutolabelTestService} mock 으로 대체 (백그라운드 호출이 mock 에 도달하는지는 race 라
 * 검증하지 않고, 동기 검증은 service.upload 의 핵심 로직(검증/저장/DB)만 다룸).
 */
class DevAutolabelTestServiceTest {

    private VideoRepository videoRepository;
    private MngResourceCctvRepository cctvRepository;
    private AutolabelTestService autolabelTestService;
    private LsDataSrcRepository srcRepository;

    private DevAutolabelTestService service;

    @TempDir
    Path storageRoot;

    private static final long MAX_FILE_SIZE = 524_288_000L; // 500MB

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        cctvRepository = mock(MngResourceCctvRepository.class);
        autolabelTestService = mock(AutolabelTestService.class);
        srcRepository = mock(LsDataSrcRepository.class);
        // 기본: 프레임이 이미 존재(>0)한다고 가정 — 기존 테스트의 runFull 트리거 동작 보존.
        // 신규 업로드(프레임 0건) 케이스는 개별 테스트에서 override.
        org.mockito.Mockito.lenient().when(srcRepository.countByRawSn(any())).thenReturn(3L);
        service = new DevAutolabelTestService(
                videoRepository,
                cctvRepository,
                autolabelTestService,
                srcRepository,
                storageRoot.toString(),
                MAX_FILE_SIZE
        );
    }

    private AutolabelTestRequest validMeta() {
        return new AutolabelTestRequest(
                "TEST-CLIP-001",
                "CCTV-001",
                "EVT_FALL",
                "1168000000",
                AutolabelTestRequest.PrvcType.ANONY,
                Instant.parse("2024-05-01T12:00:00Z"),
                null
        );
    }

    private MultipartFile mp4File(String filename, byte[] content) {
        return new MockMultipartFile("file", filename, "video/mp4", content);
    }

    /** rawSn 값을 채워 save 가 정상 반환하도록 한다. */
    private LsDataRaw savedRaw(Long rawSn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "TEST-CLIP-001", "CCTV-001", "EVT_FALL", "1168000000",
                "ANONY", "autolabel-test/x.mp4", null, 60);
        setRawSn(raw, rawSn);
        return raw;
    }

    private static void setRawSn(LsDataRaw raw, Long value) {
        try {
            Field f = LsDataRaw.class.getDeclaredField("rawSn");
            f.setAccessible(true);
            f.set(raw, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("허용_확장자_mp4_업로드_200_rawSn반환")
    void mp4_업로드_정상() throws Exception {
        given(videoRepository.findByVmsClipId("TEST-CLIP-001")).willReturn(Optional.empty());
        given(cctvRepository.existsById("CCTV-001")).willReturn(true);
        given(videoRepository.save(any(LsDataRaw.class))).willReturn(savedRaw(42L));

        MultipartFile file = mp4File("sample.mp4", new byte[]{1, 2, 3, 4});

        AutolabelTestResponse response = service.upload(file, validMeta());

        assertThat(response.rawSn()).isEqualTo(42L);
        assertThat(response.savedFilePath()).startsWith("autolabel-test/").endsWith(".mp4");
        assertThat(response.pipelineStatus()).isEqualTo("PROCESSING");
        assertThat(response.startedAt()).isGreaterThan(0L);

        // 실제 파일이 storage 하위에 저장되었는지 확인 (절대경로 아님 — 응답에 노출 X)
        Path expected = storageRoot.resolve(response.savedFilePath());
        assertThat(Files.exists(expected)).isTrue();
        assertThat(Files.size(expected)).isEqualTo(4L);

        ArgumentCaptor<LsDataRaw> captor = ArgumentCaptor.forClass(LsDataRaw.class);
        verify(videoRepository).save(captor.capture());
        LsDataRaw captured = captor.getValue();
        assertThat(captured.getVmsClipId()).isEqualTo("TEST-CLIP-001");
        assertThat(captured.getVmsCctvId()).isEqualTo("CCTV-001");
        assertThat(captured.getPrvcTypeCd()).isEqualTo("ANONY");
        assertThat(captured.getDurationSec()).isEqualTo(60);
    }

    @Test
    @DisplayName("허용_안된_확장자_exe_업로드_400")
    void exe_업로드_거부() {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        given(cctvRepository.existsById(any())).willReturn(true);

        MultipartFile file = new MockMultipartFile("file", "evil.exe",
                "application/octet-stream", new byte[]{1, 2});

        assertThatThrownBy(() -> service.upload(file, validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(videoRepository, never()).save(any());
    }

    @Test
    @DisplayName("확장자_없는_파일_업로드_400")
    void 확장자_없는_파일_거부() {
        MultipartFile file = new MockMultipartFile("file", "videofile",
                "video/mp4", new byte[]{1, 2});

        assertThatThrownBy(() -> service.upload(file, validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("파일_크기_초과_413_PAYLOAD_TOO_LARGE")
    void 파일크기_초과() {
        // maxFileSize 를 4 bytes 로 매우 작게 설정
        DevAutolabelTestService smallLimitService = new DevAutolabelTestService(
                videoRepository, cctvRepository, autolabelTestService, srcRepository,
                storageRoot.toString(), 4L);

        MultipartFile file = mp4File("big.mp4", new byte[]{1, 2, 3, 4, 5});

        assertThatThrownBy(() -> smallLimitService.upload(file, validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
    }

    @Test
    @DisplayName("빈_파일_업로드_400")
    void 빈파일_거부() {
        MultipartFile file = new MockMultipartFile("file", "empty.mp4",
                "video/mp4", new byte[0]);

        assertThatThrownBy(() -> service.upload(file, validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("vmsClipId_중복_409_CONFLICT")
    void vmsClipId_중복() {
        given(videoRepository.findByVmsClipId("TEST-CLIP-001"))
                .willReturn(Optional.of(savedRaw(1L)));

        MultipartFile file = mp4File("sample.mp4", new byte[]{1, 2});

        assertThatThrownBy(() -> service.upload(file, validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);

        verify(videoRepository, never()).save(any());
    }

    @Test
    @DisplayName("cctvId_미등록_400_INVALID_INPUT")
    void cctvId_미등록() {
        given(videoRepository.findByVmsClipId("TEST-CLIP-001")).willReturn(Optional.empty());
        given(cctvRepository.existsById("CCTV-001")).willReturn(false);

        MultipartFile file = mp4File("sample.mp4", new byte[]{1, 2});

        assertThatThrownBy(() -> service.upload(file, validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(videoRepository, never()).save(any());
    }

    @Test
    @DisplayName("path_traversal_파일명_원본은_무시_안전한_UUID_파일명_저장")
    void path_traversal_파일명_시도() throws Exception {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        given(cctvRepository.existsById(any())).willReturn(true);
        given(videoRepository.save(any(LsDataRaw.class))).willReturn(savedRaw(99L));

        // 사용자 입력 파일명에 path traversal 시도 — 서비스는 basename 만 사용해 확장자만 추출
        MultipartFile file = new MockMultipartFile("file",
                "../../etc/passwd.mp4", "video/mp4", new byte[]{1, 2});

        AutolabelTestResponse response = service.upload(file, validMeta());

        // 응답은 storage 기준 상대 경로만 — 절대경로 X, 사용자 입력 파일명 미포함
        assertThat(response.savedFilePath()).startsWith("autolabel-test/").endsWith(".mp4");
        assertThat(response.savedFilePath()).doesNotContain("..").doesNotContain("passwd");

        // 저장된 실제 경로가 storage 하위인지 검증
        Path saved = storageRoot.resolve(response.savedFilePath()).normalize();
        assertThat(saved.startsWith(storageRoot.toAbsolutePath().normalize())).isTrue();
        assertThat(Files.exists(saved)).isTrue();
    }

    @Test
    @DisplayName("octet_stream_MIME_도_허용")
    void octet_stream_허용() throws Exception {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        given(cctvRepository.existsById(any())).willReturn(true);
        given(videoRepository.save(any(LsDataRaw.class))).willReturn(savedRaw(10L));

        MultipartFile file = new MockMultipartFile("file", "video.mp4",
                "application/octet-stream", new byte[]{1, 2, 3});

        AutolabelTestResponse response = service.upload(file, validMeta());
        assertThat(response.rawSn()).isEqualTo(10L);
    }

    @Test
    @DisplayName("이미지_MIME_거부_text_plain")
    void 잘못된_MIME_거부() {
        MultipartFile file = new MockMultipartFile("file", "sample.mp4",
                "text/plain", new byte[]{1, 2});

        assertThatThrownBy(() -> service.upload(file, validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    /** durationProbe 주입형 서비스 — ffprobe 의존 격리. */
    private DevAutolabelTestService serviceWithProbe(DevAutolabelTestService.DurationProbe probe) {
        return new DevAutolabelTestService(
                videoRepository, cctvRepository, autolabelTestService, srcRepository,
                storageRoot.toString(), MAX_FILE_SIZE, "ffprobe", probe);
    }

    @Test
    @DisplayName("영상_파일에서_duration_자동_추출_LS_DATA_RAW에_저장")
    void 영상_파일에서_duration_자동_추출() throws Exception {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        given(cctvRepository.existsById(any())).willReturn(true);
        given(videoRepository.save(any(LsDataRaw.class))).willReturn(savedRaw(101L));

        // ffprobe stub — 정확히 137초 반환
        DevAutolabelTestService localService = serviceWithProbe(path -> 137);

        MultipartFile file = mp4File("video.mp4", new byte[]{1, 2, 3, 4});
        AutolabelTestResponse response = localService.upload(file, validMeta());

        assertThat(response.rawSn()).isEqualTo(101L);

        // LS_DATA_RAW INSERT 시 ffprobe 가 반환한 137 이 그대로 사용되었는지 검증
        ArgumentCaptor<LsDataRaw> captor = ArgumentCaptor.forClass(LsDataRaw.class);
        verify(videoRepository).save(captor.capture());
        assertThat(captor.getValue().getDurationSec()).isEqualTo(137);
    }

    @Test
    @DisplayName("ffprobe_실패시_400_INVALID_INPUT_저장_안됨")
    void ffprobe_실패시_400() {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        given(cctvRepository.existsById(any())).willReturn(true);

        // ffprobe 가 RuntimeException 던지는 stub — 손상된 영상 시뮬레이션
        DevAutolabelTestService localService = serviceWithProbe(path -> {
            throw new IllegalStateException("ffprobe call failed: corrupted file");
        });

        MultipartFile file = mp4File("corrupt.mp4", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> localService.upload(file, validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        // LS_DATA_RAW 저장이 일어나지 않음 (ffprobe 실패 → 400)
        verify(videoRepository, never()).save(any());
    }

    @Test
    @DisplayName("ffprobe_0초_반환시_400_INVALID_INPUT")
    void ffprobe_0초_반환_거부() {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        given(cctvRepository.existsById(any())).willReturn(true);

        DevAutolabelTestService localService = serviceWithProbe(path -> 0);
        MultipartFile file = mp4File("zero.mp4", new byte[]{1, 2});

        assertThatThrownBy(() -> localService.upload(file, validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(videoRepository, never()).save(any());
    }

    @Test
    @DisplayName("ffprobe_상한_초과시_400_INVALID_INPUT")
    void ffprobe_상한_초과_거부() {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        given(cctvRepository.existsById(any())).willReturn(true);

        DevAutolabelTestService localService = serviceWithProbe(path -> 7201);
        MultipartFile file = mp4File("toolong.mp4", new byte[]{1, 2});

        assertThatThrownBy(() -> localService.upload(file, validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(videoRepository, never()).save(any());
    }

    @Test
    @DisplayName("enabledStages_meta가_있으면_runFull에_그대로_전달")
    void enabledStages_runFull_전달() throws Exception {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        given(cctvRepository.existsById(any())).willReturn(true);
        given(videoRepository.save(any(LsDataRaw.class))).willReturn(savedRaw(303L));

        java.util.Map<String, Boolean> toggles = new java.util.HashMap<>();
        toggles.put("FRAME_EXTRACT", true);
        toggles.put("DEIDENTIFY", false);
        toggles.put("YOLO", true);
        toggles.put("SAM2", false);

        AutolabelTestRequest meta = new AutolabelTestRequest(
                "TEST-CLIP-002", "CCTV-001", "EVT_FALL",
                "1168000000", AutolabelTestRequest.PrvcType.ANONY,
                Instant.parse("2024-05-01T12:00:00Z"),
                toggles
        );

        DevAutolabelTestService localService = serviceWithProbe(path -> 60);
        MultipartFile file = mp4File("ok.mp4", new byte[]{1, 2, 3});
        localService.upload(file, meta);

        // 비동기 호출이므로 awaitility 로 대기
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            ArgumentCaptor<java.util.Map<String, Boolean>> mapCaptor =
                    ArgumentCaptor.forClass(java.util.Map.class);
            verify(autolabelTestService).runFull(eq(303L), mapCaptor.capture());
            java.util.Map<String, Boolean> captured = mapCaptor.getValue();
            assertThat(captured.get("FRAME_EXTRACT")).isTrue();
            assertThat(captured.get("DEIDENTIFY")).isFalse();
            assertThat(captured.get("YOLO")).isTrue();
            assertThat(captured.get("SAM2")).isFalse();
        });
    }

    @Test
    @DisplayName("이슈B_신규영상_프레임0건_업로드시_runFull_미호출_FAILED_미마킹_REGISTERED반환")
    void 프레임_0건_업로드시_runFull_미호출() throws Exception {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        given(cctvRepository.existsById(any())).willReturn(true);
        given(videoRepository.save(any(LsDataRaw.class))).willReturn(savedRaw(500L));
        // 신규 업로드 영상 — 프레임 아직 0건 (마킹 전이므로 프레임 추출 안 됨)
        given(srcRepository.countByRawSn(500L)).willReturn(0L);

        DevAutolabelTestService localService = serviceWithProbe(path -> 60);
        MultipartFile file = mp4File("new.mp4", new byte[]{1, 2, 3});

        AutolabelTestResponse response = localService.upload(file, validMeta());

        // 영상 등록은 정상 수행, 파이프라인 상태는 REGISTERED
        assertThat(response.rawSn()).isEqualTo(500L);
        assertThat(response.pipelineStatus()).isEqualTo("REGISTERED");

        // 핵심: runFull 을 호출하지 않으며, FAILED 로 마킹하지도 않는다.
        verify(autolabelTestService, never()).runFull(any(), anyMap());
        verify(autolabelTestService, never()).runFull(any());
        // 잠시 대기해도 비동기 FAILED 마킹이 일어나지 않음을 확인.
        Thread.sleep(200);
        verify(videoRepository, never()).updateStatus(eq(500L), eq("FAILED"));
    }

    @Test
    @DisplayName("이슈B_프레임_존재시_재실행_runFull_호출_기존동작_보존")
    void 프레임_존재시_runFull_호출() throws Exception {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        given(cctvRepository.existsById(any())).willReturn(true);
        given(videoRepository.save(any(LsDataRaw.class))).willReturn(savedRaw(501L));
        // 재실행 케이스 — 프레임이 이미 존재
        given(srcRepository.countByRawSn(501L)).willReturn(12L);

        DevAutolabelTestService localService = serviceWithProbe(path -> 60);
        MultipartFile file = mp4File("rerun.mp4", new byte[]{1, 2, 3});

        AutolabelTestResponse response = localService.upload(file, validMeta());
        assertThat(response.pipelineStatus()).isEqualTo("PROCESSING");

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                verify(autolabelTestService).runFull(eq(501L), anyMap()));
    }

    @Test
    @DisplayName("파이프라인_비동기_실패시_LS_DATA_RAW_DATA_STTS_CD_FAILED_갱신")
    void 파이프라인_실패시_FAILED_갱신() throws Exception {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        given(cctvRepository.existsById(any())).willReturn(true);
        given(videoRepository.save(any(LsDataRaw.class))).willReturn(savedRaw(202L));

        // autolabelTestService.runFull 이 예외 던지도록 설정
        doThrow(new RuntimeException("YOLO step failed: server unreachable"))
                .when(autolabelTestService).runFull(eq(202L), anyMap());

        DevAutolabelTestService localService = serviceWithProbe(path -> 60);
        MultipartFile file = mp4File("ok.mp4", new byte[]{1, 2, 3});

        // 동기 응답은 200 으로 반환 — 파이프라인은 비동기
        AutolabelTestResponse response = localService.upload(file, validMeta());
        assertThat(response.rawSn()).isEqualTo(202L);

        // 비동기 exceptionally 콜백이 updateStatus(202, "FAILED") 를 호출하는지 검증.
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                verify(videoRepository).updateStatus(202L, "FAILED"));
    }
}
