package kr.co.cudo.authoring.upload;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport;
import kr.co.cudo.authoring.upload.dto.InternalUploadCreateRequest;
import kr.co.cudo.authoring.upload.entity.LsTusUpload;
import kr.co.cudo.authoring.upload.repository.LsTusUploadRepository;
import kr.co.cudo.authoring.upload.service.InternalUploadIngestTerminator;
import kr.co.cudo.authoring.upload.service.InternalUploadPathResolver;
import kr.co.cudo.authoring.upload.service.TusUploadService;
import kr.co.cudo.authoring.video.dto.InternalUploadIngestCommand;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.InternalUploadIngestWriter;
import kr.co.cudo.authoring.video.repository.LsDataIngestRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TUS 업로드 서비스 단위 테스트 — 정상 흐름 + HIGH 9 시나리오 방어.
 *
 * <p>리포지토리는 in-memory Map / Mockito 목으로, 파일시스템은 {@link TempDir} 실디스크로 대체해
 * DB/ffprobe 외부 의존 없이 결정적으로 모든 동시성·멱등·경계 케이스를 검증한다.
 *
 * <h3>Phase 3 — 인입 행은 <b>세션 생성(POST)</b> 시점에 만들어진다</h3>
 * <p>Phase 1 은 <b>완료 시점</b>에 인입 행을 INSERT 했다. Phase 3 부터는 관제 인입 29컬럼을 화면에서
 * 받아 <b>세션 생성 시점</b>에 인입 행(PENDING)을 남기고, 파일은 청크 업로드가 끝나면 그 행이 이미
 * 가리키고 있는 최종 경로로 이동한다. 인입 테이블이 "행 먼저, 파일 나중"을 이미 견디기 때문이다
 * ({@code TrainingVideoIngestTx#handleNotArrived} — 파일 대기는 실패가 아니다).
 *
 * <p>그래서 실패 모델도 바뀐다:
 * <ul>
 *   <li>완료 시 <b>추가 INSERT 가 없다</b> — 대신 {@code NXTM_RTY_DT} 를 지금으로 당겨 backoff 를 푼다.</li>
 *   <li>이동한 파일을 <b>회수하지 않는다</b> — 그 파일을 가리키는 인입 행이 <b>이미 있다</b>
 *       (Phase 1 의 고아 파일 문제가 구조적으로 사라진다).</li>
 *   <li>취소·완료 검증 실패는 인입 행을 {@code FAILED} 로 <b>종결</b>한다(24시간 미도착 대기 방지).</li>
 * </ul>
 */
class TusUploadServiceTest {

    private static final String OWNER = "user-1";
    private static final long MAX_SIZE = 524_288_000L; // 500MB
    /** mp4 ISO BMFF 시그니처 (4~8바이트 'ftyp'). */
    private static final byte[] MP4_HEAD = new byte[]{
            0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2', 0, 0, 0, 0};

    @TempDir
    Path storageDir;

    private LsTusUploadRepository repository;
    private VideoRepository videoRepository;
    private LsDataIngestRepository ingestRepository;
    private InternalUploadIngestWriter ingestWriter;
    private InternalUploadPathResolver pathResolver;
    /** 종결 판정은 <b>실물</b>을 쓴다 — 목으로 대체하면 "파일 실재 확인" 규약이 검증되지 않는다. */
    private InternalUploadIngestTerminator ingestTerminator;
    private TusUploadService service;
    private final AtomicLong rcptnSnSeq = new AtomicLong(7000);
    /**
     * writer 가 실제로 INSERT 한 인입 행(클립 ID → 행) — 인입 조회·상태 전이 목의 <b>진실원</b>.
     *
     * <p>구 픽스처는 {@code clipId → RCPTN_SN} 만 들고 있어 <b>행의 상태</b>를 표현하지 못했다.
     * 그래서 "완료 시점에 그 행이 이미 종결돼 있었다"(H2) · "취소 종결분을 되살려 재업로드한다"(M1)
     * 같은 상태 의존 경로를 단위 테스트가 구조적으로 재현할 수 없었다.
     */
    private final Map<String, LsDataIngest> ingestRows = new HashMap<>();

    @BeforeEach
    void setUp() {
        repository = new InMemoryRepo();
        videoRepository = mock(VideoRepository.class);
        ingestRepository = mock(LsDataIngestRepository.class);
        ingestWriter = mock(InternalUploadIngestWriter.class);
        pathResolver = new InternalUploadPathResolver(
                ArtifactRootTestSupport.coLocate(storageDir), storageDir.toString());

        when(videoRepository.findByVmsClipId(anyString())).thenReturn(Optional.empty());
        // INSERT 는 실제 DB 대신 맵에 기록 — 이후 findByVmsClipId 가 그 행을 돌려준다(완료/취소 경로).
        when(ingestWriter.insertPending(any(InternalUploadIngestCommand.class))).thenAnswer(inv -> {
            InternalUploadIngestCommand cmd = inv.getArgument(0);
            long sn = rcptnSnSeq.incrementAndGet();
            ingestRows.put(cmd.vmsClipId(),
                    ingestRow(sn, LsDataIngest.PRCS_STTS_PENDING, cmd.rawFilePathNm(), null));
            return sn;
        });
        when(ingestRepository.findByVmsClipId(anyString())).thenAnswer(inv ->
                Optional.ofNullable(ingestRows.get(inv.<String>getArgument(0))));
        when(ingestRepository.findById(anyLong())).thenAnswer(inv ->
                Optional.ofNullable(rowBySn(inv.getArgument(0))));
        // 되살리기 UPDATE 술어(FAILED + RAW_SN IS NULL)를 그대로 모사한다.
        when(ingestWriter.reviveForUpload(anyLong(), any(InternalUploadIngestCommand.class)))
                .thenAnswer(inv -> {
                    LsDataIngest row = rowBySn(inv.getArgument(0));
                    if (row == null || !LsDataIngest.PRCS_STTS_FAILED.equals(row.getPrcsSttsCd())
                            || row.getRawSn() != null) {
                        return 0;
                    }
                    setIngestState(row, LsDataIngest.PRCS_STTS_PENDING);
                    return 1;
                });
        // 실제 SQL 술어(PRCS_STTS_CD='PENDING')를 그대로 모사한다 — 상태에 따라 0행을 돌려줘야
        //   "그 사이 폴링이 집었다/종결됐다" 분기를 단위 테스트가 재현할 수 있다.
        when(ingestRepository.markUploadArrived(anyLong(), any(LocalDateTime.class)))
                .thenAnswer(inv -> pendingRowBySn(inv.getArgument(0)) != null ? 1 : 0);
        when(ingestRepository.terminatePendingUpload(anyLong(), anyString(), any(LocalDateTime.class)))
                .thenAnswer(inv -> {
                    LsDataIngest row = pendingRowBySn(inv.getArgument(0));
                    if (row == null) {
                        return 0;
                    }
                    setIngestState(row, LsDataIngest.PRCS_STTS_FAILED);
                    return 1;
                });

        service = newService();
    }

    /** {@code RCPTN_SN} 으로 <b>미처리(PENDING)</b> 인입 행 찾기 — 조건부 UPDATE 술어 모사용. */
    private LsDataIngest pendingRowBySn(Long rcptnSn) {
        LsDataIngest row = rowBySn(rcptnSn);
        return (row != null && LsDataIngest.PRCS_STTS_PENDING.equals(row.getPrcsSttsCd()))
                ? row : null;
    }

    private LsDataIngest rowBySn(Long rcptnSn) {
        return ingestRows.values().stream()
                .filter(r -> r.getRcptnSn().equals(rcptnSn))
                .findFirst().orElse(null);
    }

    private long rcptnSnOf(String vmsClipId) {
        return ingestRows.get(vmsClipId).getRcptnSn();
    }

    private TusUploadService newService() {
        // 종결 판정은 <실물>을 쓴다 — 목으로 대체하면 "파일 실재 확인" 규약이 검증되지 않는다.
        //   seam 이 pathResolver 를 갈아끼우는 테스트가 있으므로 <현재> resolver 로 다시 만든다.
        ingestTerminator = new InternalUploadIngestTerminator(ingestRepository, pathResolver);
        // duration probe stub — ffprobe 대체, 항상 60초 반환.
        return new TusUploadService(repository, videoRepository, ingestRepository,
                ingestWriter, pathResolver, ingestTerminator, storageDir.toString(), MAX_SIZE,
                path -> 60);
    }

    // ======================== 요청 픽스처 ========================

    /** 필수 5(+지자체코드)만 채운 최소 요청 — 기술메타는 전부 비어 있다(= ffprobe 폴백 대상). */
    private static InternalUploadCreateRequest minimal(String clipId) {
        return request(clipId, "CCTV-1", null, null, null, null);
    }

    /** 기술메타 3종(길이·FPS·해상도)만 채운 요청 — "채운 키만 실린다" 검증용. */
    private static InternalUploadCreateRequest withPartialTechMeta(String clipId) {
        return request(clipId, "CCTV-1", BigDecimal.valueOf(123), "29.97", "1920x1080", null);
    }

    private static InternalUploadCreateRequest request(String clipId, String cctvId,
                                                       BigDecimal vdoLenSec, String fps,
                                                       String resl, String srcType) {
        return new InternalUploadCreateRequest(
                "clip.mp4", clipId, cctvId, srcType, "1168000000",
                LocalDateTime.of(2024, 5, 1, 12, 0),
                null, null, null, null,
                vdoLenSec, fps, null, null, null, null, resl, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    // ======================== 정상 흐름 — 인입 행은 세션 생성 시점 ========================

    @Test
    @DisplayName("세션생성시_인입행이_PENDING으로_1건_생기고_RAW_FILE_PATH_NM은_최종_NAS경로다")
    void createSessionInsertsPendingIngestRowWithFinalPath() {
        // when — 세션만 생성(청크 전송 전)
        service.createSession(OWNER, 10, minimal("VMS-1"));

        // then — 인입 행 1건. 경로는 <업로드가 끝나면 파일이 놓일> 최종 경로다.
        InternalUploadIngestCommand inserted = captureInsert();
        assertThat(inserted.vmsClipId()).isEqualTo("VMS-1");
        assertThat(inserted.vmsCctvId()).isEqualTo("CCTV-1");
        assertThat(inserted.srcType()).isEqualTo(LsDataIngest.SRC_TYPE_USER_ULD);
        assertThat(inserted.vdoFileNm()).isEqualTo("VMS-1.mp4");
        assertThat(inserted.rawFilePathNm())
                .isEqualTo(pathResolver.resolveUploadTarget("VMS-1", "mp4").toString());
        assertThat(inserted.lclgvCd()).isEqualTo("1168000000");
        assertThat(inserted.shtDt()).isEqualTo(LocalDateTime.of(2024, 5, 1, 12, 0));
        // then — 파일은 아직 그 경로에 없다("행 먼저, 파일 나중" — 폴링이 미도착 대기한다)
        assertThat(Files.exists(pathResolver.resolveUploadTarget("VMS-1", "mp4"))).isFalse();
    }

    @Test
    @DisplayName("정상_생성_청크2회_완료시_인입INSERT는_더_일어나지_않고_파일이동과_재시도예정_리셋만_한다")
    void completionMovesFileAndResetsRetryWithoutSecondInsert() {
        // given — 10바이트 mp4 를 5+5 두 청크로 업로드
        byte[] full = withMp4Head(10);
        UUID id = service.createSession(OWNER, 10, minimal("VMS-1"));
        long rcptnSn = rcptnSnOf("VMS-1");

        // when — 첫 청크(offset 0, 5바이트)
        var r1 = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 5), 5);
        assertThat(r1.newOffset()).isEqualTo(5);
        assertThat(r1.completed()).isFalse();

        // when — 둘째 청크(offset 5, 5바이트) → 완료
        var r2 = service.appendChunk(id, OWNER, 5, new ByteArrayInputStream(full, 5, 5), 5);

        // then — 완료. INSERT 는 <세션 생성 때 1회>가 전부다.
        assertThat(r2.completed()).isTrue();
        assertThat(r2.rcptnSn()).isEqualTo(rcptnSn);
        verify(ingestWriter, times(1)).insertPending(any(InternalUploadIngestCommand.class));
        verify(videoRepository, never()).save(any(LsDataRaw.class));

        // then — 파일이 인입 행이 가리키는 최종 경로에 놓였다
        assertThat(Files.exists(pathResolver.resolveUploadTarget("VMS-1", "mp4"))).isTrue();

        // then — ★backoff 해제: 다음 재시도 예정 시각을 지금으로 당겨 곧바로 픽업되게 한다.
        //   이게 없으면 20분짜리 업로드는 도착 후에도 최대 20분을 더 기다린다("기다린 만큼 더" backoff).
        verify(ingestRepository).markUploadArrived(eq(rcptnSn), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("기술메타를_채워보내면_인입행에_그대로_실리고_비운키만_null로_남는다 — 폴백은_키_단위")
    void suppliedTechnicalMetaIsCarriedAndOnlyBlankKeysStayNull() {
        // when — 길이·FPS·해상도만 채워 보낸다
        service.createSession(OWNER, 10, withPartialTechMeta("VMS-1"));

        // then — 채운 키는 그 값 그대로
        InternalUploadIngestCommand inserted = captureInsert();
        assertThat(inserted.vdoLenSec()).isEqualByComparingTo("123");
        assertThat(inserted.fps()).isEqualTo("29.97");
        assertThat(inserted.resl()).isEqualTo("1920x1080");

        // then — ★비운 키는 null 이다(추측해 채우지 않는다). 적재 후 VideoMetaService 가
        //   "관제 인입값 우선, 없는 키만 ffprobe" 규칙으로 이 키들만 채운다.
        assertThat(inserted.frmeCnt()).isNull();
        assertThat(inserted.wdth()).isNull();
        assertThat(inserted.vrtc()).isNull();
        assertThat(inserted.vdoCdc()).isNull();
        assertThat(inserted.asprtRt()).isNull();
        assertThat(inserted.bit()).isNull();
        assertThat(inserted.pxl()).isNull();
    }

    @Test
    @DisplayName("기술메타를_비우면_파일크기와_파일형식만_서버가_안다 — 나머지는_null")
    void blankTechnicalMetaFallsBackToServerKnownValuesOnly() {
        service.createSession(OWNER, 4096, minimal("VMS-1"));

        InternalUploadIngestCommand inserted = captureInsert();
        // 파일크기는 Upload-Length 로, 파일형식은 확장자로 <이미 알고 있다> — 추측이 아니다.
        assertThat(inserted.fileSz()).isEqualTo(4096L);
        assertThat(inserted.fileFmt()).isEqualTo("mp4");
        // 나머지는 파일을 열어야 알 수 있으므로 비운 채 둔다(ffprobe 폴백 대상).
        assertThat(inserted.vdoLenSec()).isNull();
        assertThat(inserted.fps()).isNull();
        assertThat(inserted.resl()).isNull();
    }

    @Test
    @DisplayName("사용자가_보낸_파일크기_파일형식이_서버_기본값을_이긴다")
    void suppliedFileSizeAndFormatWinOverDerivedDefaults() {
        InternalUploadCreateRequest req = new InternalUploadCreateRequest(
                "clip.mp4", "VMS-1", "CCTV-1", null, "1168000000", null,
                "mpeg4", null, 99L, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);

        service.createSession(OWNER, 4096, req);

        InternalUploadIngestCommand inserted = captureInsert();
        assertThat(inserted.fileFmt()).isEqualTo("mpeg4");
        assertThat(inserted.fileSz()).isEqualTo(99L);
    }

    @Test
    @DisplayName("출처유형을_폼에서_고르면_그_값이_인입행에_실린다 — 미지정이면_USER_ULD")
    void srcTypeIsSelectableAndDefaultsToUserUpload() {
        service.createSession(OWNER, 10, request("VMS-1", "CCTV-1", null, null, null, "RELAY"));
        assertThat(captureInsert().srcType()).isEqualTo("RELAY");
    }

    @Test
    @DisplayName("출처유형이_allowlist_밖이면_세션생성시_400 — 적재와_동일_목록으로_판정")
    void unknownSrcTypeRejectedOnCreate() {
        assertThatThrownBy(() -> service.createSession(OWNER, 10,
                request("VMS-1", "CCTV-1", null, null, null, "HACKED")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(ingestWriter, never()).insertPending(any(InternalUploadIngestCommand.class));
    }

    @Test
    @DisplayName("CCTV_제원과_이벤트_관제일지가_인입행_29컬럼에_그대로_실린다")
    void manualMetaIsCarriedIntoIngestRow() {
        InternalUploadCreateRequest req = new InternalUploadCreateRequest(
                "clip.mp4", "VMS-1", "CCTV-1", null, "1168000000", null,
                null, null, null, "서울특별시 강남구",
                null, null, null, null, null, null, null, null, null,
                new BigDecimal("37.4979200"), new BigDecimal("127.0276100"),
                "OG-01", "강남대로 CCTV", new BigDecimal("4.5"), 180,
                "ABA_0001", "차량 정체", "12시 정체 관측");

        service.createSession(OWNER, 10, req);

        InternalUploadIngestCommand inserted = captureInsert();
        assertThat(inserted.lclgvNm()).isEqualTo("서울특별시 강남구");
        assertThat(inserted.wgs84Lat()).isEqualByComparingTo("37.4979200");
        assertThat(inserted.wgs84Lot()).isEqualByComparingTo("127.0276100");
        assertThat(inserted.ogCd()).isEqualTo("OG-01");
        assertThat(inserted.cctvNm()).isEqualTo("강남대로 CCTV");
        assertThat(inserted.cctvHgt()).isEqualByComparingTo("4.5");
        assertThat(inserted.mainSurvPanAng()).isEqualTo(180);
        assertThat(inserted.evntId()).isEqualTo("ABA_0001");
        assertThat(inserted.evntNm()).isEqualTo("차량 정체");
        assertThat(inserted.mntrCn()).isEqualTo("12시 정체 관측");
    }

    @Test
    @DisplayName("Phase1_적재는_인입경로가_담당한다 — 업로드가_VideoIngestedEvent를_직접_발행하지_않는다")
    void doesNotPublishVideoIngestedEvent() {
        // given — 8바이트 단일 청크로 완료
        byte[] full = withMp4Head(8);
        UUID id = service.createSession(OWNER, 8, minimal("VMS-1"));

        // when
        var r = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);

        // then — 완료됐지만 LS_DATA_RAW 생성이 없다(=이벤트를 발행할 rawSn 자체가 없다)
        assertThat(r.completed()).isTrue();
        verify(videoRepository, never()).save(any(LsDataRaw.class));

        // then — ★구조 가드: 발행 통로(ApplicationEventPublisher) 자체를 보유하지 않는다.
        //   비식별 선두 트리거는 인입 적재(TrainingVideoIngestTx)가 커밋과 함께 발행해야 하며,
        //   업로드가 먼저 발행하면 <인입 행 없이 적재된 영상>이 생겨 두 경로가 갈라진다.
        assertThat(Arrays.stream(TusUploadService.class.getDeclaredFields())
                .map(Field::getType))
                .as("업로드는 이벤트 발행 통로를 갖지 않는다")
                .doesNotContain(ApplicationEventPublisher.class);
    }

    @Test
    @DisplayName("완료시_임시파일이_인입영역으로_이동하고_세션FILE_PATH는_임시경로로_남는다")
    void movesTempFileIntoIngestArea() {
        // given
        byte[] full = withMp4Head(8);
        UUID id = service.createSession(OWNER, 8, minimal("VMS-1"));
        String tempPath = repository.findById(id).orElseThrow().getFilePath();

        // when
        service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);

        // then — 파일은 인입 영역으로 이동했고 임시 경로에는 남지 않는다
        Path expected = pathResolver.resolveUploadTarget("VMS-1", "mp4");
        assertThat(Files.exists(expected)).isTrue();
        assertThat(Files.exists(Path.of(tempPath))).isFalse();

        // then — ★세션 FILE_PATH 는 <임시 경로 그대로>다. NAS 경로로 갱신하면 완료 전이가 유실된
        //   세션을 24h 뒤 TusUploadCleanupJob 이 스윕할 때 <인입 완료된 원본>을 지운다
        //   (정리 가드가 raw-path 하위만 보므로 새 경로도 그대로 통과해 버린다).
        assertThat(repository.findById(id).orElseThrow().getFilePath()).isEqualTo(tempPath);
    }

    // ======================== 취소 — 인입 행 종결 ========================

    @Test
    @DisplayName("업로드_취소시_인입행이_FAILED로_종결된다 — 24시간_미도착_재시도로_쌓이지_않게")
    void cancelTerminatesPendingIngestRow() {
        UUID id = service.createSession(OWNER, 10, minimal("VMS-1"));
        long rcptnSn = rcptnSnOf("VMS-1");

        service.cancel(id, OWNER);

        // then — 세션·임시파일 정리 + 인입 행 종결(사유 포함)
        assertThat(repository.findById(id)).isEmpty();
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(ingestRepository).terminatePendingUpload(
                eq(rcptnSn), reason.capture(), any(LocalDateTime.class));
        assertThat(reason.getValue()).contains("취소");
    }

    @Test
    @DisplayName("이미_완료된_세션을_취소해도_인입행은_건드리지_않는다 — 적재대기중인_영상_보호")
    void cancelAfterCompletionDoesNotTerminateIngestRow() {
        byte[] full = withMp4Head(8);
        UUID id = service.createSession(OWNER, 8, minimal("VMS-1"));
        service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);

        service.cancel(id, OWNER);

        // then — 완료된 업로드의 인입 행을 취소가 뒤늦게 죽이면 정상 영상이 사라진다
        verify(ingestRepository, never()).terminatePendingUpload(anyLong(), anyString(), any());
    }

    // ======================== HIGH-1: 동시 PATCH 오프셋 충돌 ========================

    @Test
    @DisplayName("HIGH1_동시PATCH_낙관적잠금충돌시_409")
    void concurrentPatchConflict() {
        // given — saveAndFlush 시 낙관적 잠금 예외를 던지는 리포지토리
        repository = new InMemoryRepo() {
            @Override
            public <S extends LsTusUpload> S saveAndFlush(S entity) {
                throw new OptimisticLockingFailureException("version conflict");
            }
        };
        service = newService();
        byte[] full = withMp4Head(10);
        UUID id = service.createSession(OWNER, 10, minimal("VMS-1"));

        // when/then — 동시 충돌 → 409
        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(full, 0, 5), 5))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    // ======================== HIGH-2: Upload-Length 초과 413 ========================

    @Test
    @DisplayName("HIGH2_UploadLength가_maxFileSize초과시_413")
    void uploadLengthTooLarge() {
        assertThatThrownBy(() -> service.createSession(OWNER, MAX_SIZE + 1, minimal("VMS-1")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        // 413 이면 인입 행도 남지 않는다(대기열 오염 방지)
        verify(ingestWriter, never()).insertPending(any(InternalUploadIngestCommand.class));
    }

    // ======================== HIGH-3: 완료 중복 멱등 ========================

    @Test
    @DisplayName("HIGH3_마지막청크_재전송시_멱등응답_인입행1건")
    void duplicateCompletionIdempotent() {
        // 매직바이트(ftyp, 8바이트) 통과를 위해 8바이트 단일 청크로 완료.
        byte[] full = withMp4Head(8);
        UUID id = service.createSession(OWNER, 8, minimal("VMS-1"));
        var first = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);
        assertThat(first.completed()).isTrue();

        // when — 마지막 청크 재전송 (이미 완료)
        var again = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);

        // then — 멱등: 완료 응답 + 인입 행은 <1건>만 생성됐다(세션 생성 시점 1회)
        assertThat(again.completed()).isTrue();
        verify(ingestWriter, times(1)).insertPending(any(InternalUploadIngestCommand.class));
    }

    // ======================== HIGH-5: TTL 만료 410 ========================

    @Test
    @DisplayName("HIGH5_만료세션_HEAD시_410")
    void expiredHeadGone() throws Exception {
        UUID id = service.createSession(OWNER, 10, minimal("VMS-1"));
        forceExpire(repository.findById(id).orElseThrow());

        assertThatThrownBy(() -> service.getForOwner(id, OWNER))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.GONE);
    }

    @Test
    @DisplayName("HIGH5_만료세션_PATCH시_410")
    void expiredPatchGone() throws Exception {
        byte[] full = withMp4Head(10);
        UUID id = service.createSession(OWNER, 10, minimal("VMS-1"));
        forceExpire(repository.findById(id).orElseThrow());

        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(full, 0, 5), 5))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.GONE);
    }

    // ======================== HIGH-6: 매직바이트 검증 ========================

    @Test
    @DisplayName("HIGH6_완료시_매직바이트불일치_임시파일삭제후_409_이면서_인입행도_종결된다")
    void magicByteMismatch() {
        // given — mp4 헤더 없는 10바이트 (텍스트)
        byte[] bogus = new byte[10];
        Arrays.fill(bogus, (byte) 'X');
        UUID id = service.createSession(OWNER, 10, minimal("VMS-1"));
        long rcptnSn = rcptnSnOf("VMS-1");

        // when/then — 완료 시 매직바이트 검증 실패 → 409
        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(bogus), 10))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // then — 임시 파일 삭제됨 + 추가 INSERT 없음
        String filePath = repository.findById(id).orElseThrow().getFilePath();
        assertThat(Files.exists(Path.of(filePath))).isFalse();
        verify(ingestWriter, times(1)).insertPending(any(InternalUploadIngestCommand.class));

        // then — ★인입 행은 파일이 영영 오지 않으므로 즉시 종결한다(24시간 대기 방지)
        verify(ingestRepository).terminatePendingUpload(
                eq(rcptnSn), anyString(), any(LocalDateTime.class));
    }

    // ======================== HIGH-7: 경로 순회 — UUID 저장 강제 ========================

    @Test
    @DisplayName("HIGH7_filename경로순회시도_저장은_UUID강제_storage내부")
    void pathTraversalForcedUuid() {
        // given — 경로 순회 시도 파일명
        InternalUploadCreateRequest evil = new InternalUploadCreateRequest(
                "../../../etc/passwd.mp4", "VMS-2", "CCTV-1", null, "1168000000", null,
                null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        UUID id = service.createSession(OWNER, 10, evil);

        // then — 저장 경로는 storage/tus-uploads 내부 + 파일명은 uploadId UUID
        String filePath = repository.findById(id).orElseThrow().getFilePath();
        assertThat(filePath).contains("tus-uploads");
        assertThat(filePath).contains(id.toString());
        assertThat(filePath).doesNotContain("etc/passwd");
        assertThat(Path.of(filePath).normalize().startsWith(storageDir.toAbsolutePath().normalize())).isTrue();
    }

    // ======================== HIGH-8: 세션 소유자 검증 ========================

    @Test
    @DisplayName("HIGH8_타인세션_PATCH시_403")
    void nonOwnerPatchForbidden() {
        byte[] full = withMp4Head(10);
        UUID id = service.createSession(OWNER, 10, minimal("VMS-1"));

        assertThatThrownBy(() -> service.appendChunk(id, "intruder", 0,
                new ByteArrayInputStream(full, 0, 5), 5))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("HIGH8_타인세션_HEAD시_403")
    void nonOwnerHeadForbidden() {
        UUID id = service.createSession(OWNER, 10, minimal("VMS-1"));
        assertThatThrownBy(() -> service.getForOwner(id, "intruder"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("HIGH8_타인세션_DELETE시_403_이면서_인입행도_건드리지_않는다")
    void nonOwnerCancelForbidden() {
        UUID id = service.createSession(OWNER, 10, minimal("VMS-1"));

        assertThatThrownBy(() -> service.cancel(id, "intruder"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(ingestRepository, never()).terminatePendingUpload(anyLong(), anyString(), any());
    }

    // ======================== HIGH-9: offset/경계/상한 ========================

    @Test
    @DisplayName("HIGH9_Offset불일치시_409")
    void offsetMismatchConflict() {
        byte[] full = withMp4Head(10);
        UUID id = service.createSession(OWNER, 10, minimal("VMS-1"));
        // 서버 offset 은 0 인데 5 로 보냄
        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 5,
                new ByteArrayInputStream(full, 0, 5), 5))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("HIGH9_음수Offset시_400")
    void negativeOffsetBadRequest() {
        byte[] full = withMp4Head(10);
        UUID id = service.createSession(OWNER, 10, minimal("VMS-1"));
        assertThatThrownBy(() -> service.appendChunk(id, OWNER, -1,
                new ByteArrayInputStream(full, 0, 5), 5))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("HIGH9_초과Offset시_400")
    void overLengthOffsetBadRequest() {
        byte[] full = withMp4Head(10);
        UUID id = service.createSession(OWNER, 10, minimal("VMS-1"));
        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 11,
                new ByteArrayInputStream(full, 0, 5), 5))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("HIGH9_ContentLength0_빈청크시_400")
    void emptyChunkBadRequest() {
        UUID id = service.createSession(OWNER, 10, minimal("VMS-1"));
        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(new byte[0]), 0))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("HIGH9_사용자별_동시IN_PROGRESS3개초과시_429")
    void tooManyConcurrentSessions() {
        service.createSession(OWNER, 10, minimal("VMS-A"));
        service.createSession(OWNER, 10, minimal("VMS-B"));
        service.createSession(OWNER, 10, minimal("VMS-C"));
        // 4번째 → 429
        assertThatThrownBy(() -> service.createSession(OWNER, 10, minimal("VMS-D")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        // 상한 초과분은 인입 행도 남기지 않는다
        verify(ingestWriter, times(3)).insertPending(any(InternalUploadIngestCommand.class));
    }

    // ======================== HIGH-2: 청크당 크기 상한 413 ========================

    @Test
    @DisplayName("HIGH2_단일청크가_maxChunkBytes초과시_413_truncate롤백")
    void chunkExceedsMaxChunkBytes() {
        // given — 청크 상한 8바이트로 서비스 구성, 10바이트 단일 청크 전송
        TusUploadService capped = new TusUploadService(repository, videoRepository, ingestRepository,
                ingestWriter, pathResolver, ingestTerminator, storageDir.toString(),
                MAX_SIZE, 8L,
                path -> 60);
        byte[] full = withMp4Head(10);
        UUID id = capped.createSession(OWNER, 10, minimal("VMS-1"));

        // when/then — Content-Length 선검증 단계에서 413
        assertThatThrownBy(() -> capped.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(full, 0, 10), 10))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);

        // then — offset 미전진 (파일 오염 없음)
        assertThat(repository.findById(id).orElseThrow().getUploadOffset()).isZero();
    }

    @Test
    @DisplayName("HIGH2_상한이내청크_64KB버퍼스트리밍_정상기록")
    void chunkWithinCapStreamsOk() {
        // given — 상한 32바이트, 8바이트 mp4 단일 청크 → 완료
        TusUploadService capped = new TusUploadService(repository, videoRepository, ingestRepository,
                ingestWriter, pathResolver, ingestTerminator, storageDir.toString(),
                MAX_SIZE, 32L,
                path -> 60);
        byte[] full = withMp4Head(8);
        UUID id = capped.createSession(OWNER, 8, minimal("VMS-1"));

        var r = capped.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);

        assertThat(r.completed()).isTrue();
        assertThat(r.rcptnSn()).isNotNull();
    }

    // ======================== MED-1: 완료 DB 조건부 전이 멱등 ========================

    @Test
    @DisplayName("MED1_완료전이가_이미_다른트랜잭션에_선점됐으면_재시도예정을_다시_당기지_않는다")
    void doesNotTouchIngestWhenCompletionAlreadyClaimed() {
        // given — 완료 전이(조건부 UPDATE)가 항상 0행을 반환하는 리포지토리(= 다른 트랜잭션이 선점)
        repository = new InMemoryRepo() {
            @Override
            public int markCompletedIfInProgress(UUID uploadId, Long rawSn, LocalDateTime now) {
                return 0;
            }
        };
        service = newService();
        byte[] full = withMp4Head(8);
        UUID id = service.createSession(OWNER, 8, minimal("VMS-1"));

        // when
        var r = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);

        // then — 전이 책임이 없으므로 인입 행을 건드리지 않는다(선점한 쪽이 이미 처리했다)
        assertThat(r.completed()).isTrue();
        verify(ingestRepository, never()).markUploadArrived(anyLong(), any());

        // then — ★Phase 3: 옮긴 파일은 <회수하지 않는다>. 그 파일을 가리키는 인입 행이 이미 있고
        //   (세션 생성 시점 INSERT), 지우면 폴링이 영원히 미도착 대기하다 24시간 뒤 FAILED 가 된다.
        //   Phase 1 의 "고아 파일" 문제는 인입 행이 먼저 생기면서 구조적으로 사라졌다.
        assertThat(Files.exists(pathResolver.resolveUploadTarget("VMS-1", "mp4")))
                .as("인입 행이 가리키는 파일을 지우면 그 인입은 영영 적재되지 않는다")
                .isTrue();
    }

    // ======================== F1: 쓰기 직전 TOCTOU 재판정 (CWE-59/367) ========================

    @Test
    @DisplayName("F1_인입영역_경로가_심링크로_교체되면_쓰기직전_재판정이_거부하고_allowlist밖에_기록하지_않는다")
    void toctouDirectorySymlinkIsRejectedBeforeWrite() throws Exception {
        // given — 기동 시점에는 정상인 형상(인입 영역이 적재 allowlist 하위)에서 리졸버를 만든다.
        Path allowRoot = Files.createDirectories(storageDir.resolve("toctou").resolve("raw"));
        Path outside = Files.createDirectories(storageDir.resolve("toctou").resolve("outside"));
        Files.createDirectories(allowRoot.resolve("data").resolve("upload").resolve("v2"));
        pathResolver = new InternalUploadPathResolver(
                ArtifactRootTestSupport.coLocate(allowRoot), allowRoot.toString());
        assertThat(pathResolver.baseUnderAllowedRoots()).isTrue();
        service = newService();
        UUID id = service.createSession(OWNER, 8, minimal("VMS-1"));

        // given — 기동 이후 경로 <중간 디렉터리>를 allowlist 밖을 가리키는 심링크로 교체한다.
        //   (파일 레벨 심링크로는 탈출 불가 — rename(2) 는 최종 컴포넌트 링크를 따라가지 않는다.)
        deleteRecursively(allowRoot.resolve("data"));
        Files.createSymbolicLink(allowRoot.resolve("data"), outside);

        // when/then — 쓰기 직전 재판정이 <고정 allowlist> 기준이라 거부된다.
        //   구 구현은 target.getParent() 를 uploadDir(=자기 자신) 기준으로 검사하는 항등식이라
        //   여기서 통과하고 비식별 전 원본을 allowlist 밖에 기록했다.
        byte[] full = withMp4Head(8);
        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(full, 0, 8), 8))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        // then — ★allowlist 밖 트리에 영상 파일이 하나도 기록되지 않았다
        try (var walk = Files.walk(outside)) {
            assertThat(walk.filter(Files::isRegularFile).toList())
                    .as("비식별 전 원본이 적재 allowlist 밖에 기록되면 안 된다")
                    .isEmpty();
        }
    }

    // ======================== 세션 생성 실패 시 보상 ========================

    @Test
    @DisplayName("세션생성중_인입INSERT가_UK위반이면_409_이면서_임시파일을_남기지_않는다")
    void ingestInsertUniqueViolationOnCreateCleansTempFile() {
        // given — 사전 조회 통과 이후 같은 클립이 인입된 race (UK 위반, SQLState 23505)
        when(ingestWriter.insertPending(any(InternalUploadIngestCommand.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key",
                        new SQLException("duplicate key value violates unique constraint", "23505")));

        assertThatThrownBy(() -> service.createSession(OWNER, 8, minimal("VMS-1")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // then — 0바이트 임시 파일이 남지 않는다(스토리지 누수 + 정리 잡 대상 오염 방지)
        assertThat(listTempFiles()).isEmpty();
    }

    @Test
    @DisplayName("F6_UK위반이_아닌_무결성오류를_클립ID중복_409로_오진단하지_않는다")
    void nonUniqueIntegrityViolationIsNotReportedAsDuplicate() {
        // given — NOT NULL 위반(23502) 등은 원인이 전혀 다르다. 409 로 덮으면 원인 규명이 막힌다.
        when(ingestWriter.insertPending(any(InternalUploadIngestCommand.class)))
                .thenThrow(new DataIntegrityViolationException("not-null violation",
                        new SQLException("null value in column violates not-null constraint", "23502")));

        assertThatThrownBy(() -> service.createSession(OWNER, 8, minimal("VMS-1")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(listTempFiles()).isEmpty();
    }

    @Test
    @DisplayName("R1_매직바이트_검증실패시_세션이_별도트랜잭션_통로로_종결된다 — 롤백에_휩쓸리지_않는다")
    void magicByteFailureTerminatesSessionOutOfBand() {
        // given — 종결이 <별도 트랜잭션 통로>(terminateSession)를 탔는지 기록하는 리포지토리.
        //   ★상태(EXPIRED)만 단언하면 "같은 트랜잭션에서 markExpired + save" 로 되돌리는 회귀를
        //     잡지 못한다 — in-memory 에는 롤백이 없어 그 구현도 EXPIRED 로 보이기 때문이다.
        //     실 트랜잭션에서는 그 쓰기가 직후 예외로 함께 롤백돼 세션이 IN_PROGRESS 로 부활한다.
        //     그래서 결과 상태와 <통로> 를 함께 단언한다(롤백 생존의 실증은 InternalUploadIngestFlowIT).
        java.util.List<UUID> terminatedVia = new java.util.ArrayList<>();
        repository = new InMemoryRepo() {
            @Override
            public int terminateSession(UUID uploadId, LocalDateTime now) {
                terminatedVia.add(uploadId);
                return super.terminateSession(uploadId, now);
            }
        };
        service = newService();
        byte[] bogus = new byte[10];
        Arrays.fill(bogus, (byte) 'X');
        UUID id = service.createSession(OWNER, 10, minimal("VMS-1"));

        // when/then — 매직바이트 검증 실패 → 409
        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(bogus), 10))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // then — ★임시 파일은 이미 지워졌는데(비가역) 이 트랜잭션은 롤백된다. 종결이 없으면 세션이
        //   IN_PROGRESS 로 부활해 재시도가 <사라진 임시 파일>로 향한다. 별도 트랜잭션 통로 필수.
        assertThat(terminatedVia)
                .as("종결은 REQUIRES_NEW 통로(terminateSession)를 타야 롤백에 살아남는다")
                .containsExactly(id);

        // then — 결과 상태: 만료 종결이라 이후 HEAD/PATCH 는 410 이다(재개 불가 세션 정리)
        LsTusUpload session = repository.findById(id).orElseThrow();
        assertThat(session.getStatus()).isEqualTo(LsTusUpload.STATUS_EXPIRED);
        assertThat(session.isExpired(LocalDateTime.now().plusSeconds(1))).isTrue();
    }

    // ======================== F3: 파일명 선점의 원자성 (CWE-362/367) ========================

    @Test
    @DisplayName("F3_대상파일이_이미_있으면_덮어쓰지_않고_409 — 먼저_확정된_실체_보존")
    void existingIngestTargetIsPreserved() throws Exception {
        // given — 같은 vmsClipId 의 파일이 이미 인입 영역에 확정돼 있다
        Path target = pathResolver.resolveUploadTarget("VMS-1", "mp4");
        Files.createDirectories(target.getParent());
        Files.writeString(target, FIRST_WRITER_VIDEO);
        byte[] full = withMp4Head(8);
        UUID id = service.createSession(OWNER, 8, minimal("VMS-1"));

        // when/then — 409 (조용한 대체 금지)
        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(full, 0, 8), 8))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        assertThat(Files.readString(target)).isEqualTo(FIRST_WRITER_VIDEO);
    }

    @Test
    @DisplayName("F3후속_같은_clipId의_두번째_세션은_입구에서_409 — 인입_UK를_첫_세션이_선점한다")
    void concurrentSameClipCompletionIsPreventedByIngestUniqueKey() {
        // given — 인입 행이 <세션 생성 시점>에 생기고 VMS_CLIP_ID 가 UK 라 두 번째 세션은
        //   보통 만들어지지 않는다. ★단 이것은 <1선>일 뿐 원자성의 대체물이 아니다 —
        //   미도착 상한 종결이 세션을 남기는 비대칭 등으로 두 세션이 살아날 수 있고,
        //   그 경우의 파일 대체는 O_EXCL 예약만이 막는다(아래 F3 원자성 테스트).
        service.createSession(OWNER, 8, minimal("VMS-1"));

        // when/then — 두 번째 세션은 인입 UK 선점으로 입구에서 409
        assertThatThrownBy(() -> service.createSession(OWNER, 8, minimal("VMS-1")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // then — 인입 행은 첫 세션의 1건뿐이라 "두 파일이 같은 이름을 노리는" 상황 자체가 없다
        verify(ingestWriter, times(1)).insertPending(any(InternalUploadIngestCommand.class));
    }

    @Test
    @DisplayName("F3_예약~이동_사이에_끼어든_다른_쓰기주체는_이름을_얻지_못한다 — 원자_예약이_없으면_FAIL")
    void ingestTargetNameIsReservedAtomicallyBeforeMove() throws Exception {
        // given — 경합을 <예약 이후>에 주입한다. 구 구현(Files.exists 검사 후 이동)은 이 지점에
        //   방어가 전혀 없어 rival 이 만든 파일을 ATOMIC_MOVE 가 조용히 대체했다(양쪽 다 성공).
        //   ★seam 이 <예약 통로 자체>라 예약을 없애면 rivalRejected 는커녕 seam 이 돌지도 않는다.
        java.util.concurrent.atomic.AtomicInteger reserveCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean rivalRejected =
                new java.util.concurrent.atomic.AtomicBoolean();
        pathResolver = new InternalUploadPathResolver(
                ArtifactRootTestSupport.coLocate(storageDir), storageDir.toString()) {
            @Override
            public void reserveIngestTarget(Path target) throws java.io.IOException {
                super.reserveIngestTarget(target);
                reserveCalls.incrementAndGet();
                // rival = 같은 규약(원자 배타 생성)을 따르는 다른 쓰기 주체.
                try {
                    Files.createFile(target);
                    Files.writeString(target, FIRST_WRITER_VIDEO);
                } catch (java.nio.file.FileAlreadyExistsException e) {
                    rivalRejected.set(true);
                }
            }
        };
        service = newService();
        byte[] full = withMp4Head(8);
        UUID id = service.createSession(OWNER, 8, minimal("VMS-1"));

        // when — 업로드 완료
        var result = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);

        // then — ①예약 통로를 실제로 탔고 ②그 순간 이름이 이미 선점돼 rival 이 졌다
        assertThat(result.completed()).isTrue();
        assertThat(reserveCalls)
                .as("이름 선점이 원자 예약 통로를 거치지 않으면(검사 후 사용) 이 seam 자체가 돌지 않는다")
                .hasValue(1);
        assertThat(rivalRejected.get())
                .as("예약~이동 창에서 다른 주체가 같은 이름을 배타 생성할 수 있으면 원자성이 없는 것이다")
                .isTrue();

        // then — 최종 파일은 우리 업로드 내용이고, rival 의 내용이 섞이지 않았다
        Path target = pathResolver.resolveUploadTarget("VMS-1", "mp4");
        assertThat(Files.readAllBytes(target)).isEqualTo(Arrays.copyOf(full, 8));
    }

    @Test
    @DisplayName("A_미도착상한_종결로_행만_죽고_세션이_살아있으면_되살리지_않고_409 — 두_세션_동시생존_차단")
    void revivalIsBlockedWhileSessionStillAlive() {
        // given — 미도착 대기 상한 초과로 <인입 행만> FAILED 가 됐다(TrainingVideoIngestTx 는
        //   세션을 모른다). 세션 S1 은 여전히 IN_PROGRESS 로 살아 있다.
        service.createSession(OWNER, 10, minimal("VMS-1"));
        setIngestState(ingestRows.get("VMS-1"), LsDataIngest.PRCS_STTS_FAILED);

        // when/then — 같은 clipId 로 S2 를 만들면 되살리기 3조건은 통과하지만 살아 있는 세션이 있다
        assertThatThrownBy(() -> service.createSession(OWNER, 10, minimal("VMS-1")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // then — 되살리기 UPDATE 자체가 일어나지 않았다(S1 메타가 S2 메타로 덮이지 않는다)
        verify(ingestWriter, never()).reviveForUpload(anyLong(), any(InternalUploadIngestCommand.class));
    }

    @Test
    @DisplayName("A_세션이_종결된_뒤에는_같은_clipId를_되살릴_수_있다 — 가드가_회수통로를_막지_않는다")
    void revivalIsAllowedOnceSessionIsGone() {
        // given — 세션을 취소해 세션·인입 행이 함께 종결됐다(정상 동선)
        UUID first = service.createSession(OWNER, 10, minimal("VMS-1"));
        service.cancel(first, OWNER);

        // when — 같은 clipId 재업로드
        UUID second = service.createSession(OWNER, 10, minimal("VMS-1"));

        // then — 되살리기가 정상 동작한다(가드는 <살아 있는 세션>만 막는다)
        assertThat(second).isNotNull();
        verify(ingestWriter, times(1)).insertPending(any(InternalUploadIngestCommand.class));
        verify(ingestWriter, times(1)).reviveForUpload(anyLong(), any(InternalUploadIngestCommand.class));
    }

    @Test
    @DisplayName("E_PROCESSING이_곧_PENDING으로_풀리면_도착통지를_재시도로_회수한다 — 구조적_불가가_아니다")
    void arrivalResetIsRecoveredByBoundedRetry() {
        // given — 완료 직전 폴링이 클레임(PROCESSING)했고, 곧(수 ms) 미도착 복귀가 커밋된다.
        UUID id = service.createSession(OWNER, 8, minimal("VMS-1"));
        LsDataIngest row = ingestRows.get("VMS-1");
        setIngestState(row, LsDataIngest.PRCS_STTS_PROCESSING);
        java.util.concurrent.atomic.AtomicInteger arrivedCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        when(ingestRepository.markUploadArrived(anyLong(), any(LocalDateTime.class)))
                .thenAnswer(inv -> {
                    // 2번째 호출(= 재시도 1회차) 직전에 폴링의 복귀가 커밋된 형상
                    if (arrivedCalls.incrementAndGet() == 2) {
                        setIngestState(row, LsDataIngest.PRCS_STTS_PENDING);
                    }
                    return pendingRowBySn(inv.getArgument(0)) != null ? 1 : 0;
                });
        byte[] full = withMp4Head(8);

        // when
        var result = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);

        // then — 재시도로 backoff 리셋을 회수했다(파일은 그대로, 완료 응답)
        assertThat(result.completed()).isTrue();
        assertThat(arrivedCalls.get()).as("최초 1회 + bounded-retry").isGreaterThanOrEqualTo(2);
        verify(ingestRepository, org.mockito.Mockito.atLeast(2))
                .markUploadArrived(anyLong(), any(LocalDateTime.class));
        assertThat(Files.exists(pathResolver.resolveUploadTarget("VMS-1", "mp4"))).isTrue();
    }

    @Test
    @DisplayName("R2_이동이_실패하면_인입영역에_아무_파일도_남기지_않는다")
    void noResidueInIngestAreaWhenMoveFails() {
        // given — <이동만> 실패하는 상황. 프로덕션에 테스트 훅을 넣지 않기 위해 이미 존재하는
        //   seam(쓰기 직전 재판정)을 쓴다: verifyIngestable 시점에 임시 파일을 지우면 이어지는
        //   Files.move 가 NoSuchFileException(IOException)으로 실패한다.
        pathResolver = new InternalUploadPathResolver(
                ArtifactRootTestSupport.coLocate(storageDir), storageDir.toString()) {
            @Override
            public void verifyIngestable(Path target) {
                super.verifyIngestable(target);
                try {
                    Files.deleteIfExists(Path.of(
                            repository.findById(movingUploadId).orElseThrow().getFilePath()));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        };
        service = newService();
        byte[] full = withMp4Head(8);
        movingUploadId = service.createSession(OWNER, 8, minimal("VMS-1"));

        // when/then — 이동 실패는 500 으로 종결한다
        assertThatThrownBy(() -> service.appendChunk(movingUploadId, OWNER, 0,
                new ByteArrayInputStream(full, 0, 8), 8))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        // then — 대상 경로가 비어 있다. 미완성 파일이 남으면 폴링이 그걸 영상으로 적재한다.
        assertThat(Files.exists(pathResolver.resolveUploadTarget("VMS-1", "mp4")))
                .as("이동 실패 시 인입 영역에 잔여물이 없어야 한다")
                .isFalse();
    }

    // ======================== H1: 미완성 파일이 인입 경로에 노출되지 않는다 ========================

    @Test
    @DisplayName("H1_이동실패_회수까지_실패해도_인입경로에_남는_것은_0바이트_예약뿐이다 — 미완성_내용은_절대_노출되지_않는다")
    void onlyEmptyReservationCanSurviveWhenRecoveryFails() throws Exception {
        // given — 최악 형상: 이동이 실패하고(임시 파일 선삭제) 뒤이은 <회수마저> 실패한다.
        //   ★DEV_FIX 2차 [A] 로 O_EXCL 예약을 되살렸으므로 이 조합에서는 0바이트 예약분이
        //   잔존할 수 있다. 그것이 적재되지 않는 근거는 <적재 측 완결성 게이트>다
        //   (TrainingVideoIngestTxTest#H1_0바이트_파일은_아직_도착하지_않은_것으로_본다 — 크기 0 = NOT_ARRIVED).
        //   여기서 고정하는 불변식은 "잔존물이 <내용 있는 미완성 파일>은 결코 아니다" 다.
        pathResolver = new InternalUploadPathResolver(
                ArtifactRootTestSupport.coLocate(storageDir), storageDir.toString()) {
            private int calls;

            @Override
            public void verifyIngestable(Path target) {
                super.verifyIngestable(target);
                if (++calls > 1) {
                    // 2번째 호출 = 회수(discard) 경로 — 여기서 실패시켜 잔여물을 남긴다.
                    throw new CustomException(ErrorCode.INTERNAL_ERROR, "회수 실패 모사");
                }
                try {
                    Files.deleteIfExists(Path.of(
                            repository.findById(movingUploadId).orElseThrow().getFilePath()));
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        };
        service = newService();
        byte[] full = withMp4Head(8);
        movingUploadId = service.createSession(OWNER, 8, minimal("VMS-1"));

        assertThatThrownBy(() -> service.appendChunk(movingUploadId, OWNER, 0,
                new ByteArrayInputStream(full, 0, 8), 8))
                .isInstanceOf(CustomException.class);

        // then — ★잔존물이 있더라도 <0바이트 예약>뿐이다. 부분 복사본·미완성 내용이 그 경로에
        //   보이면 폴링이 그것을 정상 영상으로 적재한다(완결성 게이트는 크기 0 만 거른다).
        Path target = pathResolver.resolveUploadTarget("VMS-1", "mp4");
        assertThat(Files.exists(target))
                .as("이 형상에서는 예약분이 회수되지 못해 잔존한다(의도 — 게이트가 막는다)")
                .isTrue();
        assertThat(Files.size(target))
                .as("인입 경로에 내용이 든 미완성 파일이 남으면 폴링이 그것을 영상으로 적재한다")
                .isZero();
    }

    // ======================== H2: 도착 통지 0행 = 정상이 아니다 ========================

    @Test
    @DisplayName("H2_완료직전_인입행이_종결됐으면_옮긴_원본을_회수하고_성공을_반환하지_않는다")
    void terminatedIngestRowOnArrivalRecoversFileAndFails() {
        // given — 미도착 대기 상한 초과 등으로 그 사이 인입 행이 FAILED 로 종결됐다.
        //   이 행은 폴링 술어(PENDING)에서 빠져 <아무도 이 파일을 적재하지 않는다>.
        UUID id = service.createSession(OWNER, 8, minimal("VMS-1"));
        setIngestState(ingestRows.get("VMS-1"), LsDataIngest.PRCS_STTS_FAILED);
        byte[] full = withMp4Head(8);

        // when/then — 사용자에게 성공(204)을 주면 "올렸는데 영영 없는 영상"이 된다
        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(full, 0, 8), 8))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // then — ★주인 없는 <비식별 전 원본>을 인입 영역에 남기지 않는다(개인정보 보존기간)
        assertThat(Files.exists(pathResolver.resolveUploadTarget("VMS-1", "mp4")))
                .as("참조하는 살아 있는 인입 행이 없으면 그 파일은 아무도 지우지 않는 PII 원본이다")
                .isFalse();
    }

    @Test
    @DisplayName("H2_그사이_폴링이_집어간_PROCESSING이면_파일을_회수하지_않고_완료로_본다")
    void processingIngestRowOnArrivalKeepsFileAndSucceeds() {
        // given — 폴링이 먼저 클레임(PENDING→PROCESSING)했고 곧 미도착 복귀시킬 참이다.
        //   행은 <살아 있고> 파일도 제자리에 있으므로 회수하면 안 된다(정상 업로드 삭제).
        UUID id = service.createSession(OWNER, 8, minimal("VMS-1"));
        setIngestState(ingestRows.get("VMS-1"), LsDataIngest.PRCS_STTS_PROCESSING);
        byte[] full = withMp4Head(8);

        var r = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);

        assertThat(r.completed()).isTrue();
        assertThat(Files.exists(pathResolver.resolveUploadTarget("VMS-1", "mp4")))
                .as("살아 있는 인입 행이 가리키는 파일을 지우면 그 업로드는 영영 적재되지 않는다")
                .isTrue();
    }

    @Test
    @DisplayName("H2_그사이_이미_적재(DONE)됐으면_파일을_회수하지_않고_완료로_본다")
    void doneIngestRowOnArrivalKeepsFileAndSucceeds() {
        UUID id = service.createSession(OWNER, 8, minimal("VMS-1"));
        setIngestState(ingestRows.get("VMS-1"), LsDataIngest.PRCS_STTS_DONE);
        byte[] full = withMp4Head(8);

        var r = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);

        assertThat(r.completed()).isTrue();
        // 적재된 LS_DATA_RAW 가 이 경로를 가리킨다 — 지우면 비식별이 열 파일이 사라진다
        assertThat(Files.exists(pathResolver.resolveUploadTarget("VMS-1", "mp4"))).isTrue();
    }

    // ======================== H2-b: 취소가 도착한 파일의 행을 죽이지 않는다 ========================

    @Test
    @DisplayName("H2b_파일이_이미_인입영역에_도착했으면_취소가_인입행을_종결하지_않는다")
    void cancelKeepsIngestRowWhenFileAlreadyArrived() throws Exception {
        // given — 완료 트랜잭션이 파일 이동 뒤 롤백돼 세션은 IN_PROGRESS 인데 파일은 도착한 형상.
        //   여기서 행만 종결하면 <행은 죽고 파일은 남는> 동일 PII 고아가 된다.
        UUID id = service.createSession(OWNER, 10, minimal("VMS-1"));
        Path target = pathResolver.resolveUploadTarget("VMS-1", "mp4");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "arrived-video");

        service.cancel(id, OWNER);

        // then — 파일 실재가 세션 플래그보다 신뢰도 높은 진실원이다: 종결하지 않는다
        verify(ingestRepository, never()).terminatePendingUpload(anyLong(), anyString(), any());
        assertThat(Files.exists(target)).isTrue();
        assertThat(ingestRows.get("VMS-1").getPrcsSttsCd())
                .isEqualTo(LsDataIngest.PRCS_STTS_PENDING);
    }

    // ======================== M1: 종결된 clipId 회수(행 재사용) ========================

    @Test
    @DisplayName("M1_취소로_종결된_클립ID는_다시_업로드할_수_있다 — 행을_지우지_않고_되살린다")
    void cancelledClipIdIsReusable() {
        // given — 취소하면 인입 행이 FAILED 로 종결되고 파일은 한 번도 도착하지 않았다.
        //   구 구현은 이 행이 UK 를 영구 점유해 같은 클립 ID 를 <어떤 API 로도> 회수할 수 없었다
        //   (requeueFailedForRetry 는 상태만 되돌릴 뿐 UK 를 풀지 못한다).
        UUID first = service.createSession(OWNER, 10, minimal("VMS-1"));
        service.cancel(first, OWNER);

        // when — 같은 클립 ID 로 재업로드
        UUID second = service.createSession(OWNER, 10, minimal("VMS-1"));

        // then — 세션이 다시 만들어지고, 인입 행은 <새로 INSERT 되지 않는다>(영구 보존 + UK 유지)
        assertThat(second).isNotNull();
        verify(ingestWriter, times(1)).insertPending(any(InternalUploadIngestCommand.class));
        assertThat(ingestRows.get("VMS-1").getPrcsSttsCd())
                .as("되살린 행은 다시 폴링 대상(PENDING)이어야 한다")
                .isEqualTo(LsDataIngest.PRCS_STTS_PENDING);
    }

    @Test
    @DisplayName("M1_관제행_기적재행_파일도착행은_되살리지_않고_409 — 신뢰경계_밖_행_보호")
    void foreignOrIngestedIngestRowIsNotRevived() throws Exception {
        // ① 관제가 넣은 행 — 경로가 우리 인입 영역 밖이다(FAILED 여도 건드리지 않는다)
        //   ★SQL 술어(FAILED + RAW_SN IS NULL)는 이 행에도 <맞는다>. 구분하는 것은 오직 Java 측
        //     경로 판정(isUploadAreaPath)이므로, 되살리기 UPDATE 가 아예 불리지 않아야 한다.
        seedForeignIngestRow("VMS-CTRL", "/nas/control/clip.mp4",
                LsDataIngest.PRCS_STTS_FAILED, null);
        assertConflictOnCreate("VMS-CTRL");
        verify(ingestWriter, never()).reviveForUpload(anyLong(), any(InternalUploadIngestCommand.class));

        // ② 이미 적재된 행 — RAW_SN 이 있으면 되살리기 대상이 아니다
        seedForeignIngestRow("VMS-DONE",
                pathResolver.resolveUploadTarget("VMS-DONE", "mp4").toString(),
                LsDataIngest.PRCS_STTS_DONE, 42L);
        assertConflictOnCreate("VMS-DONE");

        // ③ 종결됐지만 <파일이 도착해 있는> 행 — 그 파일의 주인을 지우면 고아가 된다
        Path arrived = pathResolver.resolveUploadTarget("VMS-FILE", "mp4");
        Files.createDirectories(arrived.getParent());
        Files.writeString(arrived, "arrived-video");
        seedForeignIngestRow("VMS-FILE", arrived.toString(), LsDataIngest.PRCS_STTS_FAILED, null);
        assertConflictOnCreate("VMS-FILE");
    }

    private void assertConflictOnCreate(String clipId) {
        assertThatThrownBy(() -> service.createSession(OWNER, 10, minimal(clipId)))
                .as("clipId=%s", clipId)
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    /** R2 seam 이 "현재 이동 중인 세션"의 임시 경로를 찾기 위한 참조(테스트 전용). */
    private UUID movingUploadId;

    // ======================== 입력 검증 (CWE-20) ========================

    @Test
    @DisplayName("F6_cctvId가_allowlist_밖이면_세션생성시_400 — 인입_VMS_CCTV_ID_는_VARCHAR64")
    void invalidCctvIdRejectedOnCreate() {
        // given — 구 구현은 공백 여부만 봐서 65자 이상이 완료 시점 INSERT 에서 500 으로 터졌고
        //   0바이트 임시 파일이 남았다. 세션 생성 단에서 fail-fast 한다.
        for (String evil : new String[]{"A".repeat(65), "cctv/../etc", "cctv id", "cctv$1", "씨씨티비"}) {
            assertThatThrownBy(() -> service.createSession(OWNER, 10,
                    request("VMS-C-" + evil.length(), evil, null, null, null, null)))
                    .as("cctvId=%s", evil)
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
        // 경계 — 64자는 통과한다
        assertThat(service.createSession(OWNER, 10,
                request("VMS-C-OK", "A".repeat(64), null, null, null, null))).isNotNull();
    }

    @Test
    @DisplayName("LOW_lclgvCd_숫자아님_세션생성시_400")
    void invalidLocalGovCdRejectedOnCreate() {
        InternalUploadCreateRequest req = new InternalUploadCreateRequest(
                "clip.mp4", "VMS-G", "CCTV-1", null, "11A8", null,
                null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> service.createSession(OWNER, 10, req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("Phase1_vmsClipId가_없으면_세션생성시_400 — 인입_NOT_NULL_멱등키다")
    void blankVmsClipIdRejectedOnCreate() {
        assertThatThrownBy(() -> service.createSession(OWNER, 10,
                request("  ", "CCTV-1", null, null, null, null)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("Phase1_vmsClipId가_경로문자를_포함하면_세션생성시_400 — 저장 파일명이 된다")
    void pathLikeVmsClipIdRejectedOnCreate() {
        for (String evil : new String[]{"../etc/passwd", "a/b", "a\\b", "clip id", "A".repeat(65)}) {
            assertThatThrownBy(() -> service.createSession(OWNER, 10,
                    request(evil, "CCTV-1", null, null, null, null)))
                    .as("clipId=%s", evil)
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
        verify(ingestWriter, never()).insertPending(any(InternalUploadIngestCommand.class));
    }

    @Test
    @DisplayName("Phase1_같은_vmsClipId의_인입행이_처리중이면_세션생성시_409")
    void duplicateIngestRowRejectedOnCreate() {
        // given — 아직 살아 있는(PENDING) 인입 행은 UK 를 점유한다. 되살리기 대상이 아니다.
        seedForeignIngestRow("VMS-1", "/nas/control/clip.mp4", LsDataIngest.PRCS_STTS_PENDING, null);

        assertThatThrownBy(() -> service.createSession(OWNER, 10, minimal("VMS-1")))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(ingestWriter, never()).insertPending(any(InternalUploadIngestCommand.class));
    }

    @Test
    @DisplayName("Phase1_임의의_CCTV_ID_여도_세션생성은_통과한다 — 존재_검증_없음")
    void unknownCctvIsAccepted() {
        // given — CCTV <존재> 검증은 없다. 검증할 마스터(MNG_RESOURCE_CCTV)가 V167 로 제거됐고,
        //   관제 인입 경로(TrainingVideoIngestTx)도 존재 검증을 하지 않는다("동일 재현" 원칙).
        //   입력 검증은 형식 allowlist(CCTV_ID_PATTERN)가 담당한다(CWE-20).
        UUID id = service.createSession(OWNER, 10, minimal("VMS-1"));

        assertThat(repository.findById(id)).isPresent();
    }

    @Test
    @DisplayName("Phase1_cctvId가_비면_세션생성시_400 — 인입_VMS_CCTV_ID_는_NOT_NULL")
    void blankCctvIdRejectedOnCreate() {
        assertThatThrownBy(() -> service.createSession(OWNER, 10,
                request("VMS-1", "  ", null, null, null, null)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ======================== MED-2: 매직바이트 ftyp brand allowlist ========================

    @Test
    @DisplayName("MED2_ftyp위장_brand비허용_polyglot_완료시_409_파일삭제")
    void polyglotFtypBrandRejected() {
        // given — 4~8바이트만 'ftyp' 로 위장하고 major_brand 는 비허용('HACK'), 뒤는 악성 페이로드 모사.
        // box size(0x18) 는 정상이지만 brand allowlist 밖이라 컨테이너 위장으로 판정되어야 한다.
        byte[] polyglot = new byte[]{
                0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'H', 'A', 'C', 'K',
                '<', 's', 'c', 'r'};
        UUID id = service.createSession(OWNER, 16, minimal("VMS-1"));

        // when/then — 완료 시 매직바이트(brand) 검증 실패 → 409.
        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(polyglot), 16))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // then — 임시 파일 삭제됨 (오염 파일 잔존 차단).
        String filePath = repository.findById(id).orElseThrow().getFilePath();
        assertThat(Files.exists(Path.of(filePath))).isFalse();
    }

    @Test
    @DisplayName("MED2_정상_mp42_brand_완료시_통과")
    void validMp42BrandAccepted() {
        // given — 정상 major_brand 'mp42' 16바이트 → 완료 통과 (회귀 가드).
        byte[] valid = new byte[]{
                0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2',
                0, 0, 0, 0};
        UUID id = service.createSession(OWNER, 16, minimal("VMS-1"));

        var r = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(valid), 16);

        assertThat(r.completed()).isTrue();
    }

    // ======================== 보안 LOW: 경로 밖 삭제 차단 (CWE-22 심층방어) ========================

    @Test
    @DisplayName("LOW_filePath가_storageRawPath밖이면_cancel삭제차단_행만제거")
    void cancelDoesNotDeleteFileOutsideStorage() throws Exception {
        // given — 정상 세션 생성 후, filePath 를 storageRawPath 밖(별도 temp)으로 변조.
        UUID id = service.createSession(OWNER, 10, minimal("VMS-1"));
        Path outside = Files.createTempFile("outside-victim", ".bin");
        Files.writeString(outside, "victim");
        setFilePath(repository.findById(id).orElseThrow(), outside.toString());

        // when — cancel: 행은 제거되지만 storage 밖 파일은 삭제하면 안 됨 (심층방어).
        service.cancel(id, OWNER);

        // then — 행 제거 + 외부 파일 보존
        assertThat(repository.findById(id)).isEmpty();
        assertThat(Files.exists(outside)).isTrue();
        Files.deleteIfExists(outside);
    }

    @Test
    @DisplayName("관제_cancel과_PATCH_직렬화 — cancel이 락경로(findByUploadIdForUpdate)로 통일")
    void cancelUsesPessimisticLockPath() {
        // given — findById 를 쓰면 실패하고 findByUploadIdForUpdate(락 경로)만 동작하는 리포지토리.
        // cancel 이 PATCH 와 동일한 락 경로를 사용해야 통과한다(락 경로 통일 회귀 가드).
        repository = new InMemoryRepo() {
            @Override
            public Optional<LsTusUpload> findById(UUID id) {
                throw new UnsupportedOperationException("cancel must use findByUploadIdForUpdate (lock path)");
            }
        };
        service = newService();
        UUID id = service.createSession(OWNER, 10, minimal("VMS-1"));
        String filePath = repository.findByUploadIdForUpdate(id).orElseThrow().getFilePath();
        assertThat(Files.exists(Path.of(filePath))).isTrue();

        // when — cancel 은 락 경로로 세션을 조회해 취소한다(findById 미사용).
        service.cancel(id, OWNER);

        // then — 파일 삭제 + 행 제거(락 경로로 정상 취소).
        assertThat(repository.findByUploadIdForUpdate(id)).isEmpty();
        assertThat(Files.exists(Path.of(filePath))).isFalse();
    }

    @Test
    @DisplayName("DELETE_본인세션_취소시_임시파일삭제_행제거")
    void cancelDeletesFileAndRow() {
        UUID id = service.createSession(OWNER, 10, minimal("VMS-1"));
        String filePath = repository.findById(id).orElseThrow().getFilePath();
        assertThat(Files.exists(Path.of(filePath))).isTrue();

        service.cancel(id, OWNER);

        assertThat(repository.findById(id)).isEmpty();
        assertThat(Files.exists(Path.of(filePath))).isFalse();
    }

    // ======================== 헬퍼 ========================

    /** 먼저 인입 영역에 확정된 "다른 영상"의 내용 — 조용한 대체가 있었는지 판별하는 마커. */
    private static final String FIRST_WRITER_VIDEO = "first-writer-video";

    /** 인입 엔티티 픽스처(INSERT 통로가 없는 엔티티라 리플렉션으로 만든다). */
    private static LsDataIngest ingestRow(long rcptnSn, String prcsSttsCd,
                                          String rawFilePathNm, Long rawSn) {
        try {
            Constructor<LsDataIngest> ctor = LsDataIngest.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            LsDataIngest row = ctor.newInstance();
            setField(row, "rcptnSn", rcptnSn);
            setField(row, "prcsSttsCd", prcsSttsCd);
            setField(row, "rawFilePathNm", rawFilePathNm);
            setField(row, "rawSn", rawSn);
            return row;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = LsDataIngest.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 인입 행 상태를 밖에서 바꾼다 — "그 사이 폴링이 집었다/종결시켰다"를 재현한다. */
    private void setIngestState(LsDataIngest row, String prcsSttsCd) {
        setField(row, "prcsSttsCd", prcsSttsCd);
    }

    /** 이 서비스가 만들지 않은 인입 행(관제분·기적재분)을 심는다 — 되살리기 음성 케이스용. */
    private void seedForeignIngestRow(String vmsClipId, String rawFilePathNm,
                                      String prcsSttsCd, Long rawSn) {
        ingestRows.put(vmsClipId,
                ingestRow(rcptnSnSeq.incrementAndGet(), prcsSttsCd, rawFilePathNm, rawSn));
    }

    /** tus 임시 영역에 남은 파일 목록 — 실패 경로의 스토리지 누수 판정용. */
    private java.util.List<Path> listTempFiles() {
        Path dir = storageDir.resolve("tus-uploads");
        if (!Files.isDirectory(dir)) {
            return java.util.List.of();
        }
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).toList();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** 심링크 교체 전 실디렉터리 제거(테스트 픽스처). */
    private static void deleteRecursively(Path root) throws java.io.IOException {
        try (var walk = Files.walk(root)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private InternalUploadIngestCommand captureInsert() {
        ArgumentCaptor<InternalUploadIngestCommand> captor =
                ArgumentCaptor.forClass(InternalUploadIngestCommand.class);
        verify(ingestWriter).insertPending(captor.capture());
        return captor.getValue();
    }

    /** mp4 헤더 + 패딩으로 size 바이트 페이로드 생성 (매직바이트 통과용). */
    private static byte[] withMp4Head(int size) {
        byte[] out = new byte[Math.max(size, MP4_HEAD.length)];
        System.arraycopy(MP4_HEAD, 0, out, 0, Math.min(MP4_HEAD.length, out.length));
        if (size < MP4_HEAD.length) {
            byte[] trimmed = new byte[size];
            System.arraycopy(out, 0, trimmed, 0, size);
            return trimmed;
        }
        return out;
    }

    private static void forceExpire(LsTusUpload session) throws Exception {
        Field f = LsTusUpload.class.getDeclaredField("expiresAt");
        f.setAccessible(true);
        f.set(session, LocalDateTime.now().minusHours(1));
    }

    private static void setFilePath(LsTusUpload session, String path) throws Exception {
        Field f = LsTusUpload.class.getDeclaredField("filePath");
        f.setAccessible(true);
        f.set(session, path);
    }

    /** 최소 in-memory JpaRepository 구현 — 테스트에 필요한 메서드만. */
    static class InMemoryRepo implements LsTusUploadRepository {
        final Map<UUID, LsTusUpload> store = new HashMap<>();

        @Override
        public long countByUserNoAndStatus(String userNo, String status) {
            return store.values().stream()
                    .filter(u -> userNo.equals(u.getUserNo()) && status.equals(u.getStatus()))
                    .count();
        }

        @Override
        public long countByVmsClipIdAndStatus(String vmsClipId, String status) {
            return store.values().stream()
                    .filter(u -> vmsClipId.equals(u.getVmsClipId()) && status.equals(u.getStatus()))
                    .count();
        }

        @Override
        public java.util.List<LsTusUpload> findExpired(LocalDateTime now,
                                                       org.springframework.data.domain.Pageable pageable) {
            return store.values().stream()
                    .filter(u -> !LsTusUpload.STATUS_COMPLETED.equals(u.getStatus())
                            && u.getExpiresAt().isBefore(now))
                    .limit(pageable.getPageSize())
                    .toList();
        }

        @Override
        public int deleteExpiredById(UUID uploadId, LocalDateTime now) {
            LsTusUpload session = store.get(uploadId);
            if (session == null || LsTusUpload.STATUS_COMPLETED.equals(session.getStatus())
                    || !session.getExpiresAt().isBefore(now)) {
                return 0;
            }
            store.remove(uploadId);
            return 1;
        }

        @Override
        public Optional<LsTusUpload> findByUploadIdForUpdate(UUID uploadId) {
            // 단위 테스트: 비관적 잠금은 DB 레벨 동작 — 메모리 스텁에서는 단순 조회로 대체.
            return Optional.ofNullable(store.get(uploadId));
        }

        @Override
        public int terminateSession(UUID uploadId, LocalDateTime now) {
            LsTusUpload u = store.get(uploadId);
            if (u == null || LsTusUpload.STATUS_COMPLETED.equals(u.getStatus())) {
                return 0;
            }
            u.markExpired();
            try {
                Field f = LsTusUpload.class.getDeclaredField("expiresAt");
                f.setAccessible(true);
                f.set(u, now);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return 1;
        }

        @Override
        public int markCompletedIfInProgress(UUID uploadId, Long rawSn, LocalDateTime now) {
            LsTusUpload u = store.get(uploadId);
            if (u == null || !LsTusUpload.STATUS_IN_PROGRESS.equals(u.getStatus())) {
                return 0;
            }
            u.markCompleted(rawSn);
            return 1;
        }

        @Override
        public <S extends LsTusUpload> S save(S entity) {
            store.put(entity.getUploadId(), entity);
            return entity;
        }

        @Override
        public <S extends LsTusUpload> S saveAndFlush(S entity) {
            store.put(entity.getUploadId(), entity);
            return entity;
        }

        @Override
        public Optional<LsTusUpload> findById(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public void delete(LsTusUpload entity) {
            store.remove(entity.getUploadId());
        }

        // ----- 미사용 JpaRepository 메서드 (기본 미구현) -----
        @Override public java.util.List<LsTusUpload> findAll() { throw new UnsupportedOperationException(); }
        @Override public java.util.List<LsTusUpload> findAll(org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public java.util.List<LsTusUpload> findAllById(Iterable<UUID> ids) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsTusUpload> java.util.List<S> saveAll(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void flush() { }
        @Override public <S extends LsTusUpload> java.util.List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<LsTusUpload> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllByIdInBatch(Iterable<UUID> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch() { throw new UnsupportedOperationException(); }
        @Override public LsTusUpload getOne(UUID id) { throw new UnsupportedOperationException(); }
        @Override public LsTusUpload getById(UUID id) { throw new UnsupportedOperationException(); }
        @Override public LsTusUpload getReferenceById(UUID id) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsTusUpload> java.util.Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsTusUpload> java.util.List<S> findAll(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsTusUpload> java.util.List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsTusUpload> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsTusUpload> long count(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsTusUpload> boolean exists(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends LsTusUpload, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
        @Override public org.springframework.data.domain.Page<LsTusUpload> findAll(org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public boolean existsById(UUID id) { return store.containsKey(id); }
        @Override public long count() { return store.size(); }
        @Override public void deleteById(UUID id) { store.remove(id); }
        @Override public void deleteAllById(Iterable<? extends UUID> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll(Iterable<? extends LsTusUpload> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAll() { store.clear(); }
    }
}
