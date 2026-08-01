package kr.co.cudo.authoring.dev.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.dev.dto.AutolabelTestRequest;
import kr.co.cudo.authoring.dev.dto.AutolabelTestResponse;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.entity.MngClipMasterId;
import kr.co.cudo.authoring.video.repository.MngClipMasterRepository;
import kr.co.cudo.authoring.video.repository.MngResourceCctvRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.TrainingVideoIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link DevAutolabelTestService} 단위 테스트 — Phase 3 "관제 INSERT → 실제 픽업 배치" 전환 반영.
 *
 * <p>구(舊) 전제(‘서비스가 LS_DATA_RAW 를 직접 만든다’ + ‘DevPipelineRunner 로 비식별 트리거’)는
 * 전부 제거되었다. 새 계약:
 * <ol>
 *   <li>{@link DevControlClipWriter} 에 관제 두 테이블 INSERT 를 위임하고,</li>
 *   <li><b>커밋 이후</b> 운영 픽업 스캔({@link TrainingVideoIngestService#scanAndIngest()})을 호출하며,</li>
 *   <li>{@code VMS_CLIP_ID} 로 rawSn 을 회수한다. 회수 실패 시 {@code INGEST_PENDING}(거짓 성공 금지).</li>
 * </ol>
 */
class DevAutolabelTestServiceTest {

    private VideoRepository videoRepository;
    private MngResourceCctvRepository cctvRepository;
    private MngClipMasterRepository clipMasterRepository;
    private EventTypeService eventTypeService;
    private TrainingVideoIngestService ingestService;
    private DevControlClipWriter clipWriter;

    private DevAutolabelTestService service;

    @TempDir
    Path storageRoot;

    private static final long MAX_FILE_SIZE = 524_288_000L; // 500MB
    /** dev 업로드 도구가 받는 관제 상세 EV-코드 (쓰러짐 카테고리 020002 의 대표 코드). */
    private static final String VALID_EV_CODE = "EV02000201";
    private static final String CLIP_ID = "CLP-testclip001";

    /** {@code {raw-path}/data/upload/v2} — application.yml 기본값(CONST-050 규약)과 동일 조립. */
    private Path clipBase() {
        return storageRoot.resolve("data").resolve("upload").resolve("v2");
    }

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        cctvRepository = mock(MngResourceCctvRepository.class);
        clipMasterRepository = mock(MngClipMasterRepository.class);
        eventTypeService = mock(EventTypeService.class);
        ingestService = mock(TrainingVideoIngestService.class);
        clipWriter = mock(DevControlClipWriter.class);
        given(eventTypeService.categoryKeyOf(VALID_EV_CODE)).willReturn(Optional.of("020002"));
        given(cctvRepository.existsById("CCTV-001")).willReturn(true);
        service = newService(clipWriter, path -> 60);
    }

    private DevAutolabelTestService newService(DevControlClipWriter writer,
                                               DevAutolabelTestService.DurationProbe probe) {
        Supplier<DevControlClipWriter> supplier = () -> writer;
        return new DevAutolabelTestService(
                videoRepository, cctvRepository, clipMasterRepository, eventTypeService,
                ingestService, supplier,
                storageRoot.toString(), clipBase().toString(),
                MAX_FILE_SIZE, "ffprobe", probe);
    }

    private AutolabelTestRequest validMeta() {
        return new AutolabelTestRequest(
                CLIP_ID, "CCTV-001", VALID_EV_CODE, "1168000000", "ANONY",
                Instant.parse("2024-05-01T12:00:00Z"));
    }

    private MultipartFile mp4File(String filename, byte[] content) {
        return new MockMultipartFile("file", filename, "video/mp4", content);
    }

    /** 스캔이 적재에 성공해 rawSn 이 회수되는 상황을 만든다. */
    private void givenIngestedAfterScan(String clipId, Long rawSn) {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                clipId, "CCTV-001", VALID_EV_CODE, "1168000000", "ANONY",
                "/nas/x.mp4", null, 60);
        setRawSn(raw, rawSn);
        // 사전 중복 검증(적재 전) 은 empty, 스캔 이후 조회는 적재된 행을 반환한다.
        given(videoRepository.findByVmsClipId(clipId))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(raw));
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

    // ------------------------------------------------------------------ 정상 흐름

    @Test
    @DisplayName("업로드하면_관제_두_테이블에_행이_생긴다")
    void delegatesControlInsert() {
        givenIngestedAfterScan(CLIP_ID, 42L);

        AutolabelTestResponse response = service.upload(mp4File("sample.mp4", new byte[]{1, 2, 3, 4}), validMeta());

        assertThat(response.rawSn()).isEqualTo(42L);
        ArgumentCaptor<DevControlClipWriter.ControlClipRow> captor =
                ArgumentCaptor.forClass(DevControlClipWriter.ControlClipRow.class);
        verify(clipWriter).insertClip(captor.capture());
        DevControlClipWriter.ControlClipRow row = captor.getValue();
        assertThat(row.clipId()).isEqualTo(CLIP_ID);
        assertThat(row.vmsCctvId()).isEqualTo("CCTV-001");
        assertThat(row.evntTypeCd()).isEqualTo(VALID_EV_CODE);
        assertThat(row.lclgvCd()).isEqualTo("1168000000");
        assertThat(row.prvcTypeCd()).isEqualTo("ANONY");
        // 픽업 배치가 집으려면 학습용 지정(JOB_DMND_YN='Y') 이어야 한다.
        assertThat(row.jobDmndYn()).isEqualTo("Y");
        assertThat(row.clipTypeCd()).isEqualTo("ORIGINAL");
    }

    @Test
    @DisplayName("업로드_후_스캔이_돌아_LS_DATA_RAW_가_적재된다")
    void runsProductionScanAfterInsert() {
        givenIngestedAfterScan(CLIP_ID, 42L);

        AutolabelTestResponse response = service.upload(mp4File("s.mp4", new byte[]{1}), validMeta());

        verify(ingestService).scanAndIngest();
        assertThat(response.rawSn()).isEqualTo(42L);
        assertThat(response.pipelineStatus()).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("적재는_TrainingVideoIngestTx_경로를_거친다_dev_우회_적재_없음")
    void neverWritesLsDataRawDirectly() {
        givenIngestedAfterScan(CLIP_ID, 42L);

        service.upload(mp4File("s.mp4", new byte[]{1}), validMeta());

        // dev 전용 우회 적재 제거 — LS_DATA_RAW 는 오직 픽업 배치(TrainingVideoIngestTx)만 만든다.
        // 그 경로가 VideoIngestedEvent 를 발행해 운영 비식별(IngestDeidentifyBridge)이 이어진다.
        verify(videoRepository, never()).save(any(LsDataRaw.class));
        verify(ingestService).scanAndIngest();
    }

    @Test
    @DisplayName("INSERT_커밋_이전에_스캔이_호출되지_않는다")
    void scanRunsOnlyAfterInsertCommits() throws Exception {
        givenIngestedAfterScan(CLIP_ID, 42L);

        service.upload(mp4File("s.mp4", new byte[]{1}), validMeta());

        // ① 호출 순서: 관제 INSERT → 스캔
        InOrder order = inOrder(clipWriter, ingestService);
        order.verify(clipWriter).insertClip(any());
        order.verify(ingestService).scanAndIngest();

        // ② 커밋 경계 소유자 검증 — 서비스에는 @Transactional 이 없어야 하고(있으면 스캔이 같은 tx 안에서
        //    돌아 미커밋 행을 못 본다), 커밋은 writer 의 @Transactional 이 담당한다.
        assertThat(DevAutolabelTestService.class.getAnnotation(Transactional.class)).isNull();
        assertThat(DevAutolabelTestService.class
                .getMethod("upload", MultipartFile.class, AutolabelTestRequest.class)
                .getAnnotation(Transactional.class)).isNull();
        Transactional writerTx = DevControlClipWriter.class
                .getMethod("insertClip", DevControlClipWriter.ControlClipRow.class)
                .getAnnotation(Transactional.class);
        assertThat(writerTx).isNotNull();
        assertThat(writerTx.value()).isEqualTo("controlTransactionManager");
    }

    @Test
    @DisplayName("적재가_안되면_rawSn없이_INGEST_PENDING을_반환한다")
    void returnsIngestPendingWhenNotIngested() {
        given(videoRepository.findByVmsClipId(CLIP_ID)).willReturn(Optional.empty());
        given(ingestService.scanAndIngest()).willReturn(0);

        AutolabelTestResponse response = service.upload(mp4File("s.mp4", new byte[]{1}), validMeta());

        // 거짓 성공 금지 — 적재되지 않았으면 rawSn 을 지어내지 않는다.
        assertThat(response.rawSn()).isNull();
        assertThat(response.pipelineStatus()).isEqualTo("INGEST_PENDING");
    }

    // ------------------------------------------------------------------ 경로 규약

    @Test
    @DisplayName("저장경로가_clipNasBasePath_하위이고_autolabel_문자열이_없다")
    void savesUnderClipNasBasePath() throws Exception {
        givenIngestedAfterScan(CLIP_ID, 1L);

        AutolabelTestResponse response = service.upload(mp4File("sample.mp4", new byte[]{1, 2, 3, 4}), validMeta());

        Path expected = clipBase().resolve(CLIP_ID + ".mp4");
        assertThat(Files.exists(expected)).isTrue();
        assertThat(Files.size(expected)).isEqualTo(4L);
        // 구 규약(UPLOAD_SUBDIR="autolabel-test") 폐지
        assertThat(response.savedFilePath()).doesNotContain("autolabel-test");
        assertThat(response.savedFilePath()).isEqualTo("data/upload/v2/" + CLIP_ID + ".mp4");
        // CWE-209 — 절대경로 미노출
        assertThat(response.savedFilePath()).doesNotContain(storageRoot.toString());

        // 관제 FILE_PATH 에는 픽업 배치가 그대로 열 수 있는 절대경로가 들어간다.
        ArgumentCaptor<DevControlClipWriter.ControlClipRow> captor =
                ArgumentCaptor.forClass(DevControlClipWriter.ControlClipRow.class);
        verify(clipWriter).insertClip(captor.capture());
        assertThat(captor.getValue().filePath()).isEqualTo(expected.toString());
    }

    @Test
    @DisplayName("clipId_미전송시_CLP접두_30자이내로_생성된다")
    void generatesClipIdWhenAbsent() {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());

        AutolabelTestRequest meta = new AutolabelTestRequest(
                null, "CCTV-001", VALID_EV_CODE, "1168000000", "ANONY",
                Instant.parse("2024-05-01T12:00:00Z"));

        service.upload(mp4File("s.mp4", new byte[]{1}), meta);

        ArgumentCaptor<DevControlClipWriter.ControlClipRow> captor =
                ArgumentCaptor.forClass(DevControlClipWriter.ControlClipRow.class);
        verify(clipWriter).insertClip(captor.capture());
        String generated = captor.getValue().clipId();
        assertThat(generated).startsWith("CLP-").hasSizeLessThanOrEqualTo(30);
        assertThat(generated).matches("^CLP-[A-Za-z0-9]{1,26}$");
    }

    @Test
    @DisplayName("evntId_미전송시_50자이내로_생성된다")
    void generatesEvntIdWhenAbsent() {
        givenIngestedAfterScan(CLIP_ID, 7L);

        service.upload(mp4File("s.mp4", new byte[]{1}), validMeta());

        ArgumentCaptor<DevControlClipWriter.ControlClipRow> captor =
                ArgumentCaptor.forClass(DevControlClipWriter.ControlClipRow.class);
        verify(clipWriter).insertClip(captor.capture());
        assertThat(captor.getValue().evntId()).matches("^[A-Za-z0-9_-]{1,50}$");
    }

    @Test
    @DisplayName("clipId_형식위반이면_400을_반환한다")
    void rejectsMalformedClipId() {
        // ① 경로 순회 문자 포함
        AutolabelTestRequest traversal = new AutolabelTestRequest(
                "CLP-../../etc/passwd", "CCTV-001", VALID_EV_CODE, "1168000000", "ANONY",
                Instant.parse("2024-05-01T12:00:00Z"));
        assertThatThrownBy(() -> service.upload(mp4File("s.mp4", new byte[]{1}), traversal))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        // ② 31자 (CLP- + 27자) — 상한 초과
        AutolabelTestRequest tooLong = new AutolabelTestRequest(
                "CLP-" + "a".repeat(27), "CCTV-001", VALID_EV_CODE, "1168000000", "ANONY",
                Instant.parse("2024-05-01T12:00:00Z"));
        assertThatThrownBy(() -> service.upload(mp4File("s.mp4", new byte[]{1}), tooLong))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(clipWriter, never()).insertClip(any());
    }

    // ------------------------------------------------------------------ 단위 왕복

    @Test
    @DisplayName("영상길이가_초에서_ms로_변환되어_관제에_저장되고_적재시_초로_복원된다")
    void videoLengthRoundTripsSecondsToMillis() {
        givenIngestedAfterScan(CLIP_ID, 9L);
        DevAutolabelTestService localService = newService(clipWriter, path -> 137);

        localService.upload(mp4File("v.mp4", new byte[]{1, 2}), validMeta());

        ArgumentCaptor<DevControlClipWriter.ControlClipRow> captor =
                ArgumentCaptor.forClass(DevControlClipWriter.ControlClipRow.class);
        verify(clipWriter).insertClip(captor.capture());
        Integer storedMs = captor.getValue().vdoLenMs();
        // 관제 VDO_LEN_SEC 실측 단위 = ms
        assertThat(storedMs).isEqualTo(137_000);
        // 적재(TrainingVideoIngestTx) 의 역변환과 왕복 일치 — Math.round(ms/1000f)
        assertThat(Math.round(storedMs / 1000f)).isEqualTo(137);
    }

    @Test
    @DisplayName("요청이_vdoLenSec_를_명시하면_그_값이_ms로_저장된다")
    void explicitVideoLengthWins() {
        givenIngestedAfterScan(CLIP_ID, 11L);
        AutolabelTestRequest meta = new AutolabelTestRequest(
                CLIP_ID, "CCTV-001", VALID_EV_CODE, "1168000000", "ANONY",
                Instant.parse("2024-05-01T12:00:00Z"),
                null, null, null, null, null, 300, null, null, null, null, null, null,
                null, null, null, null, null, null);

        service.upload(mp4File("v.mp4", new byte[]{1}), meta);

        ArgumentCaptor<DevControlClipWriter.ControlClipRow> captor =
                ArgumentCaptor.forClass(DevControlClipWriter.ControlClipRow.class);
        verify(clipWriter).insertClip(captor.capture());
        assertThat(captor.getValue().vdoLenMs()).isEqualTo(300_000);
    }

    // ------------------------------------------------------------------ 충돌 구분

    @Test
    @DisplayName("같은_clipId_다른_evntId_재업로드시_409를_반환한다")
    void duplicateClipIdReturns409WithLsDataRawMessage() {
        LsDataRaw existing = LsDataRaw.createFromIngest(
                CLIP_ID, "CCTV-001", VALID_EV_CODE, "1168000000", "ANONY", "/nas/x.mp4", null, 60);
        given(videoRepository.findByVmsClipId(CLIP_ID)).willReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.upload(mp4File("s.mp4", new byte[]{1}), validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);

        // 관제 PK 축이 아니라 LS_DATA_RAW UK 축임을 메시지로 구분한다.
        try {
            service.upload(mp4File("s.mp4", new byte[]{1}), validMeta());
        } catch (CustomException e) {
            assertThat(e.getMessage()).contains("LS_DATA_RAW.VMS_CLIP_ID");
            assertThat(e.getMessage()).doesNotContain("MNG_CLIP_MASTER");
        }
        verify(clipWriter, never()).insertClip(any());
    }

    @Test
    @DisplayName("같은_evntId와_clipTypeCd_재업로드시_409를_반환한다")
    void duplicateControlPkReturns409WithControlMessage() {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        given(clipMasterRepository.existsById(any(MngClipMasterId.class))).willReturn(true);

        AutolabelTestRequest meta = new AutolabelTestRequest(
                CLIP_ID, "CCTV-001", VALID_EV_CODE, "1168000000", "ANONY",
                Instant.parse("2024-05-01T12:00:00Z"),
                "DEV-dup-1", "ORIGINAL", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null);

        try {
            service.upload(mp4File("s.mp4", new byte[]{1}), meta);
        } catch (CustomException e) {
            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
            assertThat(e.getMessage()).contains("MNG_CLIP_MASTER");
            assertThat(e.getMessage()).doesNotContain("LS_DATA_RAW");
        }
        verify(clipWriter, never()).insertClip(any());
    }

    // ------------------------------------------------------------------ prd 게이팅

    @Test
    @DisplayName("prd_프로파일에서_관제쓰기_빈이_등록되지_않으면_업로드는_403")
    void refusesUploadWhenWriterBeanAbsent() {
        DevAutolabelTestService prdService = newService(null, path -> 60);
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> prdService.upload(mp4File("s.mp4", new byte[]{1}), validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("관제_쓰기_빈은_prd_프로파일_어노테이션으로_차단된다")
    void writerBeanIsProfileGated() {
        org.springframework.context.annotation.Profile profile =
                DevControlClipWriter.class.getAnnotation(org.springframework.context.annotation.Profile.class);
        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("!prd");
    }

    // ------------------------------------------------------------------ 기존 파일 검증 회귀

    @Test
    @DisplayName("허용_안된_확장자_exe_업로드_400")
    void exe_업로드_거부() {
        MultipartFile file = new MockMultipartFile("file", "evil.exe",
                "application/octet-stream", new byte[]{1, 2});

        assertThatThrownBy(() -> service.upload(file, validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(clipWriter, never()).insertClip(any());
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
        DevAutolabelTestService smallLimitService = new DevAutolabelTestService(
                videoRepository, cctvRepository, clipMasterRepository, eventTypeService,
                ingestService, () -> clipWriter,
                storageRoot.toString(), clipBase().toString(), 4L, "ffprobe", path -> 60);

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
    @DisplayName("cctvId_미등록_400_INVALID_INPUT")
    void cctvId_미등록() {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        given(cctvRepository.existsById("CCTV-001")).willReturn(false);

        assertThatThrownBy(() -> service.upload(mp4File("s.mp4", new byte[]{1, 2}), validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(clipWriter, never()).insertClip(any());
    }

    @Test
    @DisplayName("AutolabelTest_eventTypeCd_관제미등록코드면_400_관제INSERT_안됨")
    void eventTypeCd_관제미등록코드_400() {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        given(eventTypeService.categoryKeyOf("EV09999999")).willReturn(Optional.empty());

        AutolabelTestRequest meta = new AutolabelTestRequest(
                CLIP_ID, "CCTV-001", "EV09999999", "1168000000", "ANONY",
                Instant.parse("2024-05-01T12:00:00Z"));

        assertThatThrownBy(() -> service.upload(mp4File("unknown.mp4", new byte[]{1, 2, 3}), meta))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(clipWriter, never()).insertClip(any());
    }

    @Test
    @DisplayName("path_traversal_파일명_원본은_무시_clipId_기반_파일명_저장")
    void path_traversal_파일명_시도() {
        givenIngestedAfterScan(CLIP_ID, 99L);

        MultipartFile file = new MockMultipartFile("file",
                "../../etc/passwd.mp4", "video/mp4", new byte[]{1, 2});

        AutolabelTestResponse response = service.upload(file, validMeta());

        assertThat(response.savedFilePath()).doesNotContain("..").doesNotContain("passwd");
        assertThat(Files.exists(clipBase().resolve(CLIP_ID + ".mp4"))).isTrue();
    }

    @Test
    @DisplayName("octet_stream_MIME_도_허용")
    void octet_stream_허용() {
        givenIngestedAfterScan(CLIP_ID, 10L);

        MultipartFile file = new MockMultipartFile("file", "video.mp4",
                "application/octet-stream", new byte[]{1, 2, 3});

        assertThat(service.upload(file, validMeta()).rawSn()).isEqualTo(10L);
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

    @Test
    @DisplayName("ffprobe_실패시_400_INVALID_INPUT_관제INSERT_안됨_파일도_정리된다")
    void ffprobe_실패시_400() {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        DevAutolabelTestService localService = newService(clipWriter, path -> {
            throw new IllegalStateException("ffprobe call failed: corrupted file");
        });

        assertThatThrownBy(() -> localService.upload(mp4File("corrupt.mp4", new byte[]{1, 2, 3}), validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(clipWriter, never()).insertClip(any());
        // 고아 파일이 남지 않는다.
        assertThat(Files.exists(clipBase().resolve(CLIP_ID + ".mp4"))).isFalse();
    }

    @Test
    @DisplayName("ffprobe_0초_반환시_400_INVALID_INPUT")
    void ffprobe_0초_반환_거부() {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        DevAutolabelTestService localService = newService(clipWriter, path -> 0);

        assertThatThrownBy(() -> localService.upload(mp4File("zero.mp4", new byte[]{1, 2}), validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(clipWriter, never()).insertClip(any());
    }

    @Test
    @DisplayName("ffprobe_상한_초과시_400_INVALID_INPUT")
    void ffprobe_상한_초과_거부() {
        given(videoRepository.findByVmsClipId(any())).willReturn(Optional.empty());
        DevAutolabelTestService localService = newService(clipWriter, path -> 7201);

        assertThatThrownBy(() -> localService.upload(mp4File("toolong.mp4", new byte[]{1, 2}), validMeta()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        verify(clipWriter, never()).insertClip(any());
    }
}
