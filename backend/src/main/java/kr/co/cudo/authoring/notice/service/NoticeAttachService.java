package kr.co.cudo.authoring.notice.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.notice.entity.LsNoticeAttach;
import kr.co.cudo.authoring.notice.repository.LsNoticeAttachRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 게시판(공지) 첨부파일 응용 서비스.
 *
 * <p>업로드 검증 → UUID 저장 → 다운로드 Resource → 삭제(물리 파일 포함) 를 담당한다.
 * 공지 가시성(DRAFT 은 WORKER 에게 404) 판정은 {@link NoticeService#get(long, TokenClaims)}
 * 에 위임해 Phase 1 규칙과 일관성을 유지한다.
 *
 * <p>보안 가드:
 * <ul>
 *   <li>CWE-434 — 확장자 allowlist + 크기 상한(20MB) + 빈 파일/원본명 길이 검증.</li>
 *   <li>CWE-22 — 저장 파일명 UUID 재명명 + storage 기준 경로 normalize/startsWith 검증(업로드·다운로드).</li>
 *   <li>CWE-639 — 다운로드/삭제 시 (attachSn, noticeSn) 동시 조회로 소속 검증.</li>
 *   <li>CWE-209 — 응답·예외 메시지에 절대 경로 미노출.</li>
 *   <li>CWE-117 — 로그에 원본 파일명 미출력 (attachSn/UUID 만).</li>
 * </ul>
 */
@Slf4j
@Service
public class NoticeAttachService {

    /** CWE-434 확장자 allowlist (소문자 비교). */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "png", "jpg", "jpeg", "zip", "hwp", "hwpx");

    /** 첨부 저장 storage 하위 디렉토리. */
    private static final String UPLOAD_SUBDIR = "notice-attach";

    /** 파일 크기 상한 — 20MB. */
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024;

    /** 원본 파일명 최대 길이 (DB 컬럼 ORGNL_FILE_NM VARCHAR(255) 정합). */
    private static final int MAX_ORIGINAL_NAME_LENGTH = 255;

    private final LsNoticeAttachRepository attachRepository;
    private final NoticeService noticeService;

    private final Path storageBasePath;

    public NoticeAttachService(LsNoticeAttachRepository attachRepository,
                               NoticeService noticeService,
                               @Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath) {
        this.attachRepository = attachRepository;
        this.noticeService = noticeService;
        this.storageBasePath = Paths.get(storageRawPath).toAbsolutePath().normalize();
    }

    /**
     * 첨부 업로드. 공지 존재(REVIEWER 전용 호출이므로 가시성은 항상 통과)를 확인하고
     * 검증·UUID 저장 후 DB row 를 생성한다.
     */
    @Transactional("controlTransactionManager")
    public LsNoticeAttach upload(long noticeId, MultipartFile file, TokenClaims actor) {
        // 공지 존재/가시성 확인 (없으면 404). 업로드는 REVIEWER 전용이므로 DRAFT 도 통과.
        noticeService.get(noticeId, actor);

        // MINOR 1 — nullable getOriginalFilename() 1회만 호출.
        String originalName = file.getOriginalFilename();
        validateFile(file, originalName);
        String extension = resolveExtension(originalName);

        String storeFileName = UUID.randomUUID() + "." + extension;
        Path savedAbsolutePath = resolveSafeStoragePath(storeFileName);

        try {
            Files.createDirectories(savedAbsolutePath.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, savedAbsolutePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("[NoticeAttach] file save failed noticeSn={} ext={}", noticeId, extension, e);
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "첨부파일 저장에 실패했습니다.");
        }

        // MAJOR 1 — 파일 쓰기 성공 후 DB save 가 실패/롤백되면 물리 파일이 고아로 남는다.
        // 트랜잭션 롤백 시점에만 방금 쓴 파일을 정리한다 (커밋되면 그대로 유지).
        registerOrphanCleanupOnRollback(savedAbsolutePath);

        LsNoticeAttach attach = LsNoticeAttach.create(
                noticeId, originalName, storeFileName,
                savedAbsolutePath.toString(), file.getSize());
        LsNoticeAttach saved = attachRepository.save(attach);

        // CWE-117 — 원본 파일명 미출력 (attachSn/ext/size 만).
        log.info("[NoticeAttach] uploaded attachSn={} noticeSn={} ext={} sizeBytes={}",
                saved.getAttachSn(), noticeId, extension, file.getSize());
        return saved;
    }

    /**
     * 첨부 다운로드. 공지 가시성(DRAFT → WORKER 404)을 먼저 판정한 뒤,
     * (attachSn, noticeSn) 동시 조회로 소속을 검증(IDOR)하고 파일 Resource 를 반환한다.
     *
     * @return Resource + 복원할 원본 파일명을 담은 {@link Download}
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Download download(long noticeId, long attachId, TokenClaims actor) {
        // 가시성 판정 — DRAFT 공지의 첨부는 WORKER 에게 404 (Phase 1 규칙 재사용).
        noticeService.get(noticeId, actor);

        LsNoticeAttach attach = attachRepository.findByAttachSnAndNoticeSn(attachId, noticeId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "첨부파일을 찾을 수 없습니다."));

        Path resolved = resolveSafeExistingPath(attach.getFilePath());
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            log.warn("[NoticeAttach] file missing attachSn={} noticeSn={}", attachId, noticeId);
            throw new CustomException(ErrorCode.NOT_FOUND, "첨부파일이 존재하지 않습니다.");
        }

        try {
            long contentLength = Files.size(resolved);
            InputStream in = Files.newInputStream(resolved);
            return new Download(new InputStreamResource(in), attach.getOrgnlFileNm(), contentLength);
        } catch (IOException e) {
            log.error("[NoticeAttach] file read failed attachSn={} noticeSn={}", attachId, noticeId, e);
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "첨부파일을 읽을 수 없습니다.");
        }
    }

    /**
     * 첨부 삭제 (REVIEWER). (attachSn, noticeSn) 동시 조회로 소속 검증 후
     * DB row + 물리 파일을 모두 삭제한다.
     */
    @Transactional("controlTransactionManager")
    public void delete(long noticeId, long attachId, TokenClaims actor) {
        noticeService.get(noticeId, actor);

        LsNoticeAttach attach = attachRepository.findByAttachSnAndNoticeSn(attachId, noticeId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "첨부파일을 찾을 수 없습니다."));

        // MAJOR 2 — DB row 삭제를 먼저 수행하고, 물리 파일 삭제는 커밋 성공 후(afterCommit)에만 한다.
        // (구 순서는 파일 먼저 삭제 → DB 롤백 시 파일만 사라지는 불일치를 유발했다.)
        attachRepository.delete(attach);
        registerPhysicalDeleteOnCommit(attach);
        log.info("[NoticeAttach] deleted attachSn={} noticeSn={}", attachId, noticeId);
    }

    /** 특정 공지의 첨부 목록 조회 (상세 응답용). */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<LsNoticeAttach> listByNotice(long noticeId) {
        return attachRepository.findAllByNoticeSn(noticeId);
    }

    /**
     * 공지 삭제에 연계되는 물리 파일 정리 (MINOR 3 — 정리 로직 일원화 단일 진입점).
     * DB row 는 FK ON DELETE CASCADE 로 공지 삭제와 함께 제거되므로 여기서는 파일만 제거하되,
     * 커밋이 성공한 뒤(afterCommit)에만 삭제해 롤백 시 파일/DB 불일치를 방지한다.
     * 공지 삭제 트랜잭션 내에서 {@link NoticeService#delete(long)} 가 위임 호출한다.
     */
    public void cleanupPhysicalFiles(long noticeId) {
        List<LsNoticeAttach> attaches = attachRepository.findAllByNoticeSn(noticeId);
        for (LsNoticeAttach attach : attaches) {
            registerPhysicalDeleteOnCommit(attach);
        }
    }

    /**
     * MAJOR 1 — 트랜잭션 롤백 시에만 방금 쓴 물리 파일을 정리하는 동기화 콜백 등록.
     * 트랜잭션 비활성 상황(예: 트랜잭션 밖 호출)은 방어적으로 무시한다.
     */
    private void registerOrphanCleanupOnRollback(Path savedPath) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        Files.deleteIfExists(savedPath);
                    } catch (IOException e) {
                        // 고아 파일은 수동/배치 정리 — 트랜잭션 정리 흐름을 막지 않도록 warn 만.
                        log.warn("[NoticeAttach] orphan cleanup failed causeType={}",
                                e.getClass().getSimpleName());
                    }
                }
            }
        });
    }

    /**
     * MAJOR 2 — 커밋 성공 후에만 물리 파일을 삭제하는 동기화 콜백 등록.
     * 트랜잭션 비활성 시(드묾)에는 즉시 삭제로 폴백한다.
     * 파일 삭제 실패는 warn 로그만 남기며, 잔류 파일은 수동/배치로 정리한다(고아 파일 정책).
     */
    private void registerPhysicalDeleteOnCommit(LsNoticeAttach attach) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deletePhysicalFile(attach);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deletePhysicalFile(attach);
            }
        });
    }

    private void deletePhysicalFile(LsNoticeAttach attach) {
        try {
            Path resolved = resolveSafeExistingPath(attach.getFilePath());
            Files.deleteIfExists(resolved);
        } catch (CustomException e) {
            // 경로가 storage 밖이면(데이터 손상) 삭제하지 않고 경고만 — 임의 파일 삭제 방지.
            log.warn("[NoticeAttach] skip physical delete (path outside storage) attachSn={}",
                    attach.getAttachSn());
        } catch (IOException e) {
            // 고아 파일은 수동/배치 정리 — 삭제 실패가 후속 흐름을 막지 않도록 warn 만.
            log.warn("[NoticeAttach] physical delete failed attachSn={} causeType={}",
                    attach.getAttachSn(), e.getClass().getSimpleName());
        }
    }

    private void validateFile(MultipartFile file, String originalName) {
        if (file == null || file.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "업로드 파일이 비어 있습니다.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new CustomException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "파일 크기가 허용 한도(20MB)를 초과했습니다.");
        }
        if (originalName == null || originalName.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "파일명이 비어 있습니다.");
        }
        if (originalName.length() > MAX_ORIGINAL_NAME_LENGTH) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "파일명이 너무 깁니다 (최대 255자).");
        }
        String extension = resolveExtension(originalName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "허용되지 않는 확장자입니다. 허용: " + ALLOWED_EXTENSIONS);
        }
    }

    /**
     * 사용자 입력 파일명에서 확장자만 추출 — 경로 구분자 제거 + 소문자화.
     * 확장자가 없거나 위험 문자가 포함되면 빈 문자열 반환(allowlist 검증에서 실패).
     */
    private static String resolveExtension(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "";
        }
        // CWE-22 — 경로 구분자 무시, 마지막 segment 만 사용.
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
        if (!ext.matches("^[a-z0-9]{1,8}$")) {
            return "";
        }
        return ext;
    }

    /**
     * CWE-22 — 신규 저장 경로 결정. UUID 파일명만 입력으로 받지만 이중 방어로
     * normalize + storage prefix 검증을 수행한다.
     */
    private Path resolveSafeStoragePath(String safeFileName) {
        Path candidate = storageBasePath.resolve(UPLOAD_SUBDIR).resolve(safeFileName).normalize();
        if (!candidate.startsWith(storageBasePath)) {
            log.warn("[NoticeAttach] path traversal attempt blocked on store");
            throw new CustomException(ErrorCode.FORBIDDEN, "저장 경로가 허용 범위를 벗어납니다.");
        }
        return candidate;
    }

    /**
     * CWE-22 — DB 에 보관된 절대/상대 경로를 storage 기준으로 normalize 후 prefix 검증.
     * 다운로드·삭제 양쪽에서 사용.
     */
    private Path resolveSafeExistingPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "첨부 경로가 비어 있습니다.");
        }
        Path candidate = Paths.get(filePath);
        Path resolved = candidate.isAbsolute()
                ? candidate.normalize()
                : storageBasePath.resolve(candidate).normalize();
        if (!resolved.startsWith(storageBasePath)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 첨부 경로입니다.");
        }
        return resolved;
    }

    /** 다운로드 결과 — Resource + 복원할 원본 파일명 + content length. */
    public record Download(Resource resource, String fileName, long contentLength) {
    }
}
