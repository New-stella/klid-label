package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.portal.config.PortalUploadProperties;
import kr.co.cudo.authoring.portal.dto.PortalTusCreateCommand;
import kr.co.cudo.authoring.portal.entity.LsPortalTusUpload;
import kr.co.cudo.authoring.portal.repository.LsPortalTusUploadRepository;
import kr.co.cudo.authoring.upload.service.TusChunkStore;
import kr.co.cudo.authoring.upload.service.VideoMagicByteValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 포털 전용 TUS 영상 업로드 서비스 (PORTAL_USER).
 *
 * <p>관제 {@code TusUploadService} 와 <b>동일한 파일 I/O 코어({@link TusChunkStore})</b>를 공유하되,
 * 세션 저장소(LS_PORTAL_TUS_ULD)·완료 합류처(LS_PORTAL_ULD)·완료 이벤트
 * ({@code PortalVideoUploadedEvent})는 <b>포털 전용으로 완전 분리</b>한다(관제 비식별 파이프라인
 * 미연결 — 시나리오 #18).
 *
 * <p>시나리오 방어:
 * <ol>
 *   <li>#3 IDOR — {@link LsPortalTusUpload#isOwnedBy(String)} 로 소유자만 HEAD/PATCH/DELETE.
 *       소유자 불일치는 <b>미존재와 같은 404</b>다(존재 오라클 차단 —
 *       {@link PortalVideoUploadTxService#sessionNotFound()}).</li>
 *   <li>#6 완료 검증 — 매직바이트 + ffprobe 비디오 스트림 존재. 검증은 <b>락/트랜잭션 밖</b>에서
 *       수행(security M-1: 커넥션·락 장시간 점유 방지). 실패 시 파일 삭제 + CANCELLED 영속 + 400.</li>
 *   <li>#10 완료 멱등 — DB 조건부 UPDATE(markCompletedIfInProgress) affectedRows==1 만 이벤트 발행.</li>
 *   <li>#11 cancel 락 — {@code findByUldIdForUpdate}(PESSIMISTIC_WRITE)로 PATCH 와 직렬화.</li>
 *   <li>입력검증 — Upload-Length 상한(5GB), 청크 상한, 확장자 allowlist, offset 정합.</li>
 * </ol>
 *
 * <p>트랜잭션 경계는 {@link PortalVideoUploadTxService} 로 위임한다. 본 빈의 {@link #appendChunk}
 * 는 비트랜잭션 오케스트레이터로, 짧은 tx(청크 write) 후 락을 해제한 상태에서 검증을 수행하고 다시
 * 짧은 tx(완료/거부 확정)로 위임한다.
 */
@Slf4j
@Service
public class PortalVideoUploadService {

    private static final String TUS_VIDEO_SUBDIR = "tus-video";
    /** 사용자별 동시 진행 세션 상한. */
    private static final int MAX_CONCURRENT_IN_PROGRESS = 3;

    private static final Map<String, String> EXT_TO_MIME = Map.of(
            "mp4", "video/mp4",
            "mov", "video/quicktime",
            "avi", "video/x-msvideo",
            "webm", "video/webm",
            "mkv", "video/x-matroska");

    private final LsPortalTusUploadRepository tusRepository;
    private final PortalUploadProperties properties;
    private final PortalVideoProbe videoProbe;
    private final PortalVideoUploadTxService txService;
    private final Path storageRoot;

    public PortalVideoUploadService(LsPortalTusUploadRepository tusRepository,
                                    PortalUploadProperties properties,
                                    PortalVideoProbe videoProbe,
                                    PortalVideoUploadTxService txService) {
        this.tusRepository = tusRepository;
        this.properties = properties;
        this.videoProbe = videoProbe;
        this.txService = txService;
        this.storageRoot = Paths.get(properties.storagePath()).toAbsolutePath().normalize();
    }

    // ======================== POST — 세션 생성 ========================

    @Transactional("controlTransactionManager")
    public UUID createSession(String portalUserNo, PortalTusCreateCommand cmd) {
        requireOwner(portalUserNo);
        if (cmd.uploadLength() <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "Upload-Length 가 유효하지 않습니다.");
        }
        // 입력검증: 전체 길이 상한(포털 5GB) 사전 차단 → 413.
        if (cmd.uploadLength() > properties.maxFileSizeBytes()) {
            throw new CustomException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "Upload-Length 가 허용 한도(" + properties.maxFileSizeBytes() + " bytes) 를 초과했습니다.");
        }
        String extension = resolveExtension(cmd.fileName());
        if (!properties.allowedExtensions().contains(extension)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "허용되지 않는 확장자입니다. 허용: " + properties.allowedExtensions());
        }
        // 동시 진행 세션 상한 → 429.
        long inProgress = tusRepository.countByPortalUserNoAndSttsCd(
                portalUserNo, LsPortalTusUpload.STTS_IN_PROGRESS);
        if (inProgress >= MAX_CONCURRENT_IN_PROGRESS) {
            throw new CustomException(ErrorCode.TOO_MANY_REQUESTS,
                    "동시 진행 가능한 업로드 세션 수(" + MAX_CONCURRENT_IN_PROGRESS + ") 를 초과했습니다.");
        }

        UUID uldId = UUID.randomUUID();
        // 저장 파일명 UUID 강제 — filename 은 표시용만(경로 순회 차단).
        String safeFileName = uldId + "." + extension;
        Path absolutePath = resolveSafe(storageRoot.resolve(TUS_VIDEO_SUBDIR).resolve(safeFileName));
        try {
            TusChunkStore.createEmptyFile(absolutePath);
        } catch (IOException e) {
            log.error("[PortalTus] temp file create failed uldId={} causeType={}",
                    uldId, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "업로드 임시 파일 생성에 실패했습니다.");
        }

        LsPortalTusUpload session = LsPortalTusUpload.create(
                uldId, portalUserNo, cmd.uploadLength(), absolutePath.toString(), truncate(cmd.fileName()));
        tusRepository.save(session);
        log.info("[PortalTus] session created uldId={} user={} length={} ext={}",
                uldId, LogSanitizer.sanitize(portalUserNo), cmd.uploadLength(), extension);
        return uldId;
    }

    // ======================== HEAD — offset 조회 ========================

    /**
     * 세션 조회 (HEAD). 만료(410)/부재·소유자 아님(404) 검증 후 반환.
     *
     * <p>소유자 불일치는 {@link PortalVideoUploadTxService#sessionNotFound()} 로 <b>미존재와 같은
     * 404</b>다(존재 오라클 차단 — 근거는 그 팩토리의 javadoc). 인증 401·역할 403 은 그대로다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public LsPortalTusUpload getForOwner(UUID uldId, String portalUserNo) {
        LsPortalTusUpload session = tusRepository.findById(uldId)
                .orElseThrow(PortalVideoUploadTxService::sessionNotFound);
        // 소유자 불일치 = 미존재와 동일한 404 (존재 오라클 차단).
        if (!session.isOwnedBy(portalUserNo)) {
            throw PortalVideoUploadTxService.sessionNotFound();
        }
        if (session.isExpired(java.time.LocalDateTime.now())) {
            throw new CustomException(ErrorCode.GONE, "업로드 세션이 만료되었습니다.");
        }
        return session;
    }

    // ======================== PATCH — 청크 append ========================

    /**
     * 청크 append 오케스트레이터(비트랜잭션). 짧은 tx({@link PortalVideoUploadTxService#appendChunkTx})
     * 로 offset 검증·write·전진만 수행해 락을 즉시 해제하고, 완료 후보면 <b>락 밖</b>에서 매직바이트 +
     * ffprobe 검증을 돌린 뒤 짧은 tx 로 완료/거부를 확정한다(security M-1/M-2).
     */
    public PortalTusPatchResult appendChunk(UUID uldId, String portalUserNo, long expectedOffset,
                                            InputStream chunk, long contentLength) {
        PortalVideoUploadTxService.AppendOutcome outcome =
                txService.appendChunkTx(uldId, portalUserNo, expectedOffset, chunk, contentLength);

        if (!outcome.completionCandidate()) {
            // 진행 중이거나(uldSn null), 이미 완료된 세션의 멱등 재전송(uldSn 보유).
            if (outcome.alreadyCompletedUldSn() != null) {
                return new PortalTusPatchResult(outcome.newOffset(), true, outcome.alreadyCompletedUldSn());
            }
            return new PortalTusPatchResult(outcome.newOffset(), false, null);
        }

        // 완료 후보 — 락 해제 상태에서 검증(장시간 ffprobe 가 커넥션/락을 점유하지 않음).
        Path file = Paths.get(outcome.filePathNm());
        // #6: 매직바이트 검증 실패 = 입력 검증 실패 → 거부 확정(CANCELLED 영속) + 400.
        if (!VideoMagicByteValidator.isVideoContainer(file)) {
            txService.finalizeRejected(uldId, "매직바이트 불일치");
            throw new CustomException(ErrorCode.INVALID_INPUT, "업로드된 파일이 유효한 영상 컨테이너가 아닙니다.");
        }
        // #6: ffprobe — 비디오 스트림 존재 검증(오디오 전용/손상 거부).
        PortalVideoProbe.Result probe;
        try {
            probe = videoProbe.probe(file);
        } catch (RuntimeException e) {
            txService.finalizeRejected(uldId, "ffprobe 실패");
            log.warn("[PortalTus] probe failed uldId={} causeType={}", uldId, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "영상을 확인할 수 없습니다. 손상되었거나 지원되지 않는 형식일 수 있습니다.");
        }
        if (!probe.hasVideoStream()) {
            txService.finalizeRejected(uldId, "비디오 스트림 없음");
            throw new CustomException(ErrorCode.INVALID_INPUT, "비디오 스트림이 없는 파일입니다(오디오 전용 등).");
        }

        String extension = resolveExtension(outcome.orgnlFileNm());
        String mime = EXT_TO_MIME.getOrDefault(extension, "video/mp4");
        Long uldSn = txService.finalizeCompleted(uldId, outcome.portalUserNo(), outcome.orgnlFileNm(),
                outcome.filePathNm(), outcome.lengthBytes(), mime);
        return new PortalTusPatchResult(outcome.newOffset(), true, uldSn);
    }

    // ======================== DELETE — 세션 취소 ========================

    /**
     * 세션 취소 (DELETE).
     *
     * <p>소유자 불일치는 {@link PortalVideoUploadTxService#sessionNotFound()} 로 <b>미존재와 같은
     * 404</b>다. 취소는 <b>파괴적 조작</b>이라 조회보다 오라클 가치가 크다(성공/실패로 실재가 드러나면
     * 안 된다). 거부 시 임시파일 삭제·CANCELLED 전이는 <b>어느 것도 실행되지 않는다</b>(판정이 먼저다).
     */
    @Transactional("controlTransactionManager")
    public void cancel(UUID uldId, String portalUserNo) {
        // #11: cancel 도 행 잠금 — PATCH 와 락 경로 통일.
        LsPortalTusUpload session = tusRepository.findByUldIdForUpdate(uldId)
                .orElseThrow(PortalVideoUploadTxService::sessionNotFound);
        // 소유자 불일치 = 미존재와 동일한 404 (존재 오라클 차단, 위 javadoc).
        if (!session.isOwnedBy(portalUserNo)) {
            throw PortalVideoUploadTxService.sessionNotFound();
        }
        // 이미 완료된 세션: 임시 파일은 LS_PORTAL_ULD 의 영구 영상이므로 삭제하지 않고 no-op.
        if (session.isCompleted()) {
            return;
        }
        TusChunkStore.deleteQuietly(session.getFilePathNm(), storageRoot);
        session.markCancelled();
        tusRepository.save(session);
        log.info("[PortalTus] session cancelled uldId={}", uldId);
    }

    // ======================== 헬퍼 ========================

    private Path resolveSafe(Path candidate) {
        Path resolved = candidate.isAbsolute() ? candidate.normalize()
                : storageRoot.resolve(candidate).normalize();
        if (!resolved.startsWith(storageRoot)) {
            log.warn("[PortalTus] path traversal blocked");
            throw new CustomException(ErrorCode.FORBIDDEN, "저장 경로가 허용 범위를 벗어납니다.");
        }
        return resolved;
    }

    private void requireOwner(String portalUserNo) {
        if (portalUserNo == null || portalUserNo.isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
    }

    private static String truncate(String name) {
        if (name == null) {
            return null;
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
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

    /** PATCH 결과 — 새 오프셋 + 완료 여부 + (완료 시) uldSn. */
    public record PortalTusPatchResult(long newOffset, boolean completed, Long uldSn) {
    }
}
