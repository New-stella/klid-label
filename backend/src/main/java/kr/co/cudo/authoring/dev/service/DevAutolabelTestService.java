package kr.co.cudo.authoring.dev.service;

import kr.co.cudo.authoring.batch.test.AutolabelTestService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.dev.dto.AutolabelTestRequest;
import kr.co.cudo.authoring.dev.dto.AutolabelTestResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.MngResourceCctvRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * [개발/검수 전용] 영상 업로드 + 오토라벨 파이프라인 트리거 서비스.
 *
 * <p>운영(prd) 환경에서는 {@code @Profile("!prd")} 로 빈 자체가 등록되지 않는다.
 *
 * <p>보안:
 * <ul>
 *   <li>CWE-434 Unrestricted File Upload — 확장자 allowlist + 크기 제한 + 메타 검증.</li>
 *   <li>CWE-22 Path Traversal — UUID 재명명 + storage 기준 경로 prefix 검증.</li>
 *   <li>CWE-117 Log Injection — 사용자 파일명 원본 로그 출력 금지 (UUID 만 출력).</li>
 *   <li>CWE-209 Information Leak — 응답에 절대경로 미노출 (상대경로만 반환).</li>
 *   <li>CWE-863 Improper Authorization — 컨트롤러에서 {@code @PreAuthorize} 로 차단.</li>
 * </ul>
 *
 * <p>업로드된 영상은 LS_DATA_RAW row 생성 후 기존 {@link AutolabelTestService#runFull(Long)}
 * 을 비동기로 호출 — 프레임 추출 + YOLO + SAM2 가 백그라운드 실행된다 (외부 의존 없는 경량 파이프라인).
 */
@Slf4j
@Service
@Profile("!prd")
public class DevAutolabelTestService {

    /** CWE-434 영상 확장자 allowlist (소문자 비교). */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("mp4", "webm", "mov", "avi");

    /** 업로드된 파일을 저장할 storage 하위 디렉토리. */
    private static final String UPLOAD_SUBDIR = "autolabel-test";

    private final VideoRepository videoRepository;
    private final MngResourceCctvRepository cctvRepository;
    private final AutolabelTestService autolabelTestService;
    private final Path storageRawPath;
    private final long maxFileSize;

    public DevAutolabelTestService(
            VideoRepository videoRepository,
            MngResourceCctvRepository cctvRepository,
            AutolabelTestService autolabelTestService,
            @Value("${authoring.storage.raw-path:./storage/raw}") String storageRawPath,
            @Value("${authoring.dev.autolabel-test.max-file-size:524288000}") long maxFileSize
    ) {
        this.videoRepository = videoRepository;
        this.cctvRepository = cctvRepository;
        this.autolabelTestService = autolabelTestService;
        this.storageRawPath = Paths.get(storageRawPath).toAbsolutePath().normalize();
        this.maxFileSize = maxFileSize;
    }

    /**
     * 영상 업로드 + LS_DATA_RAW 등록 후 오토라벨 파이프라인을 백그라운드로 시작한다.
     *
     * @param file 업로드된 영상 파일 (multipart)
     * @param meta 검증된 메타데이터
     * @return rawSn + 저장된 상대 경로 + 파이프라인 상태
     */
    @Transactional("controlTransactionManager")
    public AutolabelTestResponse upload(MultipartFile file, AutolabelTestRequest meta) {
        validateFile(file);
        validateMeta(meta);

        String extension = resolveExtension(file.getOriginalFilename());
        String safeFileName = UUID.randomUUID().toString() + "." + extension;
        Path savedAbsolutePath = resolveSafeStoragePath(safeFileName);
        String relativePath = UPLOAD_SUBDIR + "/" + safeFileName;

        try {
            Files.createDirectories(savedAbsolutePath.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, savedAbsolutePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.error("[DevAutolabelTest] file save failed vmsClipIdHash={}",
                    Integer.toHexString(meta.vmsClipId().hashCode()), e);
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "영상 파일 저장에 실패했습니다.");
        }

        LocalDateTime capturedAt = LocalDateTime.ofInstant(meta.capturedAt(), ZoneId.systemDefault());
        LsDataRaw raw = LsDataRaw.createFromIngest(
                meta.vmsClipId(),
                meta.cctvId(),
                meta.eventTypeCd(),
                meta.localGovCd(),
                meta.prvcTypeCd().name(),
                relativePath,
                capturedAt,
                meta.durationSec()
        );
        LsDataRaw saved = videoRepository.save(raw);
        Long rawSn = saved.getRawSn();
        long startedAt = System.currentTimeMillis();

        // CWE-117 Log Injection 방어 — 사용자 입력 원본 파일명/경로 절대 출력 금지.
        log.info("[DevAutolabelTest] uploaded rawSn={} vmsClipIdHash={} prvcType={} ext={} sizeBytes={}",
                rawSn,
                Integer.toHexString(meta.vmsClipId().hashCode()),
                meta.prvcTypeCd().name(),
                extension,
                file.getSize());

        // 백그라운드 파이프라인 실행 — runFull 은 동기 long-running, 별도 스레드로 분리.
        // 트랜잭션 커밋 이후에 호출되어야 하므로 CompletableFuture 로 fire-and-forget.
        CompletableFuture.runAsync(() -> {
            try {
                autolabelTestService.runFull(rawSn);
            } catch (Exception e) {
                log.warn("[DevAutolabelTest] pipeline failed rawSn={} cause={}",
                        rawSn, e.getClass().getSimpleName());
            }
        });

        return new AutolabelTestResponse(rawSn, relativePath, "PROCESSING", startedAt);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "업로드 파일이 비어 있습니다.");
        }
        if (file.getSize() > maxFileSize) {
            throw new CustomException(ErrorCode.PAYLOAD_TOO_LARGE,
                    "파일 크기가 허용 한도를 초과했습니다. 최대 " + maxFileSize + " bytes");
        }
        String extension = resolveExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "허용되지 않는 확장자입니다. 허용: " + ALLOWED_EXTENSIONS);
        }
        // MIME 은 브라우저/OS 에 따라 다양하여 너무 엄격하면 거부 많음 — 확장자 검증을 우선,
        // 단순한 sanity check 만 적용 (octet-stream 도 허용).
        String contentType = file.getContentType();
        if (contentType != null
                && !contentType.equalsIgnoreCase("application/octet-stream")
                && !contentType.toLowerCase(Locale.ROOT).startsWith("video/")) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "허용되지 않는 MIME 타입입니다.");
        }
    }

    private void validateMeta(AutolabelTestRequest meta) {
        // vmsClipId 중복 — UK 충돌 사전 차단.
        videoRepository.findByVmsClipId(meta.vmsClipId()).ifPresent(existing -> {
            throw new CustomException(ErrorCode.CONFLICT,
                    "동일한 vmsClipId 가 이미 존재합니다.");
        });
        // cctvId 등록 여부.
        if (!cctvRepository.existsById(meta.cctvId())) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "등록되지 않은 CCTV 입니다.");
        }
    }

    /**
     * 사용자 입력 파일명에서 확장자만 추출 — 경로 구분자 제거 + 소문자화.
     * 확장자가 없거나 위험 문자가 포함되면 빈 문자열 반환 (allowlist 검증에서 실패).
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
        // 영문/숫자만 (특수문자 차단)
        if (!ext.matches("^[a-z0-9]{1,8}$")) {
            return "";
        }
        return ext;
    }

    /**
     * CWE-22 Path Traversal 방어 — storage 기준 경로 prefix 검증.
     * UUID 재명명된 파일명만 입력으로 받으므로 일반적으로 안전하지만 이중 방어.
     */
    private Path resolveSafeStoragePath(String safeFileName) {
        Path candidate = storageRawPath.resolve(UPLOAD_SUBDIR).resolve(safeFileName).normalize();
        if (!candidate.startsWith(storageRawPath)) {
            log.warn("[DevAutolabelTest] path traversal attempt blocked");
            throw new CustomException(ErrorCode.FORBIDDEN,
                    "저장 경로가 허용 범위를 벗어납니다.");
        }
        return candidate;
    }
}
