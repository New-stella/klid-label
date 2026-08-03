package kr.co.cudo.authoring.upload;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.ArtifactRootTestSupport;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.upload.dto.TusCreateCommand;
import kr.co.cudo.authoring.upload.entity.LsTusUpload;
import kr.co.cudo.authoring.upload.repository.LsTusUploadRepository;
import kr.co.cudo.authoring.upload.service.InternalUploadPathResolver;
import kr.co.cudo.authoring.upload.service.TusUploadService;
import kr.co.cudo.authoring.video.dto.InternalUploadIngestCommand;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.InternalUploadIngestWriter;
import kr.co.cudo.authoring.video.repository.LsDataIngestRepository;
import kr.co.cudo.authoring.video.repository.MngResourceCctvRepository;
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
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TUS 업로드 서비스 단위 테스트 — 정상 흐름 + HIGH 9 시나리오 방어.
 *
 * <p>리포지토리는 in-memory Map 으로, 파일시스템은 {@link TempDir} 실디스크로 대체해
 * DB/ffprobe 외부 의존 없이 결정적으로 모든 동시성·멱등·경계 케이스를 검증한다.
 *
 * <h3>Phase 1 — 완료 시 합류 대상이 {@code LS_DATA_RAW} → {@code LS_DATA_INGEST} 로 바뀌었다</h3>
 * <p>업로드는 더 이상 {@code LS_DATA_RAW} 를 직접 만들지도, {@code VideoIngestedEvent} 를 발행하지도
 * 않는다. 파일을 인입 영역으로 옮기고 <b>인입 행(PENDING)</b> 만 남기면, 관제 인입과 동일하게 폴링
 * 배치({@code TrainingVideoIngestTx})가 적재하고 그 배치가 이벤트를 발행한다. 인입 경로를 우회하면
 * 적재 규칙(경로 allowlist·중복 판정·상태머신)이 업로드에만 적용되지 않는 두 번째 진실원이 된다.
 */
class TusUploadServiceTest {

    private static final String OWNER = "user-1";
    private static final long MAX_SIZE = 524_288_000L; // 500MB
    /** mp4 ISO BMFF 시그니처 (4~8바이트 'ftyp'). */
    private static final byte[] MP4_HEAD = new byte[]{
            0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'm', 'p', '4', '2', 0, 0, 0, 0};

    @TempDir
    Path storageDir;

    /** Phase 4a: 관제 마스터에 등록된 상세 EV-코드(침수 카테고리). */
    private static final String VALID_EVENT_CODE = "EV01000101";

    private LsTusUploadRepository repository;
    private VideoRepository videoRepository;
    private LsDataIngestRepository ingestRepository;
    private MngResourceCctvRepository cctvRepository;
    private InternalUploadIngestWriter ingestWriter;
    private InternalUploadPathResolver pathResolver;
    private EventTypeService eventTypeService;
    private TusUploadService service;
    private final AtomicLong rcptnSnSeq = new AtomicLong(7000);

    @BeforeEach
    void setUp() {
        repository = new InMemoryRepo();
        videoRepository = mock(VideoRepository.class);
        ingestRepository = mock(LsDataIngestRepository.class);
        cctvRepository = mock(MngResourceCctvRepository.class);
        ingestWriter = mock(InternalUploadIngestWriter.class);
        eventTypeService = mock(EventTypeService.class);
        pathResolver = new InternalUploadPathResolver(
                ArtifactRootTestSupport.coLocate(storageDir), storageDir.toString());
        // 등록 EV-코드만 categoryKey 변환 성공 — 미등록/위조 코드는 기본(빈 Optional)으로 거부.
        when(eventTypeService.categoryKeyOf(VALID_EVENT_CODE))
                .thenReturn(Optional.of("010001"));

        when(cctvRepository.existsById(anyString())).thenReturn(true);
        when(videoRepository.findByVmsClipId(anyString())).thenReturn(Optional.empty());
        when(ingestRepository.findByVmsClipId(anyString())).thenReturn(Optional.empty());
        when(ingestWriter.insertPending(any(InternalUploadIngestCommand.class)))
                .thenAnswer(inv -> rcptnSnSeq.incrementAndGet());

        service = newService();
    }

