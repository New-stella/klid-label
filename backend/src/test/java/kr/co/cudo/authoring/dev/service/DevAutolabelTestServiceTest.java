package kr.co.cudo.authoring.dev.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.runner.DevPipelineRunner;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.dev.dto.AutolabelTestRequest;
import kr.co.cudo.authoring.dev.dto.AutolabelTestResponse;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.LsDataIngestRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoMetaService;
import kr.co.cudo.authoring.video.service.port.VideoProbe;
import kr.co.cudo.authoring.video.service.port.VideoProbe.VideoMeta;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
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
    private VideoProbe videoProbe;
    private VideoMetaService videoMetaService;

    private DevAutolabelTestService service;

    @TempDir
    Path storageRoot;

    private static final long MAX_FILE_SIZE = 524_288_000L; // 500MB
    /** dev 업로드 도구가 받는 관제 상세 EV-코드 (쓰러짐 카테고리 020002 의 대표 코드). */
    private static final String VALID_EV_CODE = "EV02000201";

    /** 기본 probe 결과 — 60초 + 기술메타 전 필드 채움. */
    private static VideoMeta fullMeta() {
        return new VideoMeta(1920, 1080, "h264", 30.0, 4_500_000L, 60_000L, 12_345_678L);
    }

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        devPipelineRunner = mock(DevPipelineRunner.class);
        eventTypeService = mock(EventTypeService.class);
        videoProbe = mock(VideoProbe.class);
        videoMetaService = mock(VideoMetaService.class);
        // 기본: 유효 EV-코드는 관제 마스터에 등록되어 categoryKey 를 반환한다.
        given(eventTypeService.filterKeyOf(VALID_EV_CODE)).willReturn(Optional.of("020002"));
        given(videoProbe.probe(any(Path.class))).willReturn(fullMeta());
        service = newService(MAX_FILE_SIZE, true);
    }

    /** 기술메타 적재 토글·크기 한도만 바꿔 서비스를 새로 만든다(공용 mock 재사용). */
    private DevAutolabelTestService newService(long maxFileSize, boolean technicalMetaEnabled) {
        return new DevAutolabelTestService(
                videoRepository,
                devPipelineRunner,
                eventTypeService,
                videoProbe,
                videoMetaService,
                storageRoot.toString(),
                maxFileSize,
                technicalMetaEnabled
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
                "ANONY", "dev-upload/x.mp4", null, 60);
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
        assertThat(response.savedFilePath()).startsWith("dev-upload/").endsWith(".mp4");
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
    @DisplayName("저장_서브디렉터리가_dev_upload_이고_저장경로가_그_디렉터리_아래로_계산된다")
    void 저장_서브디렉터리_개명_가드() throws Exception {
        // given: 저장 서브디렉터리 개명(구 이름은 DevUploadPathRenameGuardTest 참조) 회귀 가드.
        //   응답의 상대 경로만 보면 "접두어가 맞다"까지만 알 수 있으므로, 실제 파일이 어느
        //   디렉터리에 떨어졌는지(=UPLOAD_SUBDIR 이 쓰기 경로 계산에 실제로 반영됐는지)까지 본다.
        given(videoRepository.findByVmsClipId("TEST-CLIP-001")).willReturn(Optional.empty());
        given(videoRepository.save(any(LsDataRaw.class))).willReturn(savedRaw(7L));

        // when
        AutolabelTestResponse response = service.upload(
                mp4File("sample.mp4", new byte[]{1, 2, 3, 4}), validMeta());

        // then: 응답 상대 경로의 첫 세그먼트 = 실제 저장 디렉터리 = dev-upload
        assertThat(response.savedFilePath()).startsWith("dev-upload/");
        Path saved = storageRoot.resolve(response.savedFilePath());
        assertThat(Files.exists(saved)).isTrue();
        assertThat(saved.getParent()).isEqualTo(storageRoot.resolve("dev-upload"));
        // ⚠ 기존 영상은 LS_DATA_RAW 에 절대 경로가 적재돼 있어 구 디렉터리에서 그대로 열린다 —
        //   파일을 옮기는 마이그레이션을 만들면 오히려 그 영상이 열리지 않는다.
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
        DevAutolabelTestService smallLimitService = newService(4L, true);

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
        assertThat(response.savedFilePath()).startsWith("dev-upload/").endsWith(".mp4");
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

    /** duration(초) 만 지정한 probe 결과 — 기술메타 축은 이 테스트들의 관심 밖이다. */
    private void givenProbeDurationMs(Long durationMs) {
        given(videoProbe.probe(any(Path.class)))
                .willReturn(new VideoMeta(1920, 1080, "h264", 30.0, 4_500_000L, durationMs, 1_000L));
    }

    @Test
    @DisplayName("영상_파일에서_duration_자동_추출_LS_DATA_RAW에_저장")
    void 영상_파일에서_duration_자동_추출() throws Exception {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        given(videoRepository.save(any(LsDataRaw.class))).willReturn(savedRaw(101L));

        // ffprobe stub — 정확히 137초(=137,000ms) 반환
        givenProbeDurationMs(137_000L);

        MultipartFile file = mp4File("video.mp4", new byte[]{1, 2, 3, 4});
        AutolabelTestResponse response = service.upload(file, validMeta());

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
        willThrow(new IllegalStateException("ffprobe call failed: corrupted file"))
                .given(videoProbe).probe(any(Path.class));

        MultipartFile file = mp4File("corrupt.mp4", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> service.upload(file, validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        // LS_DATA_RAW 저장이 일어나지 않음 (ffprobe 실패 → 400)
        verify(videoRepository, never()).save(any());
    }

    @Test
    @DisplayName("ffprobe가_길이를_모르면_400_INVALID_INPUT — 지어내지_않는다")
    void ffprobe_길이_미상_거부() {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());

        // durationMs=null(미상) — 0 으로 단정하지 않고 추출 실패로 다룬다.
        givenProbeDurationMs(null);
        MultipartFile file = mp4File("unknown.mp4", new byte[]{1, 2});

        assertThatThrownBy(() -> service.upload(file, validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(videoRepository, never()).save(any());
    }

    @Test
    @DisplayName("ffprobe_0초_반환시_400_INVALID_INPUT")
    void ffprobe_0초_반환_거부() {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());

        givenProbeDurationMs(0L);
        MultipartFile file = mp4File("zero.mp4", new byte[]{1, 2});

        assertThatThrownBy(() -> service.upload(file, validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(videoRepository, never()).save(any());
    }

    @Test
    @DisplayName("ffprobe_상한_초과시_400_INVALID_INPUT")
    void ffprobe_상한_초과_거부() {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());

        givenProbeDurationMs(7_201_000L);
        MultipartFile file = mp4File("toolong.mp4", new byte[]{1, 2});

        assertThatThrownBy(() -> service.upload(file, validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(videoRepository, never()).save(any());
    }

    @Test
    @DisplayName("신규영상_업로드시_DevPipelineRunner_runAsync_단일_위임_PROCESSING_반환")
    void 신규영상_업로드시_runner_위임() throws Exception {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        given(videoRepository.save(any(LsDataRaw.class))).willReturn(savedRaw(500L));

        MultipartFile file = mp4File("new.mp4", new byte[]{1, 2, 3});

        AutolabelTestResponse response = service.upload(file, validMeta());

        assertThat(response.rawSn()).isEqualTo(500L);
        assertThat(response.pipelineStatus()).isEqualTo("PROCESSING");

        // 단계 토글/마킹 분기 없이 단일 runAsync(rawSn) 으로 위임한다.
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                verify(devPipelineRunner).runAsync(eq(500L)));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 영상 기술메타(video.*) 적재 — 즉시 실행 경로 [@design SCREEN-027]
    //   이 경로는 VideoIngestedEvent 를 발행하지 않아 VideoMetaExtractBridge →
    //   AsyncVideoMetaRunner(= video.* 를 쓰는 유일한 통로)가 트리거되지 않았고,
    //   그래서 ffprobe 자동 추출조차 일어나지 않았다.
    // ─────────────────────────────────────────────────────────────────────────

    /** 기술메타 적재의 유일한 통로 — 서비스가 새 적재 경로를 만들지 않았음을 이 검증이 고정한다. */
    private VideoMeta capturedStoredMeta() {
        ArgumentCaptor<VideoMeta> captor = ArgumentCaptor.forClass(VideoMeta.class);
        verify(videoMetaService).upsertVideoMeta(eq(101L), captor.capture());
        return captor.getValue();
    }

    private void givenSaveReturns(Long rawSn) {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        given(videoRepository.save(any(LsDataRaw.class))).willReturn(savedRaw(rawSn));
    }

    @Test
    @DisplayName("즉시_실행_업로드가_영상_기술메타를_적재한다")
    void 즉시_실행_업로드가_영상_기술메타를_적재한다() throws Exception {
        // given — ffprobe 가 기술메타 전 필드를 돌려준다.
        givenSaveReturns(101L);

        // when
        service.upload(mp4File("video.mp4", new byte[]{1, 2, 3, 4}), validMeta());

        // then — 기존 통로(VideoMetaService)로 probe 결과가 그대로 넘어간다.
        VideoMeta stored = capturedStoredMeta();
        assertThat(stored.width()).isEqualTo(1920);
        assertThat(stored.height()).isEqualTo(1080);
        assertThat(stored.codecName()).isEqualTo("h264");
        assertThat(stored.fps()).isEqualTo(30.0);
        assertThat(stored.bitRate()).isEqualTo(4_500_000L);
        assertThat(stored.durationMs()).isEqualTo(60_000L);
        assertThat(stored.fileSize()).isEqualTo(12_345_678L);
    }

    @Test
    @DisplayName("추출할_수_없는_항목은_비워_두고_지어내지_않는다")
    void 추출할_수_없는_항목은_비워_두고_지어내지_않는다() throws Exception {
        // given — 코덱·비트레이트·파일크기 미상 + 비디오 스트림 해상도 없음(0). 길이만 확보.
        givenSaveReturns(101L);
        given(videoProbe.probe(any(Path.class)))
                .willReturn(new VideoMeta(0, 0, null, null, null, 45_000L, null));

        // when
        service.upload(mp4File("partial.mp4", new byte[]{1, 2}), validMeta());

        // then — 없는 값을 0·빈문자열·추정치로 채우지 않고 미상(null/0)을 그대로 넘긴다.
        //   실제 skip(= 행 미생성)은 VideoMetaService 가 담당한다(VideoMetaServiceTest).
        VideoMeta stored = capturedStoredMeta();
        assertThat(stored.codecName()).isNull();
        assertThat(stored.bitRate()).isNull();
        assertThat(stored.fileSize()).isNull();
        assertThat(stored.width()).isZero();
        assertThat(stored.height()).isZero();
        assertThat(stored.durationMs()).isEqualTo(45_000L);
    }

    @Test
    @DisplayName("옵션을_끄면_기술메타를_적재하지_않는다")
    void 옵션을_끄면_기술메타를_적재하지_않는다() throws Exception {
        // given
        givenSaveReturns(101L);
        DevAutolabelTestService off = newService(MAX_FILE_SIZE, false);

        // when — 업로드 자체는 정상 완료된다(끄는 것은 부가 기능만).
        AutolabelTestResponse response = off.upload(mp4File("v.mp4", new byte[]{1, 2}), validMeta());

        // then
        assertThat(response.rawSn()).isEqualTo(101L);
        verify(videoMetaService, never()).upsertVideoMeta(anyLong(), any(VideoMeta.class));
    }

    @Test
    @DisplayName("기술메타를_껐다는_사실이_기동_로그로_남는다")
    void 기술메타를_껐다는_사실이_기동_로그로_남는다() {
        // given/when — 요청마다 도배하지 않고 빈 생성 시점에 1회만 알린다.
        ListAppender<ILoggingEvent> logs = attachLogAppender();
        try {
            newService(MAX_FILE_SIZE, false);

            // then
            assertThat(logs.list.stream()
                    .filter(e -> e.getLevel() == Level.INFO)
                    .map(ILoggingEvent::getFormattedMessage))
                    .as("운영자가 '왜 기술메타가 없지' 를 추적할 수 있어야 한다")
                    .anyMatch(m -> m.contains("technical meta extraction disabled"));
        } finally {
            serviceLogger().detachAppender(logs);
        }
    }

    @Test
    @DisplayName("기술메타_적재가_실패해도_업로드는_성공한다")
    void 기술메타_적재가_실패해도_업로드는_성공한다() throws Exception {
        // given — 적재 통로가 터진다(부가 기능의 실패).
        givenSaveReturns(101L);
        willThrow(new IllegalStateException("meta store blew up"))
                .given(videoMetaService).upsertVideoMeta(anyLong(), any(VideoMeta.class));

        // when/then — 2xx 상당(정상 반환). duration 400 과 달리 업로드를 죽이지 않는다.
        AutolabelTestResponse response =
                service.upload(mp4File("v.mp4", new byte[]{1, 2}), validMeta());

        assertThat(response.rawSn()).isEqualTo(101L);
        assertThat(response.pipelineStatus()).isEqualTo("PROCESSING");
        // 파이프라인도 계속 간다 — 기술메타 실패가 후속 단계를 막지 않는다.
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                verify(devPipelineRunner).runAsync(eq(101L)));
    }

    @Test
    @DisplayName("기술메타_적재_실패는_로그로_남는다")
    void 기술메타_적재_실패는_로그로_남는다() throws Exception {
        // given
        givenSaveReturns(101L);
        willThrow(new IllegalStateException("meta store blew up"))
                .given(videoMetaService).upsertVideoMeta(anyLong(), any(VideoMeta.class));
        ListAppender<ILoggingEvent> logs = attachLogAppender();

        try {
            // when
            service.upload(mp4File("v.mp4", new byte[]{1, 2}), validMeta());

            // then — 조용히 삼키지 않는다.
            assertThat(logs.list.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .map(ILoggingEvent::getFormattedMessage))
                    .anyMatch(m -> m.contains("technical meta store failed"));
        } finally {
            serviceLogger().detachAppender(logs);
        }
    }

    @Test
    @DisplayName("프로브를_두_번_호출하지_않는다")
    void 프로브를_두_번_호출하지_않는다() throws Exception {
        // given — 호출 횟수와 «넘긴 결과가 그 호출의 결과인지» 를 함께 본다.
        givenSaveReturns(101L);
        AtomicInteger calls = new AtomicInteger();
        given(videoProbe.probe(any(Path.class))).willAnswer(inv -> {
            // 호출마다 다른 값을 돌려주므로, 두 번 불렀다면 duration 과 기술메타가 갈린다.
            long ms = 10_000L * calls.incrementAndGet();
            return new VideoMeta(1920, 1080, "h264", 30.0, 1L, ms, 1L);
        });

        // when
        service.upload(mp4File("v.mp4", new byte[]{1, 2}), validMeta());

        // then — ffprobe 프로세스를 두 번 띄우지 않는다.
        assertThat(calls.get()).isEqualTo(1);
        verify(videoProbe).probe(any(Path.class));
        // LS_DATA_RAW 의 길이와 기술메타의 길이가 «같은 한 번의 결과» 다.
        ArgumentCaptor<LsDataRaw> rawCaptor = ArgumentCaptor.forClass(LsDataRaw.class);
        verify(videoRepository).save(rawCaptor.capture());
        assertThat(rawCaptor.getValue().getDurationSec()).isEqualTo(10);
        assertThat(capturedStoredMeta().durationMs()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("기존_video_메타_키_집합_밖의_키를_만들지_않는다")
    void 기존_video_메타_키_집합_밖의_키를_만들지_않는다() throws Exception {
        // given — 실제 VideoMetaService 를 통과시켜 최종 META_KEY 를 관측한다.
        //   키 집합의 진실원은 VideoMetaService(KEY_FPS…KEY_RESOLUTION) 이며 아래는 그 고정 사본이다.
        Set<String> knownKeys = Set.of(
                "video.fps", "video.codec", "video.bit_rate",
                "video.duration_ms", "video.filesize", "video.resolution");
        LsDataMetaRepository metaRepository = mock(LsDataMetaRepository.class);
        LsDataIngestRepository ingestRepository = mock(LsDataIngestRepository.class);
        VideoMetaService realMetaService =
                new VideoMetaService(metaRepository, videoRepository, ingestRepository);
        DevAutolabelTestService real = new DevAutolabelTestService(
                videoRepository, devPipelineRunner, eventTypeService,
                videoProbe, realMetaService, storageRoot.toString(), MAX_FILE_SIZE, true);
        givenSaveReturns(101L);

        // when
        real.upload(mp4File("v.mp4", new byte[]{1, 2}), validMeta());

        // then — 새 키(video.* 밖 또는 미등록 video.* 키)를 만들지 않는다. 소비처가 읽지 않는다.
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(metaRepository, org.mockito.Mockito.atLeastOnce())
                .upsertMeta(eq(101L), keyCaptor.capture(), anyString());
        List<String> keys = keyCaptor.getAllValues();
        assertThat(keys).isNotEmpty();
        assertThat(keys).allMatch(k -> k.startsWith(VideoMetaService.KEY_PREFIX));
        assertThat(knownKeys).containsAll(keys);
    }

    private static ch.qos.logback.classic.Logger serviceLogger() {
        return (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(DevAutolabelTestService.class);
    }

    private static ListAppender<ILoggingEvent> attachLogAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        serviceLogger().addAppender(appender);
        return appender;
    }
}
