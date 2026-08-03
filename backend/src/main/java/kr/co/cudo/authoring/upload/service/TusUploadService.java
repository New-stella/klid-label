package kr.co.cudo.authoring.upload.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.upload.dto.TusCreateCommand;
import kr.co.cudo.authoring.upload.entity.LsTusUpload;
import kr.co.cudo.authoring.upload.repository.LsTusUploadRepository;
import kr.co.cudo.authoring.video.dto.InternalUploadIngestCommand;
import kr.co.cudo.authoring.video.repository.InternalUploadIngestWriter;
import kr.co.cudo.authoring.video.repository.LsDataIngestRepository;
import kr.co.cudo.authoring.video.repository.MngResourceCctvRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TUS 1.0 재개 가능 업로드 코어 서비스 (CVAT 포팅 Phase 3 — 관리 화면 대용량 영상 적재).
 *
 * <p>운영(prd) 에서도 관리 화면 업로드는 필요하므로 {@code @Profile} 미적용 — 전 환경 활성.
 * 권한(REVIEWER, INTERNAL 채널)은 컨트롤러 {@code @PreAuthorize} + SecurityConfig 로 차단.
 *
 * <h3>Phase 1 — 완료 시 합류 대상은 {@code LS_DATA_INGEST}(인입) 다</h3>
 * <p>적재 주체 반전 이후 영상 적재의 단일 통로는 <b>인입 폴링</b>이다. 이 서비스는 관제가 하는 일
 * (파일 배치 + 인입 행 INSERT)까지만 하고, {@code LS_DATA_RAW} 생성·{@code VideoIngestedEvent} 발행은
 * {@code TrainingVideoIngestTx} 에 맡긴다({@link #complete} 참조). 업로드 저장 경로가 적재 allowlist
 * 밖이면 그 인입 행은 매 건 영구 종결되므로 {@link InternalUploadWiringGuard} 가 배포 형상에서
 * <b>업로드 기능을 닫는다</b>(기동은 정상, 엔드포인트만 503 — 오설정 1건이 라벨링·검수·배치까지
 * 멈추지 않게 실패 범위를 기능 단위로 한정한다).
 *
 * <p>시나리오 방어 (HIGH 9):
 * <ol>
 *   <li>동시 PATCH 오프셋 충돌 → {@code @Version} 낙관적 잠금 → {@code OptimisticLockingFailureException} → 409.</li>
 *   <li>Upload-Length &gt; maxFileSize → POST 시 413.</li>
 *   <li>완료 중복 → 조건부 UPDATE({@code markCompletedIfInProgress}) 원자 전이 + 멱등 응답.
 *       affectedRows==1 인 호출만 인입 행을 만든다.</li>
 *   <li>부분 쓰기 → FileChannel position write 성공 후에만 offset 전진, 실패 시 truncate.</li>
 *   <li>TTL — EXPIRES_AT + 만료 시 410.</li>
 *   <li>완료 시 매직바이트 검증 실패 → 임시파일 삭제 + 409.</li>
 *   <li>경로 순회 — filename 표시용, 저장 UUID 강제.</li>
 *   <li>소유자 — USER_NO 본인만 HEAD/PATCH/DELETE (403).</li>
 *   <li>offset 불일치 409 / 음수·초과 400 / 빈 청크 400 / 동시 IN_PROGRESS 3 상한 429.</li>
 * </ol>
 */
@Slf4j
@Service
public class TusUploadService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("mp4", "webm", "mov", "avi");
    private static final String UPLOAD_SUBDIR = "tus-uploads";
    private static final int MAX_DURATION_SEC = 7200;
    /** 사용자별 동시 진행 세션 상한 (HIGH-9). */
    private static final int MAX_CONCURRENT_IN_PROGRESS = 3;
    /** HIGH-2: 청크당 상한 기본값 (16MB) — 설정 미지정 시 사용. */
    static final long DEFAULT_MAX_CHUNK_BYTES = 16L * 1024 * 1024;
    /** PostgreSQL 유니크 제약 위반 SQLState — 409(클립 ID 중복) 판정을 이 코드로만 좁힌다(F6). */
    static final String SQL_STATE_UNIQUE_VIOLATION = "23505";

    private final LsTusUploadRepository uploadRepository;
    private final VideoRepository videoRepository;
    /** Phase 1: 인입 행 기준 중복(UK) 사전 판정 — 인입 행은 삭제되지 않아 과거 행도 UK 를 점유한다. */
    private final LsDataIngestRepository ingestRepository;
    private final MngResourceCctvRepository cctvRepository;
    /** Phase 1: 완료 시 인입 행(PENDING) 을 남기는 <b>유일한</b> 비-관제 INSERT 통로. */
    private final InternalUploadIngestWriter ingestWriter;
    /** Phase 1: 업로드 파일이 놓일 인입 영역 경로 결정(적재 allowlist 정합 보장). */
    private final InternalUploadPathResolver pathResolver;
    private final Path storageRawPath;
    private final long maxFileSize;
    /** HIGH-2: 단일 PATCH 청크 크기 상한 (authoring.upload.tus.max-chunk-bytes). */
    private final long maxChunkBytes;
    private final DurationProbe durationProbe;
    /** Phase 4a: 이벤트 코드 검증을 관제 마스터 기반(상세 EV-코드 등록 여부)으로 전환. */
    private final EventTypeService eventTypeService;

    /**
     * 인입 테이블에 자리가 없는 세션 메타의 유실 WARN 을 프로세스 1회로 제한하는 게이트.
     *
     * <p>결손은 <b>매 건</b> 발생하므로 건마다 WARN 하면 실제 실패 로그가 묻힌다
     * ({@code TrainingVideoIngestTx} 의 계약 갭 WARN 과 동일 규약). 빈이 싱글턴이라 운영에서는
     * 프로세스 1회로 동작하고, 테스트에서는 인스턴스마다 초기화돼 실행 순서에 단언이 흔들리지 않는다.
     */
    private final AtomicBoolean droppedMetaWarned = new AtomicBoolean();

    /**
     * 프로덕션 생성자 — Spring 컴포넌트 스캔이 주입한다.
     *
     * <p>MED-2: 본 클래스는 테스트 전용 보조 생성자({@code DurationProbe} 인터페이스를 받는 아래 2개)를
     * 보유하므로 생성자가 복수다. Spring 은 복수 생성자에서 {@code @Autowired} 가 없으면 주입 생성자를
     * 자동 결정하지 못해 기본(no-arg) 생성자로 폴백→실패하므로, 주입 대상인 본 프로덕션 생성자에만
     * {@code @Autowired} 를 명시한다(테스트 생성자는 테스트가 직접 호출 — 컨테이너 주입 대상 아님).
     */
    @org.springframework.beans.factory.annotation.Autowired
    public TusUploadService(
            LsTusUploadRepository uploadRepository,
            VideoRepository videoRepository,
            LsDataIngestRepository ingestRepository,
            MngResourceCctvRepository cctvRepository,
            InternalUploadIngestWriter ingestWriter,
            InternalUploadPathResolver pathResolver,
            @Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath,
            @Value("${authoring.upload.tus.max-file-size:524288000}") long maxFileSize,
            @Value("${authoring.upload.tus.max-chunk-bytes:16777216}") long maxChunkBytes,
            EventTypeService eventTypeService,
            DurationProbeFfprobe ffprobeProbe) {
        this(uploadRepository, videoRepository, ingestRepository, cctvRepository, ingestWriter,
                pathResolver, storageRawPath, maxFileSize, maxChunkBytes, eventTypeService,
                (DurationProbe) ffprobeProbe);
    }

    /** 테스트용 — DurationProbe 직접 주입 (청크 상한 기본값 적용). */
    public TusUploadService(
            LsTusUploadRepository uploadRepository,
            VideoRepository videoRepository,
            LsDataIngestRepository ingestRepository,
            MngResourceCctvRepository cctvRepository,
            InternalUploadIngestWriter ingestWriter,
            InternalUploadPathResolver pathResolver,
            String storageRawPath,
            long maxFileSize,
            EventTypeService eventTypeService,
            DurationProbe durationProbe) {
        this(uploadRepository, videoRepository, ingestRepository, cctvRepository, ingestWriter,
                pathResolver, storageRawPath, maxFileSize, DEFAULT_MAX_CHUNK_BYTES, eventTypeService,
                durationProbe);
    }

    /** 테스트용 — DurationProbe + 청크 상한 직접 주입. */
    public TusUploadService(
            LsTusUploadRepository uploadRepository,
            VideoRepository videoRepository,
            LsDataIngestRepository ingestRepository,
            MngResourceCctvRepository cctvRepository,
            InternalUploadIngestWriter ingestWriter,
            InternalUploadPathResolver pathResolver,
            String storageRawPath,
            long maxFileSize,
            long maxChunkBytes,
            EventTypeService eventTypeService,
            DurationProbe durationProbe) {
        this.uploadRepository = uploadRepository;
        this.videoRepository = videoRepository;
        this.ingestRepository = ingestRepository;
        this.cctvRepository = cctvRepository;
        this.ingestWriter = ingestWriter;
        this.pathResolver = pathResolver;
        this.storageRawPath = Paths.get(storageRawPath).toAbsolutePath().normalize();
        this.maxFileSize = maxFileSize;
        this.maxChunkBytes = maxChunkBytes > 0 ? maxChunkBytes : DEFAULT_MAX_CHUNK_BYTES;
        this.eventTypeService = eventTypeService;
        this.durationProbe = durationProbe;
    }

    // ======================== POST — 세션 생성 ========================

    /**
     * 업로드 세션 생성. 임시 파일(0바이트) 생성 + LS_TUS_UPLOAD 행 INSERT.
     *
     * @return 생성된 uploadId (Location 헤더로 노출)
     */
    @Transactional("controlTransactionManager")
    public UUID createSession(String userNo, TusCreateCommand cmd) {
        if (userNo == null || userNo.isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "세션 소유자를 확인할 수 없습니다.");
        }
        if (cmd.uploadLength() <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "Upload-Length 가 유효하지 않습니다.");
        }
        // HIGH-2: 전체 길이 사전 차단 — maxFileSize 초과 즉시 413.
        if (cmd.uploadLength() > maxFileSize) {
            throw new CustomException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "Upload-Length 가 허용 한도(" + maxFileSize + " bytes) 를 초과했습니다.");
        }
        // HIGH-6 사전: 확장자 allowlist (완료 시 매직바이트 2차 검증).
        String extension = resolveExtension(cmd.fileName());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "허용되지 않는 확장자입니다. 허용: " + ALLOWED_EXTENSIONS);
        }
        validateMeta(cmd);
        // HIGH-9: 사용자별 동시 진행 세션 상한 → 429.
        long inProgress = uploadRepository.countByUserNoAndStatus(userNo, LsTusUpload.STATUS_IN_PROGRESS);
        if (inProgress >= MAX_CONCURRENT_IN_PROGRESS) {
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS,
                    "동시 진행 가능한 업로드 세션 수(" + MAX_CONCURRENT_IN_PROGRESS + ") 를 초과했습니다.");
        }

        UUID uploadId = UUID.randomUUID();
        // HIGH-7: 저장 파일명은 UUID 강제 — filename 은 표시용만.
        String safeFileName = uploadId + "." + extension;
        Path absolutePath = resolveSafeStoragePath(safeFileName);
        try {
            // 0바이트 임시 파일 생성 — 이후 PATCH 가 position write 로 누적(파일 I/O 코어 위임).
            TusChunkStore.createEmptyFile(absolutePath);
        } catch (IOException e) {
            log.error("[Tus] temp file create failed uploadId={} causeType={}",
                    uploadId, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "업로드 임시 파일 생성에 실패했습니다.");
        }

        LocalDateTime capturedAt = cmd.capturedAt() != null
                ? LocalDateTime.ofInstant(cmd.capturedAt(), ZoneId.systemDefault()) : null;
        LsTusUpload session = LsTusUpload.create(uploadId, userNo, cmd.uploadLength(),
                absolutePath.toString(), cmd.fileName(), cmd.vmsClipId(), cmd.cctvId(),
                cmd.eventTypeCd(), cmd.localGovCd(), cmd.prvcTypeCd(), capturedAt);
        uploadRepository.save(session);

        log.info("[Tus] session created uploadId={} userNo={} length={} ext={}",
                uploadId, userNo, cmd.uploadLength(), extension);
        return uploadId;
    }

    // ======================== HEAD — offset 조회 ========================

    /**
     * 세션 조회 (HEAD). 만료(410)/부재(404)/소유자 아님(403) 검증 후 반환.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public LsTusUpload getForOwner(UUID uploadId, String userNo) {
        LsTusUpload session = uploadRepository.findById(uploadId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "업로드 세션을 찾을 수 없습니다."));
        // HIGH-8: 소유자 검증.
        if (!session.isOwnedBy(userNo)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "본인의 업로드 세션이 아닙니다.");
        }
        // HIGH-5: 만료 세션 → 410.
        if (session.isExpired(LocalDateTime.now())) {
            throw new CustomException(ErrorCode.GONE, "업로드 세션이 만료되었습니다.");
        }
        return session;
    }

    // ======================== PATCH — 청크 append ========================

    /**
     * 청크 append. 동일 세션 동시 PATCH 는 PESSIMISTIC_WRITE 행 잠금으로 직렬화하고(HIGH-1),
     * 잠금 우회 경로의 동시 갱신은 @Version 으로 409 처리(이중 방어).
     *
     * @param uploadId      세션 ID
     * @param userNo        호출자 (소유자 검증)
     * @param expectedOffset 클라이언트가 보낸 Upload-Offset
     * @param chunk         청크 입력 스트림
     * @param contentLength 청크 길이 (Content-Length)
     * @return 청크 기록 후의 새 오프셋
     */
    @Transactional("controlTransactionManager")
    public TusPatchResult appendChunk(UUID uploadId, String userNo, long expectedOffset,
                                      InputStream chunk, long contentLength) {
        // HIGH-1: PATCH 진입 시 세션 행을 PESSIMISTIC_WRITE 로 잠가 동일 세션의 동시 PATCH 를
        // 직렬화한다. 잠금 보유 구간에서 offset 검사 + 파일 write + offset 갱신을 원자적으로
        // 수행해 두 청크가 같은 위치에 교차 write 하거나 충돌 측 truncate 가 정상 바이트를 자르는
        // 파일 오염을 차단한다.
        LsTusUpload session = uploadRepository.findByUploadIdForUpdate(uploadId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "업로드 세션을 찾을 수 없습니다."));
        if (!session.isOwnedBy(userNo)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "본인의 업로드 세션이 아닙니다.");
        }
        if (session.isExpired(LocalDateTime.now())) {
            throw new CustomException(ErrorCode.GONE, "업로드 세션이 만료되었습니다.");
        }
        // HIGH-3: 이미 완료된 세션에 마지막 청크 재전송 → 멱등 응답.
        //   Phase 1 이후 완료 산출물은 인입 행이고 그 PK 는 세션에 보관하지 않으므로(LS_TUS_UPLOAD 에
        //   컬럼이 없다) 재전송 응답에는 식별자를 싣지 않는다. TUS 클라이언트는 Upload-Offset 만 본다.
        if (session.isCompleted()) {
            return new TusPatchResult(session.getUploadLength(), true, null);
        }
        // HIGH-9: 빈 청크 거부 (Content-Length 0 → 400).
        if (contentLength <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "청크 본문이 비어 있습니다.");
        }
        // HIGH-2: 단일 PATCH 청크 크기 상한 → 413 (Content-Length 선검증, 본문 스트리밍은 별도 누적 추적).
        if (contentLength > maxChunkBytes) {
            throw new CustomException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "청크 크기가 허용 한도(" + maxChunkBytes + " bytes) 를 초과했습니다.");
        }
        // HIGH-9: 음수/초과 오프셋 → 400.
        if (expectedOffset < 0 || expectedOffset > session.getUploadLength()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "Upload-Offset 값이 범위를 벗어났습니다.");
        }
        // HIGH-9: offset 불일치 → 409.
        if (expectedOffset != session.getUploadOffset()) {
            throw new CustomException(ErrorCode.CONFLICT, "Upload-Offset 이 서버 상태와 일치하지 않습니다.");
        }
        // HIGH-2(완료): 누적이 전체 길이를 넘으면 거부.
        if (expectedOffset + contentLength > session.getUploadLength()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "청크 길이가 잔여 용량을 초과합니다.");
        }

        // HIGH-1: 잠금 보유 구간에서 write → offset 전진 → 영속. truncate 도 잠금 보유 중에만 발생.
        long newOffset = writeChunkAtomically(session, expectedOffset, chunk, contentLength);
        session.advanceOffset(newOffset);

        try {
            // @Version 은 이중 방어 — 잠금 우회 경로가 있더라도 동시 갱신은 409.
            uploadRepository.saveAndFlush(session);
        } catch (OptimisticLockingFailureException e) {
            // 오프셋은 디스크에 기록됐으나 행 충돌 → truncate 로 일관성 복원 후 409.
            truncateTo(session.getFilePath(), expectedOffset);
            log.warn("[Tus] concurrent PATCH conflict uploadId={}", uploadId);
            throw new CustomException(ErrorCode.CONFLICT, "동시 업로드 요청이 충돌했습니다. 재시도하세요.");
        }

        if (session.isFullyUploaded()) {
            Long rcptnSn = complete(session);
            return new TusPatchResult(newOffset, true, rcptnSn);
        }
        return new TusPatchResult(newOffset, false, null);
    }

    // ======================== DELETE — 세션 취소 ========================

    @Transactional("controlTransactionManager")
    public void cancel(UUID uploadId, String userNo) {
        // 시나리오 #11: cancel 도 PESSIMISTIC_WRITE 로 세션을 잠가 동일 세션의 PATCH 와 직렬화한다.
        // (PATCH 는 findByUploadIdForUpdate 로 잠금 획득 — 락 경로 통일로 취소·청크쓰기 교차를 차단.)
        LsTusUpload session = uploadRepository.findByUploadIdForUpdate(uploadId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "업로드 세션을 찾을 수 없습니다."));
        if (!session.isOwnedBy(userNo)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "본인의 업로드 세션이 아닙니다.");
        }
        deleteQuietly(session.getFilePath());
        uploadRepository.delete(session);
        log.info("[Tus] session cancelled uploadId={}", uploadId);
    }

    // ======================== 완료 — LS_DATA_INGEST 합류 ========================

    /**
     * 업로드 완료 처리 — 파일을 인입 영역으로 옮기고 <b>인입 행(PENDING)</b> 을 남긴다 (Phase 1).
     *
     * <h3>왜 {@code LS_DATA_RAW} 를 직접 만들지 않는가</h3>
     * <p>적재 주체 반전 이후 영상 적재의 단일 통로는 <b>인입 폴링</b>
     * ({@code ControlTrainingVideoScanJob → TrainingVideoIngestTx})이다. 업로드가 자체적으로
     * {@code LS_DATA_RAW} 를 만들고 {@code VideoIngestedEvent} 까지 발행하면 적재 규칙(경로 allowlist·
     * 중복 판정·상태 전이·기술메타 back-fill)이 업로드에만 적용되지 않는 <b>두 번째 진실원</b>이 된다.
     * 여기서는 관제가 하는 일(= 인입 행 INSERT + 파일 배치)까지만 하고 나머지는 인입 경로에 맡긴다.
     *
     * <h3>순서 — 파일 이동이 DB 쓰기보다 먼저다</h3>
     * <ol>
     *   <li>매직바이트 검증(HIGH-6) → ffprobe 길이 추출</li>
     *   <li>임시 파일을 인입 영역으로 이동(트랜잭션과 무관한 파일 I/O)</li>
     *   <li>완료 전이(조건부 UPDATE) — <b>affectedRows==1 인 호출만</b> 인입 INSERT 책임을 갖는다</li>
     *   <li>인입 행 INSERT ({@code REQUIRES_NEW})</li>
     * </ol>
     * <p>이동을 먼저 하는 이유는 <b>인입 행이 가리키는 경로에 파일이 실재해야</b> 폴링이 적재하기
     * 때문이다(없으면 미도착 대기 → 상한 초과 종결).
     *
     * <h3>★ 비가역 파일 이동 &gt; 롤백 가능한 DB 쓰기 — 실패 경로는 <b>직접 되돌린다</b> (DEV_FIX F2)</h3>
     * <p>구 주석은 "INSERT 가 실패해 이동만 남으면 그 파일은 아무도 참조하지 않는 고아일 뿐이라 피해가
     * 없다"고 했다. <b>틀렸다.</b> 그 고아는 <b>비식별 전 원본(PII)</b>이고, 참조하는 인입 행이 없어
     * 비식별이 영원히 수행되지 않으며, {@code TusUploadCleanupJob} 은 세션의 <b>임시</b> 경로만 지우므로
     * <b>어떤 정리 주체도 이 파일을 삭제하지 않는다</b>(개인정보 보존기간 위반 소지). 덤으로
     * 파일명 선점 때문에 같은 clipId 재업로드가 영구 409 로 자기잠금된다.
     * <p>그래서 순서 재배치(INSERT 선점) 대신 <b>보상 삭제</b>를 택한다 — 순서를 뒤집으면 이번엔 커밋된
     * 인입 행이 없는 파일을 가리켜 폴링이 미도착 대기 후 종결되는, 되돌릴 수 없는 반대편 결함이 생긴다.
     * 실패 경로({@code transitioned != 1} · INSERT 예외) 두 곳 모두에서 옮긴 파일을 best-effort 삭제하고,
     * 삭제 실패는 WARN 으로 남겨 관측 가능하게 한다. 세션도 별도 트랜잭션으로 명시 종결해
     * 재시도가 "사라진 임시 파일에 이어쓰기"로 가지 않게 한다.
     *
     * @return 생성된 인입 행 PK({@code RCPTN_SN}). 완료 전이를 다른 트랜잭션이 선점했으면 null
     */
    private Long complete(LsTusUpload session) {
        Path temp = Paths.get(session.getFilePath());
        // HIGH-6: 완료 시 매직바이트 검증 — 실패 시 임시파일 삭제 + 409.
        if (!VideoMagicByteValidator.isVideoContainer(temp)) {
            deleteQuietly(session.getFilePath());
            // DEV_FIX F2 동형: 임시 파일을 이미 지웠는데 이 트랜잭션은 아래 예외로 롤백된다.
            //   같은 트랜잭션의 markExpired/save 는 함께 되돌아가 세션이 IN_PROGRESS 로 부활하므로,
            //   종결은 트랜잭션 완료 후 별도 트랜잭션에서 수행한다.
            terminateSessionAfterCompletion(session.getUploadId());
            log.warn("[Tus] magic byte check failed uploadId={}", session.getUploadId());
            throw new CustomException(ErrorCode.CONFLICT,
                    "업로드된 파일이 유효한 영상 컨테이너가 아닙니다.");
        }
        int durationSec = extractDurationSec(temp, session.getUploadId());

        String extension = resolveExtension(session.getFileName());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            // 세션 생성 시 이미 걸러진 값이라 도달 불가 — 도달했다면 세션 데이터 변조 신호다(fail-closed).
            log.error("[Tus] stored session has unsupported extension uploadId={}", session.getUploadId());
            throw new CustomException(ErrorCode.CONFLICT, "업로드 세션의 파일 형식이 유효하지 않습니다.");
        }
        Path target = pathResolver.resolveUploadTarget(session.getVmsClipId(), extension);
        moveIntoIngestArea(temp, target, session.getUploadId());

        // MED-1: 완료 전이를 DB 조건부 UPDATE(WHERE STATUS='IN_PROGRESS')로 강제.
        //   affectedRows==1 인 호출만 인입 INSERT 책임을 갖고, 0 이면 이미 다른 트랜잭션이 완료시킨
        //   것이므로 아무것도 만들지 않고 멱등 응답한다(인입 행 중복 생성 차단).
        //   RAW_SN 은 더 이상 여기서 정해지지 않으므로 null 로 둔다 — 적재 결과는 인입 행의
        //   LS_DATA_INGEST.RAW_SN 이 보유한다.
        int transitioned = uploadRepository.markCompletedIfInProgress(
                session.getUploadId(), null, LocalDateTime.now());
        if (transitioned != 1) {
            // 이 호출은 인입 INSERT 책임이 없다 = 방금 옮긴 파일을 참조할 인입 행이 영영 생기지 않는다.
            //   그대로 두면 비식별 전 원본이 인입 영역에 고아로 남는다(F2) — 즉시 회수한다.
            discardMovedFile(target, session.getUploadId(), "completion-already-claimed");
            log.info("[Tus] completion already claimed uploadId={}", session.getUploadId());
            return null;
        }
        warnDroppedMetaOnce(session);
        try {
            Long rcptnSn = ingestWriter.insertPending(InternalUploadIngestCommand.ofInternalUpload(
                    session.getVmsClipId(), session.getCctvId(),
                    target.getFileName().toString(), target.toString(),
                    session.getCapturedAt(), BigDecimal.valueOf(durationSec),
                    session.getUploadLength(), extension, session.getLocalGovCd()));
            log.info("[Tus] ingest row created uploadId={} rcptnSn={} durationSec={}",
                    session.getUploadId(), rcptnSn, durationSec);
            return rcptnSn;
        } catch (RuntimeException e) {
            // INSERT 가 REQUIRES_NEW 라 그 트랜잭션만 abort 되고 본 PATCH 트랜잭션은 살아 있어 응답을
            //   만들 수 있다(같은 트랜잭션이면 PostgreSQL 이 전체를 abort 해 불가능하다). 다만 본
            //   트랜잭션도 아래 예외로 <롤백>되므로 완료 전이·오프셋 전진이 모두 되돌아간다 —
            //   되돌지 않는 것은 <이미 옮겨진 파일>뿐이다(F2). 회수 + 세션 종결로 정합을 맞춘다.
            discardMovedFile(target, session.getUploadId(), "ingest-insert-failed");
            terminateSessionAfterCompletion(session.getUploadId());
            if (isUniqueViolation(e)) {
                // UK(VMS_CLIP_ID) 위반 — 세션 생성 시점 사전 조회 이후 같은 클립이 인입된 race.
                log.warn("[Tus] duplicate ingest row on completion uploadId={}", session.getUploadId());
                throw new CustomException(ErrorCode.CONFLICT,
                        "동일한 영상 클립 ID 의 인입 정보가 이미 존재합니다.");
            }
            // 그 외 무결성/DB 오류를 "클립 ID 중복"으로 오진단하면 원인 규명이 막힌다(F6).
            log.error("[Tus] ingest insert failed uploadId={} causeType={}",
                    session.getUploadId(), e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "업로드 인입 정보 저장에 실패했습니다.");
        }
    }

    /**
     * 인입 UK({@code VMS_CLIP_ID}) 위반만 409 로 좁힌다 — PostgreSQL 유니크 위반 SQLState {@code 23505}.
     *
     * <p>구 구현은 모든 {@code DataIntegrityViolationException} 을 "클립 ID 중복"으로 응답해, 경로 길이
     * 초과·NOT NULL 위반({@code 23502})·체크 제약 위반까지 같은 메시지로 덮어 원인 규명을 막았다(F6).
     */
    private static boolean isUniqueViolation(RuntimeException e) {
        if (e instanceof DuplicateKeyException) {
            return true;
        }
        if (!(e instanceof DataAccessException dae)) {
            return false;
        }
        Throwable specific = dae.getMostSpecificCause();
        return specific instanceof SQLException sql
                && SQL_STATE_UNIQUE_VIOLATION.equals(sql.getSQLState());
    }

    /**
     * 인입 영역으로 <b>이미 옮겨진</b> 파일을 회수한다(best-effort) — 참조할 인입 행이 없는 고아 방지.
     *
     * <p>삭제 실패는 조용히 넘기지 않고 WARN 으로 남긴다 — 그 순간부터 그 파일은 <b>아무도 지우지
     * 않는 비식별 전 원본</b>이므로 운영자가 알아야 한다(개인정보 보존기간). 삭제 대상은 우리가 방금
     * 만든 인입 영역 경로뿐이며, 삭제 직전 고정 allowlist 로 다시 판정한다(CWE-22 심층방어).
     */
    private void discardMovedFile(Path target, UUID uploadIdForLog, String reason) {
        try {
            pathResolver.verifyIngestable(target);
            boolean deleted = Files.deleteIfExists(target);
            log.warn("[Tus] moved file discarded uploadId={} reason={} deleted={}",
                    uploadIdForLog, reason, deleted);
        } catch (IOException | RuntimeException e) {
            log.warn("[Tus] moved file discard FAILED — 인입 영역에 비식별 전 원본이 남았을 수 있다."
                            + " 수동 확인 필요 uploadId={} reason={} causeType={}",
                    uploadIdForLog, reason, e.getClass().getSimpleName());
        }
    }

    /**
     * 세션을 <b>트랜잭션 완료 후</b> 별도 트랜잭션으로 종결한다 (F2).
     *
     * <p>이 메서드가 불리는 경로는 모두 곧 롤백된다 — 같은 트랜잭션에서 상태를 바꿔 봐야 함께 되돌아가
     * 세션이 {@code IN_PROGRESS} 로 부활하고, 클라이언트 재시도가 <b>이미 사라진 임시 파일</b>에
     * 이어쓰기를 시도한다(현재는 500, 원래 의도는 410 종결).
     *
     * <p>그렇다고 여기서 바로 {@code REQUIRES_NEW} UPDATE 를 날리면 <b>교착</b>한다 — 현재 트랜잭션이
     * 같은 행을 {@code PESSIMISTIC_WRITE} 로 잡고 있는데 별도 커넥션이 그 행을 UPDATE 하려 들기
     * 때문이다. 그래서 락이 풀리는 <b>트랜잭션 완료 직후</b>로 미룬다. 트랜잭션이 없는 문맥(단위
     * 테스트·직접 호출)에서는 즉시 수행한다.
     */
    private void terminateSessionAfterCompletion(UUID uploadId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            terminateSessionQuietly(uploadId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                terminateSessionQuietly(uploadId);
            }
        });
    }

    /** 세션 종결 — 실패해도 원 예외(409/500)를 가리지 않도록 삼키고 WARN 만 남긴다. */
    private void terminateSessionQuietly(UUID uploadId) {
        try {
            int terminated = uploadRepository.terminateSession(uploadId, LocalDateTime.now());
            log.info("[Tus] session terminated after failed completion uploadId={} rows={}",
                    uploadId, terminated);
        } catch (RuntimeException e) {
            log.warn("[Tus] session terminate failed uploadId={} causeType={}",
                    uploadId, e.getClass().getSimpleName());
        }
    }

    /**
     * 임시 파일 → 인입 영역 이동. 같은 파일시스템이면 원자적 rename, 다른 마운트면 copy+delete 폴백.
     *
     * <h3>파일명 선점은 <b>원자적</b>이어야 한다 (DEV_FIX F3, CWE-362/367)</h3>
     * <p>구 구현은 {@code Files.exists(target)} 로 걸렀지만 {@code ATOMIC_MOVE} 는 대상이 있으면
     * <b>조용히 대체</b>하므로, 검사와 이동 사이가 비원자 구간이었다. 같은 {@code vmsClipId} 로 두
     * 세션이 만들어질 수 있고(세션 테이블에 clipId UK 가 없다) 세션 락은 uploadId 단위라 이들을
     * 직렬화하지 못한다 — 동시 완료 시 <b>뒤에 온 파일이 앞의 파일을 대체</b>하고도 뒤쪽은 409 를 받아,
     * 인입 행이 가리키는 실체와 업로더의 인식이 어긋난 채 비식별·라벨링·관제 통지까지 흘러간다.
     * <p>{@link Files#createFile} 은 {@code O_EXCL} 이라 <b>생성 자체가 원자적 예약</b>이다. 예약에
     * 성공한 호출만 이동하고, 실패하면 {@link FileAlreadyExistsException} 으로 즉시 409 다(구현상
     * 이 예외가 {@code catch (IOException)} 에 삼켜져 409 대신 500 이 나가던 것도 함께 해소된다).
     * 예약 후 이동이 실패하면 0바이트 예약 파일이 남아 같은 clipId 가 영구 409 로 자기잠금되므로
     * 예약분을 되돌린다.
     */
    private void moveIntoIngestArea(Path temp, Path target, UUID uploadIdForLog) {
        boolean reserved = false;
        try {
            Files.createDirectories(target.getParent());
            // CWE-367/59 — 쓰기 직전, <고정 allowlist(raw-mount-roots)> 기준으로 실경로를 재판정한다.
            //   구 구현은 target.getParent() 를 uploadDir(=자기 자신) 기준으로 검사해 항등식이었고,
            //   경로 중간 디렉터리를 심링크로 교체하면 그대로 통과했다(F1). 판정 축은 독립이어야 한다.
            pathResolver.verifyIngestable(target);
            try {
                Files.createFile(target);
                reserved = true;
            } catch (FileAlreadyExistsException e) {
                log.warn("[Tus] ingest target already exists uploadId={}", uploadIdForLog);
                throw new CustomException(ErrorCode.CONFLICT,
                        "동일한 영상 클립 ID 의 파일이 이미 저장되어 있습니다.");
            }
            try {
                // 예약 파일(0바이트)을 우리 자신이 만들었으므로 REPLACE_EXISTING 은 자기 예약분 대체다.
                Files.move(temp, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // 임시 영역과 인입 영역이 다른 마운트 — 복사 후 원본 제거로 대체한다.
                Files.copy(temp, target, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            if (reserved) {
                // 예약만 남기면 같은 clipId 재업로드가 영구 409 로 잠긴다(가용성 자기잠금).
                discardMovedFile(target, uploadIdForLog, "move-failed-after-reserve");
            }
            log.error("[Tus] move into ingest area failed uploadId={} causeType={}",
                    uploadIdForLog, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "업로드 파일 저장에 실패했습니다.");
        }
    }

    /**
     * 인입 테이블에 자리가 없어 <b>버려지는</b> 세션 메타를 프로세스 1회 관측한다(조용한 유실 금지).
     *
     * <p>{@code prvcTypeCd}(개인정보 유형)·{@code eventTypeCd}(이벤트 유형)는 {@code LS_DATA_INGEST}
     * 에 컬럼이 <b>없다</b>(설계 R6 — 인입에는 관제가 보내는 값만 둔다). 적재 시 개인정보 유형은
     * {@code PRVC} fail-closed 기본값이 붙고 이벤트 유형은 null 이 된다. 화면에서 이 값을 받는
     * 메타 계약 자체는 Phase 3(FE 폼 개편)에서 정리하며, 그때까지 값이 사라진다는 사실만 남긴다.
     */
    private void warnDroppedMetaOnce(LsTusUpload session) {
        boolean hasDropped = StringUtils.hasText(session.getPrvcTypeCd())
                || StringUtils.hasText(session.getEventTypeCd());
        if (!hasDropped) {
            return;
        }
        if (droppedMetaWarned.compareAndSet(false, true)) {
            log.warn("[Tus] prvcTypeCd·eventTypeCd 는 LS_DATA_INGEST 에 컬럼이 없어 적재되지 않는다"
                    + " — 개인정보 유형은 적재 시 PRVC(fail-closed) 기본값, 이벤트 유형은 null 이 된다."
                    + " 메타 계약 정리는 Phase 3(FE 폼). 이후 동일 사례는 DEBUG 로만 남긴다. uploadId={}",
                    session.getUploadId());
        } else {
            log.debug("[Tus] session meta dropped (prvcTypeCd/eventTypeCd) uploadId={}",
                    session.getUploadId());
        }
    }

    // ======================== 내부 헬퍼 ========================

    /**
     * HIGH-2/HIGH-4: 청크 원자적 기록을 파일 I/O 코어({@link TusChunkStore})에 위임한다.
     * 트랜잭션/락/도메인 검증은 본 서비스가 담당하고, 순수 파일 I/O(스트리밍 write·상한·truncate
     * 롤백)만 코어가 처리한다(관제/포털 공유).
     *
     * @return 기록 완료 후의 누적 오프셋
     */
    private long writeChunkAtomically(LsTusUpload session, long offset, InputStream chunk, long contentLength) {
        return TusChunkStore.writeChunkAtomically(
                Paths.get(session.getFilePath()), offset, chunk, contentLength, maxChunkBytes);
    }

    private void truncateTo(String path, long size) {
        TusChunkStore.truncateTo(path, size);
    }

    /**
     * 보안 LOW(CWE-22 심층방어): 삭제 직전 대상 경로가 storageRawPath 하위인지 재검증한다(코어 위임).
     * 저장 경로는 생성 시 UUID 강제(HIGH-7)로 storage 내부가 보장되나, 데이터 변조 등으로
     * filePath 가 baseDir 밖을 가리키면 임의 파일 삭제로 이어질 수 있으므로 root 게이트를 둔다.
     */
    private void deleteQuietly(String path) {
        TusChunkStore.deleteQuietly(path, storageRawPath);
    }

    /** 보안 LOW: 코드성 메타 사전 검증 패턴 (AutolabelTestRequest 의 @Pattern 과 동일 SoT). */
    private static final java.util.regex.Pattern LOCAL_GOV_CD_PATTERN =
            java.util.regex.Pattern.compile("^[0-9]{1,10}$");
    /** prvcTypeCd allowlist — LsDataRaw 의 PRVC_TYPE_* 상수와 정합. */
    private static final Set<String> ALLOWED_PRVC_TYPES = Set.of("ANONY", "PRVC", "PSDO");
    /**
     * cctvId allowlist — 인입 {@code VMS_CCTV_ID VARCHAR(64)} 와 정합 (F6, CWE-20).
     *
     * <p>구 구현은 공백 여부만 봐서 65자 이상이 세션 생성(400)이 아니라 <b>완료 시점 INSERT</b> 에서
     * 값 초과로 터졌다 — 응답은 500 이고 0바이트 임시 파일이 남았다. {@code vmsClipId} 와 같은 문자
     * 집합으로 좁힌다(관제 인입도 같은 컬럼에 담는 값이다).
     */
    private static final java.util.regex.Pattern CCTV_ID_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private void validateMeta(TusCreateCommand cmd) {
        // Phase 1: vmsClipId 는 인입 UK 이자 <저장 파일명>이다. NOT NULL 이며 경로 문자를 허용하지
        //   않는다(CWE-22). 완료 시점에 늦게 터지지 않도록 세션 생성 단에서 fail-fast.
        InternalUploadPathResolver.validateClipId(cmd.vmsClipId());
        videoRepository.findByVmsClipId(cmd.vmsClipId()).ifPresent(existing -> {
            throw new CustomException(ErrorCode.CONFLICT, "동일한 vmsClipId 가 이미 존재합니다.");
        });
        // Phase 1: 인입 행은 <영구 보존>이라 DONE/FAILED 로 종결된 과거 행도 UK 를 점유한다.
        //   LS_DATA_RAW 조회만으로는(적재 실패로 영상이 없는 경우) 이 중복을 잡지 못해 완료 시점에
        //   제약 위반으로 터진다 — 입구에서 409 로 돌려준다.
        ingestRepository.findByVmsClipId(cmd.vmsClipId()).ifPresent(existing -> {
            throw new CustomException(ErrorCode.CONFLICT, "동일한 vmsClipId 의 인입 정보가 이미 존재합니다.");
        });
        // 인입 VMS_CCTV_ID 는 NOT NULL VARCHAR(64) — 값 필수 + 문자·길이 allowlist (F6).
        if (!StringUtils.hasText(cmd.cctvId())) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "cctvId 는 필수입니다.");
        }
        if (!CCTV_ID_PATTERN.matcher(cmd.cctvId()).matches()) {
            // CWE-209 — 입력 원문을 사용자 메시지에 담지 않는다(허용 규칙만 알린다).
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "cctvId 는 영문·숫자·'_'·'-' 조합 1~64자만 허용됩니다.");
        }
        // Phase 1: MNG_RESOURCE_CCTV <존재> 검증은 400 → 경고로 완화한다.
        //   이 테이블을 채우는 주체는 관제뿐이고 저작도구에는 local 시드 외 공급 경로가 없어, 400 을
        //   유지하면 dev/운영 형상에서 모든 업로드가 "등록되지 않은 CCTV"로 죽는다. 관제 인입 경로
        //   (TrainingVideoIngestTx)도 이 검증을 하지 않으므로 "관제 인입과 동일 재현" 원칙에도 맞는다.
        if (!cctvRepository.existsById(cmd.cctvId())) {
            log.warn("[Tus] unknown cctvId — 업로드는 계속한다(관제 인입도 존재 검증을 하지 않는다) cctvId={}",
                    LogSanitizer.sanitize(cmd.cctvId(), 64));
        }
        // 보안 LOW: 코드성 필드 사전 검증 — 완료 시점(LS_DATA_RAW 합류)에 늦게 터지는
        // DataIntegrity 실패 대신 세션 생성 단에서 fail-fast(400). 입력 검증(CWE-20).
        if (cmd.localGovCd() == null || !LOCAL_GOV_CD_PATTERN.matcher(cmd.localGovCd()).matches()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "localGovCd 는 숫자 1~10자리만 허용됩니다.");
        }
        // Phase 4a: EVT_* 하드코딩 제거 — 관제 마스터에 등록된 상세 EV-코드만 허용(빈값/null 은 미설정 허용).
        // categoryKeyOf 가 빈 Optional 이면 관제 미등록 코드 → 400.
        String eventTypeCd = cmd.eventTypeCd();
        if (eventTypeCd != null && !eventTypeCd.isBlank()
                && eventTypeService.categoryKeyOf(eventTypeCd).isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "지원하지 않는 이벤트 타입입니다.");
        }
        if (cmd.prvcTypeCd() == null || !ALLOWED_PRVC_TYPES.contains(cmd.prvcTypeCd())) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "지원하지 않는 개인정보 유형입니다. 허용: " + ALLOWED_PRVC_TYPES);
        }
    }

    int extractDurationSec(Path savedPath, UUID uploadIdForLog) {
        int durationSec;
        try {
            durationSec = durationProbe.probe(savedPath);
        } catch (RuntimeException e) {
            log.warn("[Tus] ffprobe failed uploadId={} causeType={}",
                    uploadIdForLog, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "영상 길이를 추출할 수 없습니다. 손상되었거나 지원되지 않는 형식일 수 있습니다.");
        }
        if (durationSec <= 0 || durationSec > MAX_DURATION_SEC) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "영상 길이가 유효 범위를 벗어났습니다.");
        }
        return durationSec;
    }

    private static String resolveExtension(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "";
        }
        String basename = originalFilename;
        int slash = Math.max(basename.lastIndexOf('/'), basename.lastIndexOf('\\'));
        if (slash >= 0) {
            basename = basename.substring(slash + 1);
        }
        int dot = basename.lastIndexOf('.');
        if (dot < 0 || dot == basename.length() - 1) {
            return "";
        }
        String ext = basename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("^[a-z0-9]{1,8}$") ? ext : "";
    }

    private Path resolveSafeStoragePath(String safeFileName) {
        Path candidate = storageRawPath.resolve(UPLOAD_SUBDIR).resolve(safeFileName).normalize();
        if (!candidate.startsWith(storageRawPath)) {
            log.warn("[Tus] path traversal attempt blocked");
            throw new CustomException(ErrorCode.FORBIDDEN, "저장 경로가 허용 범위를 벗어납니다.");
        }
        return candidate;
    }

    /**
     * PATCH 결과 — 새 오프셋 + 완료 여부 + (이번 호출이 완료를 확정했을 때) 인입 행 PK.
     *
     * <p>Phase 1 이후 완료 산출물은 {@code LS_DATA_RAW} 가 아니라 <b>인입 행</b>이다. 재전송·선점 등
     * 이번 호출이 완료를 확정하지 않은 경우 {@code rcptnSn} 은 null 이다(세션에 보관하지 않는다).
     * TUS 클라이언트는 {@code Upload-Offset} 헤더만 보므로 응답 계약에는 영향이 없다.
     */
    public record TusPatchResult(long newOffset, boolean completed, Long rcptnSn) {
    }

    /** 영상 파일 → duration(초) 추출 추상화 (테스트는 stub 주입). */
    @FunctionalInterface
    public interface DurationProbe {
        int probe(Path filePath);
    }
}