    private TusUploadService newService() {
        // duration probe stub — ffprobe 대체, 항상 60초 반환.
        return new TusUploadService(repository, videoRepository, ingestRepository, cctvRepository,
                ingestWriter, pathResolver, storageDir.toString(), MAX_SIZE, eventTypeService,
                path -> 60);
    }

    private TusCreateCommand cmd(long length) {
        return new TusCreateCommand(length, "clip.mp4", "VMS-1", "CCTV-1",
                VALID_EVENT_CODE, "1168000000", "ANONY", Instant.parse("2024-05-01T12:00:00Z"));
    }

    // ======================== 정상 흐름 ========================

    @Test
    @DisplayName("정상_생성_청크2회_완료시_인입행_PENDING_1건_생성")
    void happyPath() {
        // given — 10바이트 mp4 를 5+5 두 청크로 업로드
        byte[] full = withMp4Head(10);
        UUID id = service.createSession(OWNER, cmd(10));

        // when — 첫 청크(offset 0, 5바이트)
        var r1 = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 5), 5);
        // then
        assertThat(r1.newOffset()).isEqualTo(5);
        assertThat(r1.completed()).isFalse();

        // when — 둘째 청크(offset 5, 5바이트) → 완료
        var r2 = service.appendChunk(id, OWNER, 5, new ByteArrayInputStream(full, 5, 5), 5);

        // then — 완료 + 인입 행 1건(PENDING) 생성. LS_DATA_RAW 는 만들지 않는다.
        assertThat(r2.newOffset()).isEqualTo(10);
        assertThat(r2.completed()).isTrue();
        assertThat(r2.rcptnSn()).isNotNull();
        assertThat(repository.findById(id).orElseThrow().isCompleted()).isTrue();
        verify(videoRepository, never()).save(any(LsDataRaw.class));

        InternalUploadIngestCommand inserted = captureInsert();
        assertThat(inserted.vmsClipId()).isEqualTo("VMS-1");
        assertThat(inserted.vmsCctvId()).isEqualTo("CCTV-1");
        assertThat(inserted.srcType()).isEqualTo(LsDataIngest.SRC_TYPE_USER_ULD);
        assertThat(inserted.vdoLenSec()).isEqualByComparingTo("60");
        assertThat(inserted.fileSz()).isEqualTo(10L);
        assertThat(inserted.fileFmt()).isEqualTo("mp4");
        assertThat(inserted.lclgvCd()).isEqualTo("1168000000");
    }

    @Test
    @DisplayName("Phase1_적재는_인입경로가_담당한다 — 업로드가_VideoIngestedEvent를_직접_발행하지_않는다")
    void doesNotPublishVideoIngestedEvent() {
        // given — 8바이트 단일 청크로 완료
        byte[] full = withMp4Head(8);
        UUID id = service.createSession(OWNER, cmd(8));

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
        UUID id = service.createSession(OWNER, cmd(8));
        String tempPath = repository.findById(id).orElseThrow().getFilePath();

        // when
        service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);

        // then — 파일은 인입 영역으로 이동했고 임시 경로에는 남지 않는다
        Path expected = pathResolver.resolveUploadTarget("VMS-1", "mp4");
        assertThat(Files.exists(expected)).isTrue();
        assertThat(Files.exists(Path.of(tempPath))).isFalse();
        assertThat(captureInsert().rawFilePathNm()).isEqualTo(expected.toString());

        // then — ★세션 FILE_PATH 는 <임시 경로 그대로>다. NAS 경로로 갱신하면 완료 전이가 유실된
        //   세션을 24h 뒤 TusUploadCleanupJob 이 스윕할 때 <인입 완료된 원본>을 지운다
        //   (정리 가드가 raw-path 하위만 보므로 새 경로도 그대로 통과해 버린다).
        assertThat(repository.findById(id).orElseThrow().getFilePath()).isEqualTo(tempPath);
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
        UUID id = service.createSession(OWNER, cmd(10));

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
        assertThatThrownBy(() -> service.createSession(OWNER, cmd(MAX_SIZE + 1)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
    }

    // ======================== HIGH-3: 완료 중복 멱등 ========================

    @Test
    @DisplayName("HIGH3_마지막청크_재전송시_멱등응답_인입행1건")
    void duplicateCompletionIdempotent() {
        // 매직바이트(ftyp, 8바이트) 통과를 위해 8바이트 단일 청크로 완료.
        byte[] full = withMp4Head(8);
        UUID id = service.createSession(OWNER, cmd(8));
        var first = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);
        assertThat(first.completed()).isTrue();
        assertThat(first.rcptnSn()).isNotNull();

        // when — 마지막 청크 재전송 (이미 완료)
        var again = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);

        // then — 멱등: 완료 응답 + 인입 행은 <1건>만 생성됐다
        assertThat(again.completed()).isTrue();
        verify(ingestWriter, times(1)).insertPending(any(InternalUploadIngestCommand.class));
    }

    // ======================== HIGH-5: TTL 만료 410 ========================

    @Test
    @DisplayName("HIGH5_만료세션_HEAD시_410")
    void expiredHeadGone() throws Exception {
        UUID id = service.createSession(OWNER, cmd(10));
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
        UUID id = service.createSession(OWNER, cmd(10));
        forceExpire(repository.findById(id).orElseThrow());

        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(full, 0, 5), 5))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.GONE);
    }

    // ======================== HIGH-6: 매직바이트 검증 ========================

    @Test
    @DisplayName("HIGH6_완료시_매직바이트불일치_임시파일삭제후_409")
    void magicByteMismatch() {
        // given — mp4 헤더 없는 10바이트 (텍스트)
        byte[] bogus = new byte[10];
        for (int i = 0; i < 10; i++) bogus[i] = 'X';
        UUID id = service.createSession(OWNER, cmd(10));

        // when/then — 완료 시 매직바이트 검증 실패 → 409
        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(bogus), 10))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // then — 임시 파일 삭제됨 + 인입 행 미생성
        String filePath = repository.findById(id).orElseThrow().getFilePath();
        assertThat(Files.exists(Path.of(filePath))).isFalse();
        verify(ingestWriter, never()).insertPending(any(InternalUploadIngestCommand.class));
    }

    // ======================== HIGH-7: 경로 순회 — UUID 저장 강제 ========================

    @Test
    @DisplayName("HIGH7_filename경로순회시도_저장은_UUID강제_storage내부")
    void pathTraversalForcedUuid() {
        // given — 경로 순회 시도 파일명
        TusCreateCommand evil = new TusCreateCommand(10, "../../../etc/passwd.mp4",
                "VMS-2", "CCTV-1", VALID_EVENT_CODE, "1168000000", "ANONY",
                Instant.parse("2024-05-01T12:00:00Z"));
        UUID id = service.createSession(OWNER, evil);

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
        UUID id = service.createSession(OWNER, cmd(10));

        assertThatThrownBy(() -> service.appendChunk(id, "intruder", 0,
                new ByteArrayInputStream(full, 0, 5), 5))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("HIGH8_타인세션_HEAD시_403")
    void nonOwnerHeadForbidden() {
        UUID id = service.createSession(OWNER, cmd(10));
        assertThatThrownBy(() -> service.getForOwner(id, "intruder"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ======================== HIGH-9: offset/경계/상한 ========================

    @Test
    @DisplayName("HIGH9_Offset불일치시_409")
    void offsetMismatchConflict() {
        byte[] full = withMp4Head(10);
        UUID id = service.createSession(OWNER, cmd(10));
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
        UUID id = service.createSession(OWNER, cmd(10));
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
        UUID id = service.createSession(OWNER, cmd(10));
        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 11,
                new ByteArrayInputStream(full, 0, 5), 5))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("HIGH9_ContentLength0_빈청크시_400")
    void emptyChunkBadRequest() {
        UUID id = service.createSession(OWNER, cmd(10));
        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(new byte[0]), 0))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("HIGH9_사용자별_동시IN_PROGRESS3개초과시_429")
    void tooManyConcurrentSessions() {
        service.createSession(OWNER, cmd(10));
        service.createSession(OWNER, new TusCreateCommand(10, "b.mp4", "VMS-B", "CCTV-1",
                VALID_EVENT_CODE, "1168000000", "ANONY", Instant.parse("2024-05-01T12:00:00Z")));
        service.createSession(OWNER, new TusCreateCommand(10, "c.mp4", "VMS-C", "CCTV-1",
                VALID_EVENT_CODE, "1168000000", "ANONY", Instant.parse("2024-05-01T12:00:00Z")));
        // 4번째 → 429
        assertThatThrownBy(() -> service.createSession(OWNER, new TusCreateCommand(10, "d.mp4",
                "VMS-D", "CCTV-1", VALID_EVENT_CODE, "1168000000", "ANONY",
                Instant.parse("2024-05-01T12:00:00Z"))))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
    }

    // ======================== HIGH-2: 청크당 크기 상한 413 ========================

    @Test
    @DisplayName("HIGH2_단일청크가_maxChunkBytes초과시_413_truncate롤백")
    void chunkExceedsMaxChunkBytes() {
        // given — 청크 상한 8바이트로 서비스 구성, 10바이트 단일 청크 전송
        TusUploadService capped = new TusUploadService(repository, videoRepository, ingestRepository,
                cctvRepository, ingestWriter, pathResolver, storageDir.toString(), MAX_SIZE, 8L,
                eventTypeService, path -> 60);
        byte[] full = withMp4Head(10);
        UUID id = capped.createSession(OWNER, cmd(10));

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
                cctvRepository, ingestWriter, pathResolver, storageDir.toString(), MAX_SIZE, 32L,
                eventTypeService, path -> 60);
        byte[] full = withMp4Head(8);
        UUID id = capped.createSession(OWNER, cmd(8));

        var r = capped.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);

        assertThat(r.completed()).isTrue();
        assertThat(r.rcptnSn()).isNotNull();
    }

    // ======================== MED-1: 완료 DB 조건부 전이 멱등 ========================

    @Test
    @DisplayName("MED1_조건부전이_IN_PROGRESS일때만_인입행1건생성")
    void conditionalCompletionCreatesIngestOnce() {
        byte[] full = withMp4Head(8);
        UUID id = service.createSession(OWNER, cmd(8));

        // 첫 완료 — 전이 성공 (markCompletedIfInProgress affectedRows==1)
        var first = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);
        assertThat(first.completed()).isTrue();
        assertThat(first.rcptnSn()).isNotNull();

        // 마지막 청크 재전송 — 이미 COMPLETED → isCompleted() 단계에서 멱등 응답, 인입 행 추가 생성 없음
        var again = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);
        assertThat(again.completed()).isTrue();
        verify(ingestWriter, times(1)).insertPending(any(InternalUploadIngestCommand.class));
    }

    @Test
    @DisplayName("MED1_완료전이가_이미_다른트랜잭션에_선점됐으면_인입행을_만들지_않는다")
    void doesNotInsertIngestWhenCompletionAlreadyClaimed() {
        // given — 완료 전이(조건부 UPDATE)가 항상 0행을 반환하는 리포지토리(= 다른 트랜잭션이 선점)
        repository = new InMemoryRepo() {
            @Override
            public int markCompletedIfInProgress(UUID uploadId, Long rawSn, java.time.LocalDateTime now) {
                return 0;
            }
        };
        service = newService();
        byte[] full = withMp4Head(8);
        UUID id = service.createSession(OWNER, cmd(8));

        // when
        var r = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);

        // then — 전이 책임이 없으므로 인입 행을 만들지 않는다(중복 인입 차단)
        assertThat(r.completed()).isTrue();
        verify(ingestWriter, never()).insertPending(any(InternalUploadIngestCommand.class));

        // then — ★F2: 그런데 파일은 이미 옮겨졌다. 참조할 인입 행이 영영 생기지 않으므로
        //   비식별 전 원본(PII)이 인입 영역에 고아로 남으면 안 된다(아무 정리 주체도 지우지 않는다).
        assertThat(Files.exists(pathResolver.resolveUploadTarget("VMS-1", "mp4")))
                .as("완료 전이를 선점당한 호출이 옮긴 파일은 회수돼야 한다")
                .isFalse();
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
        UUID id = service.createSession(OWNER, cmd(8));

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
        verify(ingestWriter, never()).insertPending(any(InternalUploadIngestCommand.class));
    }

    // ======================== F2: 비가역 파일 이동의 보상 (CWE-459/772/359) ========================

    @Test
    @DisplayName("F2_인입INSERT가_UK위반으로_실패하면_409_이면서_옮긴파일을_회수하고_세션을_종결한다")
    void ingestInsertUniqueViolationDiscardsMovedFileAndTerminatesSession() {
        // given — 세션 생성 시점 사전 조회 이후 같은 클립이 인입된 race (UK 위반, SQLState 23505)
        when(ingestWriter.insertPending(any(InternalUploadIngestCommand.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key",
                        new SQLException("duplicate key value violates unique constraint", "23505")));
        // given — ★실 트랜잭션 모사: 완료 전이는 <이번 호출이 책임자>임을 알리지만(1행), 그 UPDATE 는
        //   곧 롤백될 트랜잭션 안에 있어 영속 상태로는 남지 않는다. 이 롤백 시맨틱을 재현해야
        //   "종결이 별도 트랜잭션이라 살아남는다"는 계약을 검증할 수 있다(in-memory 는 롤백이 없다).
        repository = new InMemoryRepo() {
            @Override
            public int markCompletedIfInProgress(UUID uploadId, Long rawSn, java.time.LocalDateTime now) {
                return 1;
            }
        };
        service = newService();
        byte[] full = withMp4Head(8);
        UUID id = service.createSession(OWNER, cmd(8));
        Path target = pathResolver.resolveUploadTarget("VMS-1", "mp4");

        // when/then — 409
        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(full, 0, 8), 8))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        // then — ★비식별 전 원본(PII)이 참조 없는 고아로 남지 않는다.
        //   인입 행이 없어 비식별이 영영 수행되지 않고, 정리 잡은 <임시> 경로만 지운다.
        assertThat(Files.exists(target))
                .as("INSERT 실패 시 이미 옮겨진 파일은 회수돼야 한다")
                .isFalse();

        // then — 세션은 명시 종결(별도 트랜잭션)돼 재시도가 <사라진 임시 파일>로 가지 않는다.
        LsTusUpload session = repository.findById(id).orElseThrow();
        assertThat(session.getStatus()).isEqualTo(LsTusUpload.STATUS_EXPIRED);
        assertThat(session.isExpired(java.time.LocalDateTime.now().plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("F6_UK위반이_아닌_무결성오류를_클립ID중복_409로_오진단하지_않는다")
    void nonUniqueIntegrityViolationIsNotReportedAsDuplicate() {
        // given — NOT NULL 위반(23502) 등은 원인이 전혀 다르다. 409 로 덮으면 원인 규명이 막힌다.
        when(ingestWriter.insertPending(any(InternalUploadIngestCommand.class)))
                .thenThrow(new DataIntegrityViolationException("not-null violation",
                        new SQLException("null value in column violates not-null constraint", "23502")));
        byte[] full = withMp4Head(8);
        UUID id = service.createSession(OWNER, cmd(8));
        Path target = pathResolver.resolveUploadTarget("VMS-1", "mp4");

        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(full, 0, 8), 8))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        // then — 실패 경로 보상은 원인과 무관하게 동일하다(고아 금지)
        assertThat(Files.exists(target)).isFalse();
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
            public int terminateSession(UUID uploadId, java.time.LocalDateTime now) {
                terminatedVia.add(uploadId);
                return super.terminateSession(uploadId, now);
            }
        };
        service = newService();
        byte[] bogus = new byte[10];
        java.util.Arrays.fill(bogus, (byte) 'X');
        UUID id = service.createSession(OWNER, cmd(10));

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
        assertThat(session.isExpired(java.time.LocalDateTime.now().plusSeconds(1))).isTrue();
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
        UUID id = service.createSession(OWNER, cmd(8));

        // when/then — 409 (조용한 대체 금지)
        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(full, 0, 8), 8))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        assertThat(Files.readString(target)).isEqualTo(FIRST_WRITER_VIDEO);
        verify(ingestWriter, never()).insertPending(any(InternalUploadIngestCommand.class));
    }

    @Test
    @DisplayName("F3_쓰기직전_검증통과_이후_같은_파일명이_생겨도_남의_영상을_대체하지_않는다")
    void nameClaimAfterVerificationDoesNotReplaceOtherVideo() {
        // given — "검증 통과 → 실제 쓰기" 사이에 다른 세션이 같은 파일명을 확정하는 창을 재현한다.
        //   구 구현은 이 창이 비원자였고(exists 체크 후 ATOMIC_MOVE) ATOMIC_MOVE 는 대상이 있으면
        //   <조용히 대체>하므로, 뒤에 온 파일이 앞의 영상을 덮고도 뒤쪽만 실패로 인지했다.
        //   수정본은 O_EXCL(createFile) 로 이름을 원자 예약하므로 남의 파일을 건드리지 못한다.
        pathResolver = new InternalUploadPathResolver(
                ArtifactRootTestSupport.coLocate(storageDir), storageDir.toString()) {
            @Override
            public void verifyIngestable(Path target) {
                super.verifyIngestable(target);
                try {
                    Files.createDirectories(target.getParent());
                    if (!Files.exists(target)) {
                        Files.writeString(target, FIRST_WRITER_VIDEO);
                    }
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        };
        service = newService();
        byte[] full = withMp4Head(8);
        UUID id = service.createSession(OWNER, cmd(8));

        // when/then — 409 + 먼저 확정된 실체 보존
        assertThatThrownBy(() -> service.appendChunk(id, OWNER, 0,
                new ByteArrayInputStream(full, 0, 8), 8))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        Path target = pathResolver.resolveUploadTarget("VMS-1", "mp4");
        assertThat(Files.exists(target)).isTrue();
        assertThat(readQuietly(target)).isEqualTo(FIRST_WRITER_VIDEO);
        verify(ingestWriter, never()).insertPending(any(InternalUploadIngestCommand.class));
    }

    @Test
    @DisplayName("R2_예약후_이동이_실패하면_0바이트_예약파일을_남기지_않는다 — 영구409_자기잠금_방지")
    void reservationIsRolledBackWhenMoveFails() {
        // given — O_EXCL 예약은 성공하고 <이동만> 실패하는 상황을 만든다.
        //   ★프로덕션에 테스트 훅을 넣지 않기 위해 이미 존재하는 seam(쓰기 직전 재판정)을 쓴다:
        //     verifyIngestable 시점에 임시 파일을 지우면 → createFile(예약)은 성공하고
        //     이어지는 Files.move 가 NoSuchFileException(IOException)으로 실패한다.
        //     이 경로가 깨지면 0바이트 예약 파일이 남아 같은 vmsClipId 가 영구 409 로 잠긴다
        //     (F3 이 명시적으로 방지하려던 실패형).
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
        movingUploadId = service.createSession(OWNER, cmd(8));

        // when/then — 이동 실패는 500 으로 종결한다
        assertThatThrownBy(() -> service.appendChunk(movingUploadId, OWNER, 0,
                new ByteArrayInputStream(full, 0, 8), 8))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        // then — ★예약분이 회수돼 대상 경로가 비어 있다. 남으면 같은 클립 재업로드가 영구 409 다.
        assertThat(Files.exists(pathResolver.resolveUploadTarget("VMS-1", "mp4")))
                .as("이동 실패 시 O_EXCL 예약(0바이트)은 되돌려야 한다")
                .isFalse();
        verify(ingestWriter, never()).insertPending(any(InternalUploadIngestCommand.class));
    }

    /** R2 seam 이 "현재 이동 중인 세션"의 임시 경로를 찾기 위한 참조(테스트 전용). */
    private UUID movingUploadId;

    // ======================== F6: cctvId allowlist (CWE-20) ========================

    @Test
    @DisplayName("F6_cctvId가_allowlist_밖이면_세션생성시_400 — 인입_VMS_CCTV_ID_는_VARCHAR64")
    void invalidCctvIdRejectedOnCreate() {
        // given — 구 구현은 공백 여부만 봐서 65자 이상이 완료 시점 INSERT 에서 500 으로 터졌고
        //   0바이트 임시 파일이 남았다. 세션 생성 단에서 fail-fast 한다.
        for (String evil : new String[]{"A".repeat(65), "cctv/../etc", "cctv id", "cctv$1", "씨씨티비"}) {
            TusCreateCommand invalid = new TusCreateCommand(10, "clip.mp4", "VMS-C-" + evil.length(),
                    evil, VALID_EVENT_CODE, "1168000000", "ANONY",
                    Instant.parse("2024-05-01T12:00:00Z"));
            assertThatThrownBy(() -> service.createSession(OWNER, invalid))
                    .as("cctvId=%s", evil)
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
        // 경계 — 64자는 통과한다
        TusCreateCommand ok = new TusCreateCommand(10, "clip.mp4", "VMS-C-OK", "A".repeat(64),
                VALID_EVENT_CODE, "1168000000", "ANONY", Instant.parse("2024-05-01T12:00:00Z"));
        assertThat(service.createSession(OWNER, ok)).isNotNull();
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
        UUID id = service.createSession(OWNER, cmd(16));

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
        UUID id = service.createSession(OWNER, cmd(16));

        var r = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(valid), 16);

        assertThat(r.completed()).isTrue();
        assertThat(r.rcptnSn()).isNotNull();
    }

    // ======================== 보안 LOW: 메타 사전 검증 (400) ========================

    @Test
    @DisplayName("LOW_localGovCd_숫자아님_세션생성시_400")
    void invalidLocalGovCdRejectedOnCreate() {
        TusCreateCommand cmd = new TusCreateCommand(10, "clip.mp4", "VMS-G", "CCTV-1",
                VALID_EVENT_CODE, "11A8", "ANONY", Instant.parse("2024-05-01T12:00:00Z"));
        assertThatThrownBy(() -> service.createSession(OWNER, cmd))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("LOW_prvcTypeCd_enum밖이면_세션생성시_400")
    void invalidPrvcTypeCdRejectedOnCreate() {
        TusCreateCommand cmd = new TusCreateCommand(10, "clip.mp4", "VMS-P", "CCTV-1",
                VALID_EVENT_CODE, "1168000000", "BOGUS", Instant.parse("2024-05-01T12:00:00Z"));
        assertThatThrownBy(() -> service.createSession(OWNER, cmd))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("Phase4a_eventTypeCd_관제미등록코드면_세션생성시_400")
    void invalidEventTypeCdRejectedOnCreate() {
        // 구 EVT_* 코드/임의 문자열은 categoryKeyOf 가 빈 Optional → 400.
        TusCreateCommand cmd = new TusCreateCommand(10, "clip.mp4", "VMS-E", "CCTV-1",
                "EVT_HACK", "1168000000", "ANONY", Instant.parse("2024-05-01T12:00:00Z"));
        assertThatThrownBy(() -> service.createSession(OWNER, cmd))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("Phase4a_eventTypeCd_관제등록_EV코드면_세션생성_통과")
    void validControlEventCodeAcceptedOnCreate() {
        // VALID_EVENT_CODE(EV01000101)는 categoryKeyOf 가 present → 통과 (회귀 가드).
        UUID id = service.createSession(OWNER, cmd(10));
        assertThat(repository.findById(id)).isPresent();
    }

    @Test
    @DisplayName("Phase4a_eventTypeCd_빈값이면_세션생성_통과_이벤트미설정허용")
    void blankEventCodeAcceptedOnCreate() {
        TusCreateCommand cmd = new TusCreateCommand(10, "clip.mp4", "VMS-B", "CCTV-1",
                "", "1168000000", "ANONY", Instant.parse("2024-05-01T12:00:00Z"));
        UUID id = service.createSession(OWNER, cmd);
        assertThat(repository.findById(id)).isPresent();
    }

    // ======================== Phase 1: vmsClipId · 인입 중복 · CCTV 완화 ========================

    @Test
    @DisplayName("Phase1_vmsClipId가_없으면_세션생성시_400 — 인입_NOT_NULL_멱등키다")
    void blankVmsClipIdRejectedOnCreate() {
        TusCreateCommand cmd = new TusCreateCommand(10, "clip.mp4", "  ", "CCTV-1",
                VALID_EVENT_CODE, "1168000000", "ANONY", Instant.parse("2024-05-01T12:00:00Z"));
        assertThatThrownBy(() -> service.createSession(OWNER, cmd))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("Phase1_vmsClipId가_경로문자를_포함하면_세션생성시_400 — 저장 파일명이 된다")
    void pathLikeVmsClipIdRejectedOnCreate() {
        for (String evil : new String[]{"../etc/passwd", "a/b", "a\\b", "clip id", "A".repeat(65)}) {
            TusCreateCommand cmd = new TusCreateCommand(10, "clip.mp4", evil, "CCTV-1",
                    VALID_EVENT_CODE, "1168000000", "ANONY", Instant.parse("2024-05-01T12:00:00Z"));
            assertThatThrownBy(() -> service.createSession(OWNER, cmd))
                    .as("clipId=%s", evil)
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
    }

    @Test
    @DisplayName("Phase1_같은_vmsClipId의_인입행이_이미_있으면_세션생성시_409")
    void duplicateIngestRowRejectedOnCreate() {
        // given — 인입 행은 <영구 보존>이라 DONE/FAILED 로 남은 과거 행도 UK 를 점유한다.
        //   LS_DATA_RAW 조회만으로는(예: 적재 실패로 영상이 없는 경우) 이 중복을 잡지 못한다.
        when(ingestRepository.findByVmsClipId("VMS-1"))
                .thenReturn(Optional.of(mock(LsDataIngest.class)));

        assertThatThrownBy(() -> service.createSession(OWNER, cmd(10)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("Phase1_미등록_CCTV여도_세션생성은_통과한다 — 경고로_완화")
    void unknownCctvIsWarnedNotRejected() {
        // given — MNG_RESOURCE_CCTV 를 채우는 주체는 관제뿐이라 dev/246 에는 행이 없다.
        //   400 을 유지하면 그 환경의 모든 업로드가 죽는다(인입 경로도 이 검증을 하지 않는다).
        when(cctvRepository.existsById(anyString())).thenReturn(false);

        UUID id = service.createSession(OWNER, cmd(10));

        assertThat(repository.findById(id)).isPresent();
    }

    @Test
    @DisplayName("Phase1_cctvId가_비면_세션생성시_400 — 인입_VMS_CCTV_ID_는_NOT_NULL")
    void blankCctvIdRejectedOnCreate() {
        TusCreateCommand cmd = new TusCreateCommand(10, "clip.mp4", "VMS-1", "  ",
                VALID_EVENT_CODE, "1168000000", "ANONY", Instant.parse("2024-05-01T12:00:00Z"));
        assertThatThrownBy(() -> service.createSession(OWNER, cmd))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ======================== 보안 LOW: 경로 밖 삭제 차단 (CWE-22 심층방어) ========================

    @Test
    @DisplayName("LOW_filePath가_storageRawPath밖이면_cancel삭제차단_행만제거")
    void cancelDoesNotDeleteFileOutsideStorage() throws Exception {
        // given — 정상 세션 생성 후, filePath 를 storageRawPath 밖(별도 temp)으로 변조.
        UUID id = service.createSession(OWNER, cmd(10));
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
        UUID id = service.createSession(OWNER, cmd(10));
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
        UUID id = service.createSession(OWNER, cmd(10));
        String filePath = repository.findById(id).orElseThrow().getFilePath();
        assertThat(Files.exists(Path.of(filePath))).isTrue();

        service.cancel(id, OWNER);

        assertThat(repository.findById(id)).isEmpty();
        assertThat(Files.exists(Path.of(filePath))).isFalse();
    }

    // ======================== 헬퍼 ========================

    /** 먼저 인입 영역에 확정된 "다른 영상"의 내용 — 조용한 대체가 있었는지 판별하는 마커. */
    private static final String FIRST_WRITER_VIDEO = "first-writer-video";

    private static String readQuietly(Path path) {
        try {
            return Files.readString(path);
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
        f.set(session, java.time.LocalDateTime.now().minusHours(1));
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
        public java.util.List<LsTusUpload> findExpired(java.time.LocalDateTime now,
                                                       org.springframework.data.domain.Pageable pageable) {
            return store.values().stream()
                    .filter(u -> !LsTusUpload.STATUS_COMPLETED.equals(u.getStatus())
                            && u.getExpiresAt().isBefore(now))
                    .limit(pageable.getPageSize())
                    .toList();
        }

        @Override
        public int deleteExpiredById(UUID uploadId, java.time.LocalDateTime now) {
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
        public int terminateSession(UUID uploadId, java.time.LocalDateTime now) {
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
        public int markCompletedIfInProgress(UUID uploadId, Long rawSn, java.time.LocalDateTime now) {
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
