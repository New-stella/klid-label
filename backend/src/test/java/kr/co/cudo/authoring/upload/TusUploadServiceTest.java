package kr.co.cudo.authoring.upload;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.upload.dto.TusCreateCommand;
import kr.co.cudo.authoring.upload.entity.LsTusUpload;
import kr.co.cudo.authoring.upload.repository.LsTusUploadRepository;
import kr.co.cudo.authoring.upload.service.TusUploadService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.MngResourceCctvRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import kr.co.cudo.authoring.video.event.VideoIngestedEvent;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TUS 업로드 서비스 단위 테스트 — 정상 흐름 + HIGH 9 시나리오 방어.
 *
 * <p>리포지토리는 in-memory Map 으로, 파일시스템은 {@link TempDir} 실디스크로 대체해
 * DB/ffprobe 외부 의존 없이 결정적으로 모든 동시성·멱등·경계 케이스를 검증한다.
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
    private MngResourceCctvRepository cctvRepository;
    private ApplicationEventPublisher eventPublisher;
    private TusUploadService service;
    private final AtomicLong rawSnSeq = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        repository = new InMemoryRepo();
        videoRepository = mock(VideoRepository.class);
        cctvRepository = mock(MngResourceCctvRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        when(cctvRepository.existsById(anyString())).thenReturn(true);
        when(videoRepository.findByVmsClipId(anyString())).thenReturn(Optional.empty());
        when(videoRepository.save(any(LsDataRaw.class))).thenAnswer(inv -> {
            LsDataRaw raw = inv.getArgument(0);
            setRawSn(raw, rawSnSeq.incrementAndGet());
            return raw;
        });

        // duration probe stub — ffprobe 대체, 항상 60초 반환.
        service = new TusUploadService(repository, videoRepository, cctvRepository,
                storageDir.toString(), MAX_SIZE, eventPublisher, path -> 60);
    }

    @AfterEach
    void tearDown() {
        // TempDir 가 정리하므로 별도 작업 불필요.
    }

    private TusCreateCommand cmd(long length) {
        return new TusCreateCommand(length, "clip.mp4", "VMS-1", "CCTV-1",
                "EVT_FALL", "1168000000", "ANONY", Instant.parse("2024-05-01T12:00:00Z"));
    }

    // ======================== 정상 흐름 ========================

    @Test
    @DisplayName("정상_생성_청크2회_완료시_LS_DATA_RAW_생성")
    void happyPath() throws Exception {
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
        // then — 완료 + LS_DATA_RAW 생성
        assertThat(r2.newOffset()).isEqualTo(10);
        assertThat(r2.completed()).isTrue();
        assertThat(r2.rawSn()).isNotNull();
        assertThat(repository.findById(id).orElseThrow().isCompleted()).isTrue();
    }

    @Test
    @DisplayName("Phase2_적재완료시_VideoIngestedEvent_발행 — 선두 비식별 트리거")
    void publishesVideoIngestedEventOnComplete() {
        // given — 8바이트 단일 청크로 완료
        byte[] full = withMp4Head(8);
        UUID id = service.createSession(OWNER, cmd(8));

        // when
        var r = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);

        // then — 적재 완료 시 rawSn 으로 VideoIngestedEvent 발행
        assertThat(r.completed()).isTrue();
        org.mockito.Mockito.verify(eventPublisher)
                .publishEvent(eq(new VideoIngestedEvent(r.rawSn())));
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
        service = new TusUploadService(repository, videoRepository, cctvRepository,
                storageDir.toString(), MAX_SIZE, eventPublisher, path -> 60);
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
    @DisplayName("HIGH3_마지막청크_재전송시_멱등응답_LS_DATA_RAW1건")
    void duplicateCompletionIdempotent() {
        // 매직바이트(ftyp, 8바이트) 통과를 위해 8바이트 단일 청크로 완료.
        byte[] full = withMp4Head(8);
        UUID id = service.createSession(OWNER, cmd(8));
        var first = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);
        assertThat(first.completed()).isTrue();

        // when — 마지막 청크 재전송 (이미 완료)
        var again = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);
        // then — 멱등: 완료 응답 + 동일 rawSn
        assertThat(again.completed()).isTrue();
        assertThat(again.rawSn()).isEqualTo(first.rawSn());
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

        // then — 임시 파일 삭제됨
        String filePath = repository.findById(id).orElseThrow().getFilePath();
        assertThat(Files.exists(Path.of(filePath))).isFalse();
    }

    // ======================== HIGH-7: 경로 순회 — UUID 저장 강제 ========================

    @Test
    @DisplayName("HIGH7_filename경로순회시도_저장은_UUID강제_storage내부")
    void pathTraversalForcedUuid() {
        // given — 경로 순회 시도 파일명
        TusCreateCommand evil = new TusCreateCommand(10, "../../../etc/passwd.mp4",
                "VMS-2", "CCTV-1", "EVT_FALL", "1168000000", "ANONY",
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
                "EVT_FALL", "1168000000", "ANONY", Instant.parse("2024-05-01T12:00:00Z")));
        service.createSession(OWNER, new TusCreateCommand(10, "c.mp4", "VMS-C", "CCTV-1",
                "EVT_FALL", "1168000000", "ANONY", Instant.parse("2024-05-01T12:00:00Z")));
        // 4번째 → 429
        assertThatThrownBy(() -> service.createSession(OWNER, new TusCreateCommand(10, "d.mp4",
                "VMS-D", "CCTV-1", "EVT_FALL", "1168000000", "ANONY",
                Instant.parse("2024-05-01T12:00:00Z"))))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
    }

    // ======================== HIGH-2: 청크당 크기 상한 413 ========================

    @Test
    @DisplayName("HIGH2_단일청크가_maxChunkBytes초과시_413_truncate롤백")
    void chunkExceedsMaxChunkBytes() throws Exception {
        // given — 청크 상한 8바이트로 서비스 구성, 10바이트 단일 청크 전송
        TusUploadService capped = new TusUploadService(repository, videoRepository, cctvRepository,
                storageDir.toString(), MAX_SIZE, 8L, eventPublisher, path -> 60);
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
        TusUploadService capped = new TusUploadService(repository, videoRepository, cctvRepository,
                storageDir.toString(), MAX_SIZE, 32L, eventPublisher, path -> 60);
        byte[] full = withMp4Head(8);
        UUID id = capped.createSession(OWNER, cmd(8));

        var r = capped.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);

        assertThat(r.completed()).isTrue();
        assertThat(r.rawSn()).isNotNull();
    }

    // ======================== MED-1: 완료 DB 조건부 전이 멱등 ========================

    @Test
    @DisplayName("MED1_조건부전이_IN_PROGRESS일때만_LS_DATA_RAW1건생성")
    void conditionalCompletionCreatesRawOnce() {
        byte[] full = withMp4Head(8);
        UUID id = service.createSession(OWNER, cmd(8));

        // 첫 완료 — 전이 성공 (markCompletedIfInProgress affectedRows==1)
        var first = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);
        assertThat(first.completed()).isTrue();
        assertThat(first.rawSn()).isNotNull();

        // 마지막 청크 재전송 — 이미 COMPLETED → isCompleted() 단계에서 멱등 응답, 새 RAW 미생성
        var again = service.appendChunk(id, OWNER, 0, new ByteArrayInputStream(full, 0, 8), 8);
        assertThat(again.completed()).isTrue();
        assertThat(again.rawSn()).isEqualTo(first.rawSn());
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
        assertThat(r.rawSn()).isNotNull();
    }

    // ======================== 보안 LOW: 메타 사전 검증 (400) ========================

    @Test
    @DisplayName("LOW_localGovCd_숫자아님_세션생성시_400")
    void invalidLocalGovCdRejectedOnCreate() {
        TusCreateCommand cmd = new TusCreateCommand(10, "clip.mp4", "VMS-G", "CCTV-1",
                "EVT_FALL", "11A8", "ANONY", Instant.parse("2024-05-01T12:00:00Z"));
        assertThatThrownBy(() -> service.createSession(OWNER, cmd))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("LOW_prvcTypeCd_enum밖이면_세션생성시_400")
    void invalidPrvcTypeCdRejectedOnCreate() {
        TusCreateCommand cmd = new TusCreateCommand(10, "clip.mp4", "VMS-P", "CCTV-1",
                "EVT_FALL", "1168000000", "BOGUS", Instant.parse("2024-05-01T12:00:00Z"));
        assertThatThrownBy(() -> service.createSession(OWNER, cmd))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("LOW_eventTypeCd_패턴밖이면_세션생성시_400")
    void invalidEventTypeCdRejectedOnCreate() {
        TusCreateCommand cmd = new TusCreateCommand(10, "clip.mp4", "VMS-E", "CCTV-1",
                "EVT_HACK", "1168000000", "ANONY", Instant.parse("2024-05-01T12:00:00Z"));
        assertThatThrownBy(() -> service.createSession(OWNER, cmd))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("LOW_정상메타_세션생성_통과_회귀가드")
    void validMetaAcceptedOnCreate() {
        UUID id = service.createSession(OWNER, cmd(10));
        assertThat(repository.findById(id)).isPresent();
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

    private static void setRawSn(LsDataRaw raw, long val) {
        try {
            Field f = LsDataRaw.class.getDeclaredField("rawSn");
            f.setAccessible(true);
            f.set(raw, val);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
        public java.util.List<LsTusUpload> findExpired(java.time.LocalDateTime now) {
            return store.values().stream()
                    .filter(u -> !LsTusUpload.STATUS_COMPLETED.equals(u.getStatus())
                            && u.getExpiresAt().isBefore(now))
                    .toList();
        }

        @Override
        public Optional<LsTusUpload> findByUploadIdForUpdate(UUID uploadId) {
            // 단위 테스트: 비관적 잠금은 DB 레벨 동작 — 메모리 스텁에서는 단순 조회로 대체.
            return Optional.ofNullable(store.get(uploadId));
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
