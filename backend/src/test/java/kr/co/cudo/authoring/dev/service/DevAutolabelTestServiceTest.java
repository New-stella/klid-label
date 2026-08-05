package kr.co.cudo.authoring.dev.service;

import kr.co.cudo.authoring.batch.runner.DevPipelineRunner;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.dev.dto.AutolabelTestRequest;
import kr.co.cudo.authoring.dev.dto.AutolabelTestResponse;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * DevAutolabelTestService 단위 테스트 (Phase 3 — 단일 파이프라인 수렴 반영).
 *
 * <p>실제 파일 IO 는 JUnit {@link TempDir} 로 격리. 파이프라인 trigger 는
 * {@link DevPipelineRunner} mock 으로 대체한다. upload 는 단계 토글/마킹 분기 없이
 * 항상 단일 {@code DevPipelineRunner.runAsync(rawSn)} 로 위임하며 응답은 PROCESSING 이다.
 */
class DevAutolabelTestServiceTest {

    private VideoRepository videoRepository;
    private DevPipelineRunner devPipelineRunner;
    private EventTypeService eventTypeService;

    private DevAutolabelTestService service;

    @TempDir
    Path storageRoot;

    private static final long MAX_FILE_SIZE = 524_288_000L; // 500MB
    /** dev 업로드 도구가 받는 관제 상세 EV-코드 (쓰러짐 카테고리 020002 의 대표 코드). */
    private static final String VALID_EV_CODE = "EV02000201";

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        devPipelineRunner = mock(DevPipelineRunner.class);
        eventTypeService = mock(EventTypeService.class);
        // 기본: 유효 EV-코드는 관제 마스터에 등록되어 categoryKey 를 반환한다.
        given(eventTypeService.filterKeyOf(VALID_EV_CODE)).willReturn(Optional.of("020002"));
        service = new DevAutolabelTestService(
                videoRepository,
                devPipelineRunner,
                eventTypeService,
                storageRoot.toString(),
                MAX_FILE_SIZE
        );
    }

    private AutolabelTestRequest validMeta() {
        return new AutolabelTestRequest(
                "TEST-CLIP-001",
                "CCTV-001",
                VALID_EV_CODE,
                "1168000000",
                AutolabelTestRequest.PrvcType.ANONY,
                Instant.parse("2024-05-01T12:00:00Z")
        );
    }

    private MultipartFile mp4File(String filename, byte[] content) {
        return new MockMultipartFile("file", filename, "video/mp4", content);
    }

    /** rawSn 값을 채워 save 가 정상 반환하도록 한다. */
    private LsDataRaw savedRaw(Long rawSn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "TEST-CLIP-001", "CCTV-001", VALID_EV_CODE, "1168000000",
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
    @DisplayName("허용_확장자_mp4_업로드_200_rawSn반환_PROCESSING")
    void mp4_업로드_정상() throws Exception {
        given(videoRepository.findByVmsClipId("TEST-CLIP-001")).willReturn(Optional.empty());
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
                videoRepository, devPipelineRunner, eventTypeService,
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

    // ★폐기: 'cctvId_미등록_400_INVALID_INPUT' (V167)
    //   구 동작은 관제 공유 MNG_RESOURCE_CCTV 존재 여부로 400 을 냈으나 그 테이블이 제거되면서
    //   판정 근거가 사라졌다. 대체 원천(LS_DATA_INGEST.VMS_CCTV_ID)은 "이미 수신된 영상"의 기록이지
    //   CCTV 마스터가 아니라, 신규 dev 영상의 CCTV 를 판정하면 첫 영상이 항상 거부된다.
    //   cctvId 입력 검증은 요청 DTO 의 @Pattern(영문/숫자/-/_ 1~64자)이 담당한다(CWE-20).

    @Test
    @DisplayName("AutolabelTest_eventTypeCd_관제EV코드면_통과")
    void eventTypeCd_관제등록코드_통과() throws Exception {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        given(videoRepository.save(any(LsDataRaw.class))).willReturn(savedRaw(77L));
        // VALID_EV_CODE 는 setUp 에서 categoryKey 반환하도록 stub.

        MultipartFile file = mp4File("ok.mp4", new byte[]{1, 2, 3});
        AutolabelTestResponse response = service.upload(file, validMeta());

        assertThat(response.rawSn()).isEqualTo(77L);
        verify(eventTypeService).filterKeyOf(VALID_EV_CODE);
    }

    @Test
    @DisplayName("AutolabelTest_eventTypeCd_관제미등록코드면_400_저장안됨")
    void eventTypeCd_관제미등록코드_400() {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        // 미등록 EV-코드 — filterKeyOf 가 빈 Optional 반환.
        given(eventTypeService.filterKeyOf("EV09999999")).willReturn(Optional.empty());

        AutolabelTestRequest meta = new AutolabelTestRequest(
                "TEST-CLIP-001", "CCTV-001", "EV09999999", "1168000000",
                AutolabelTestRequest.PrvcType.ANONY, Instant.parse("2024-05-01T12:00:00Z"));
        MultipartFile file = mp4File("unknown.mp4", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.upload(file, meta))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(videoRepository, never()).save(any());
    }

    @Test
    @DisplayName("path_traversal_파일명_원본은_무시_안전한_UUID_파일명_저장")
    void path_traversal_파일명_시도() throws Exception {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
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
                videoRepository, devPipelineRunner, eventTypeService,
                storageRoot.toString(), MAX_FILE_SIZE, "ffprobe", probe);
    }

    @Test
    @DisplayName("영상_파일에서_duration_자동_추출_LS_DATA_RAW에_저장")
    void 영상_파일에서_duration_자동_추출() throws Exception {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
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

        DevAutolabelTestService localService = serviceWithProbe(path -> 7201);
        MultipartFile file = mp4File("toolong.mp4", new byte[]{1, 2});

        assertThatThrownBy(() -> localService.upload(file, validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(videoRepository, never()).save(any());
    }

    @Test
    @DisplayName("신규영상_업로드시_DevPipelineRunner_runAsync_단일_위임_PROCESSING_반환")
    void 신규영상_업로드시_runner_위임() throws Exception {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        given(videoRepository.save(any(LsDataRaw.class))).willReturn(savedRaw(500L));

        DevAutolabelTestService localService = serviceWithProbe(path -> 60);
        MultipartFile file = mp4File("new.mp4", new byte[]{1, 2, 3});

        AutolabelTestResponse response = localService.upload(file, validMeta());

        assertThat(response.rawSn()).isEqualTo(500L);
        assertThat(response.pipelineStatus()).isEqualTo("PROCESSING");

        // 단계 토글/마킹 분기 없이 단일 runAsync(rawSn) 으로 위임한다.
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                verify(devPipelineRunner).runAsync(eq(500L)));
    }
}
