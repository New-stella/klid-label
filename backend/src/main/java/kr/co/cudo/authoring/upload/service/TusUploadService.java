package kr.co.cudo.authoring.upload.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.upload.dto.TusCreateCommand;
import kr.co.cudo.authoring.upload.entity.LsTusUpload;
import kr.co.cudo.authoring.upload.repository.LsTusUploadRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.MngResourceCctvRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * TUS 1.0 재개 가능 업로드 코어 서비스 (CVAT 포팅 Phase 3 — 관리 화면 대용량 영상 적재).
 *
 * <p>운영(prd) 에서도 관리 화면 업로드는 필요하므로 {@code @Profile} 미적용 — 전 환경 활성.
 * 권한(REVIEWER, INTERNAL 채널)은 컨트롤러 {@code @PreAuthorize} + SecurityConfig 로 차단.
 *
 * <p>시나리오 방어 (HIGH 9):
 * <ol>
 *   <li>동시 PATCH 오프셋 충돌 → {@code @Version} 낙관적 잠금 → {@code OptimisticLockingFailureException} → 409.</li>
 *   <li>Upload-Length &gt; maxFileSize → POST 시 413.</li>
 *   <li>완료 중복 → {@link LsTusUpload#markCompleted(Long)} 원자 전이 + 멱등 응답(기존 rawSn).</li>
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
    /** HIGH-2: 청크 스트리밍 고정 버퍼 크기 (64KB) — 전체 byte[] 메모리 적재 회피. */
    private static final int CHUNK_STREAM_BUFFER_BYTES = 64 * 1024;
    /** HIGH-2: 청크당 상한 기본값 (16MB) — 설정 미지정 시 사용. */
    static final long DEFAULT_MAX_CHUNK_BYTES = 16L * 1024 * 1024;

    private final LsTusUploadRepository uploadRepository;
    private final VideoRepository videoRepository;
    private final MngResourceCctvRepository cctvRepository;
    private final Path storageRawPath;
    private final long maxFileSize;
    /** HIGH-2: 단일 PATCH 청크 크기 상한 (authoring.upload.tus.max-chunk-bytes). */
    private final long maxChunkBytes;
    private final DurationProbe durationProbe;

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
            MngResourceCctvRepository cctvRepository,
            @Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath,
            @Value("${authoring.upload.tus.max-file-size:524288000}") long maxFileSize,
            @Value("${authoring.upload.tus.max-chunk-bytes:16777216}") long maxChunkBytes,
            DurationProbeFfprobe ffprobeProbe) {
        this(uploadRepository, videoRepository, cctvRepository, storageRawPath, maxFileSize,
                maxChunkBytes, (DurationProbe) ffprobeProbe);
    }

    /** 테스트용 — DurationProbe 직접 주입으로 ffprobe 의존성 격리 (청크 상한 기본값 적용). */
    public TusUploadService(
            LsTusUploadRepository uploadRepository,
            VideoRepository videoRepository,
            MngResourceCctvRepository cctvRepository,
            String storageRawPath,
            long maxFileSize,
            DurationProbe durationProbe) {
        this(uploadRepository, videoRepository, cctvRepository, storageRawPath, maxFileSize,
                DEFAULT_MAX_CHUNK_BYTES, durationProbe);
    }

    /** 테스트용 — DurationProbe + 청크 상한 직접 주입. */
    public TusUploadService(
            LsTusUploadRepository uploadRepository,
            VideoRepository videoRepository,
            MngResourceCctvRepository cctvRepository,
            String storageRawPath,
            long maxFileSize,
            long maxChunkBytes,
            DurationProbe durationProbe) {
        this.uploadRepository = uploadRepository;
        this.videoRepository = videoRepository;
        this.cctvRepository = cctvRepository;
        this.storageRawPath = Paths.get(storageRawPath).toAbsolutePath().normalize();
        this.maxFileSize = maxFileSize;
        this.maxChunkBytes = maxChunkBytes > 0 ? maxChunkBytes : DEFAULT_MAX_CHUNK_BYTES;
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
            Files.createDirectories(absolutePath.getParent());
            // 0바이트 임시 파일 생성 — 이후 PATCH 가 position write 로 누적.
            Files.write(absolutePath, new byte[0]);
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
        if (session.isCompleted()) {
            return new TusPatchResult(session.getUploadLength(), true, session.getRawSn());
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
            Long rawSn = complete(session);
            return new TusPatchResult(newOffset, true, rawSn);
        }
        return new TusPatchResult(newOffset, false, null);
    }

    // ======================== DELETE — 세션 취소 ========================

    @Transactional("controlTransactionManager")
    public void cancel(UUID uploadId, String userNo) {
        LsTusUpload session = uploadRepository.findById(uploadId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "업로드 세션을 찾을 수 없습니다."));
        if (!session.isOwnedBy(userNo)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "본인의 업로드 세션이 아닙니다.");
        }
        deleteQuietly(session.getFilePath());
        uploadRepository.delete(session);
        log.info("[Tus] session cancelled uploadId={}", uploadId);
    }

    // ======================== 완료 — LS_DATA_RAW 합류 ========================

    private Long complete(LsTusUpload session) {
        Path file = Paths.get(session.getFilePath());
        // HIGH-6: 완료 시 매직바이트 검증 — 실패 시 임시파일 삭제 + 409.
        if (!VideoMagicByteValidator.isVideoContainer(file)) {
            deleteQuietly(session.getFilePath());
            session.markExpired();
            uploadRepository.save(session);
            log.warn("[Tus] magic byte check failed uploadId={}", session.getUploadId());
            throw new CustomException(ErrorCode.CONFLICT,
                    "업로드된 파일이 유효한 영상 컨테이너가 아닙니다.");
        }
        int durationSec = extractDurationSec(file, session.getUploadId());

        LocalDateTime capturedAt = session.getCapturedAt() != null
                ? session.getCapturedAt() : LocalDateTime.now();
        LsDataRaw raw = LsDataRaw.createFromIngest(
                session.getVmsClipId(), session.getCctvId(), session.getEventTypeCd(),
                session.getLocalGovCd(), session.getPrvcTypeCd(),
                file.toString(), capturedAt, durationSec);
        LsDataRaw saved = videoRepository.save(raw);
        Long rawSn = saved.getRawSn();

        // MED-1: 완료 전이를 DB 조건부 UPDATE(WHERE STATUS='IN_PROGRESS')로 강제.
        // affectedRows==1 인 호출만 LS_DATA_RAW 를 보존하고, 0 이면 이미 다른 트랜잭션이
        // 완료시킨 것이므로 방금 INSERT 한 LS_DATA_RAW 를 롤백(삭제)하고 기존 rawSn 으로 멱등 응답.
        int transitioned = uploadRepository.markCompletedIfInProgress(
                session.getUploadId(), rawSn, LocalDateTime.now());
        if (transitioned != 1) {
            videoRepository.delete(saved);
            LsTusUpload current = uploadRepository.findById(session.getUploadId())
                    .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "업로드 세션을 찾을 수 없습니다."));
            log.info("[Tus] completion already claimed uploadId={} existingRawSn={}",
                    session.getUploadId(), current.getRawSn());
            return current.getRawSn();
        }
        // 완료 전이는 DB 조건부 UPDATE 로 이미 영속화됨(@Modifying clearAutomatically) — 추가 save 불필요.
        log.info("[Tus] completed uploadId={} rawSn={} durationSec={}",
                session.getUploadId(), rawSn, durationSec);
        return rawSn;
    }

    // ======================== 내부 헬퍼 ========================

    /**
     * HIGH-2/HIGH-4: 청크 입력을 고정 64KB 버퍼 루프로 스트리밍하며 FileChannel 의 정확한
     * 오프셋에 기록한다. 전체 byte[] 를 메모리에 적재하지 않으므로 OOM 위험이 없다.
     *
     * <p>누적 기록량이 {@code maxChunkBytes} 를 넘으면 즉시 413 + truncate 롤백한다(스트리밍
     * 도중 클라이언트가 Content-Length 보다 더 보내는 경우 방어). 부분 쓰기 실패 시에는 기록
     * 시작 오프셋으로 truncate 하고 offset 을 갱신하지 않아 재시도 시 동일 오프셋 재요청이 된다.
     *
     * @return 기록 완료 후의 누적 오프셋
     */
    private long writeChunkAtomically(LsTusUpload session, long offset, InputStream chunk, long contentLength) {
        Path file = Paths.get(session.getFilePath());
        long written = 0L;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.position(offset);
            byte[] buf = new byte[CHUNK_STREAM_BUFFER_BYTES];
            int r;
            while (written < contentLength && (r = chunk.read(buf)) != -1) {
                int toWrite = (int) Math.min(r, contentLength - written);
                // HIGH-2: 스트리밍 누적 상한 — 한도 초과 시 413 + truncate 롤백.
                if (written + toWrite > maxChunkBytes) {
                    truncateTo(session.getFilePath(), offset);
                    throw new CustomException(ErrorCode.PAYLOAD_TOO_LARGE,
                            "청크 크기가 허용 한도(" + maxChunkBytes + " bytes) 를 초과했습니다.");
                }
                ch.write(java.nio.ByteBuffer.wrap(buf, 0, toWrite));
                written += toWrite;
            }
            ch.force(true);
        } catch (CustomException e) {
            throw e;
        } catch (IOException e) {
            // 부분 쓰기 실패 → 기록 시작 오프셋으로 truncate, offset 미갱신.
            truncateTo(session.getFilePath(), offset);
            log.error("[Tus] chunk write failed uploadId={} causeType={}",
                    session.getUploadId(), e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "청크 저장에 실패했습니다.");
        }
        if (written != contentLength) {
            truncateTo(session.getFilePath(), offset);
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "청크 본문 길이가 Content-Length 와 일치하지 않습니다.");
        }
        return offset + written;
    }

    private void truncateTo(String path, long size) {
        try (FileChannel ch = FileChannel.open(Paths.get(path), StandardOpenOption.WRITE)) {
            ch.truncate(size);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /**
     * 보안 LOW(CWE-22 심층방어): 삭제 직전 대상 경로가 storageRawPath 하위인지 재검증한다.
     * 저장 경로는 생성 시 UUID 강제(HIGH-7)로 storage 내부가 보장되나, 데이터 변조 등으로
     * filePath 가 baseDir 밖을 가리키면 임의 파일 삭제로 이어질 수 있으므로 best-effort 삭제 전
     * normalize().startsWith() 게이트를 둔다.
     */
    private void deleteQuietly(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        Path target = Paths.get(path).toAbsolutePath().normalize();
        if (!target.startsWith(storageRawPath)) {
            log.warn("[Tus] delete blocked — path outside storage root");
            return;
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
            // best-effort
        }
    }

    /** 보안 LOW: 코드성 메타 사전 검증 패턴 (AutolabelTestRequest 의 @Pattern 과 동일 SoT). */
    private static final java.util.regex.Pattern LOCAL_GOV_CD_PATTERN =
            java.util.regex.Pattern.compile("^[0-9]{1,10}$");
    private static final java.util.regex.Pattern EVENT_TYPE_CD_PATTERN =
            java.util.regex.Pattern.compile("^EVT_(FALL|VIOLENCE|ACCIDENT|ABNORMAL|FLOOD|FIRE)$");
    /** prvcTypeCd allowlist — LsDataRaw 의 PRVC_TYPE_* 상수와 정합. */
    private static final Set<String> ALLOWED_PRVC_TYPES = Set.of("ANONY", "PRVC", "PSDO");

    private void validateMeta(TusCreateCommand cmd) {
        if (cmd.vmsClipId() != null) {
            videoRepository.findByVmsClipId(cmd.vmsClipId()).ifPresent(existing -> {
                throw new CustomException(ErrorCode.CONFLICT, "동일한 vmsClipId 가 이미 존재합니다.");
            });
        }
        if (cmd.cctvId() == null || !cctvRepository.existsById(cmd.cctvId())) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "등록되지 않은 CCTV 입니다.");
        }
        // 보안 LOW: 코드성 필드 사전 검증 — 완료 시점(LS_DATA_RAW 합류)에 늦게 터지는
        // DataIntegrity 실패 대신 세션 생성 단에서 fail-fast(400). 입력 검증(CWE-20).
        if (cmd.localGovCd() == null || !LOCAL_GOV_CD_PATTERN.matcher(cmd.localGovCd()).matches()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "localGovCd 는 숫자 1~10자리만 허용됩니다.");
        }
        if (cmd.eventTypeCd() == null || !EVENT_TYPE_CD_PATTERN.matcher(cmd.eventTypeCd()).matches()) {
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

    /** PATCH 결과 — 새 오프셋 + 완료 여부 + (완료 시) rawSn. */
    public record TusPatchResult(long newOffset, boolean completed, Long rawSn) {
    }

    /** 영상 파일 → duration(초) 추출 추상화 (테스트는 stub 주입). */
    @FunctionalInterface
    public interface DurationProbe {
        int probe(Path filePath);
    }
}
