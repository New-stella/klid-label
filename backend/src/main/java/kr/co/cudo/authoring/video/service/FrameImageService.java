package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * SCR-REVIEW-002 — 프레임 이미지 서빙 (rawSn + frameNo 기반).
 *
 * <p>보안 (HIGH):
 * <ul>
 *   <li><b>Path Traversal (CWE-22)</b>: {@link #resolveSafe} 가 baseDir 외부 경로를 차단.</li>
 *   <li><b>비식별 강제</b>: PRVC/PSDO 영상은 무조건 {@code deidFilePath} 사용.
 *       deidFilePath 가 비어있으면 NOT_FOUND. 원본 경로 폴백 금지.</li>
 *   <li><b>확장자 allowlist</b>: jpg/jpeg/png/webp 만 서빙.</li>
 *   <li><b>로그 마스킹</b>: 사용자 입력 평문 path/filename 노출 금지.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class FrameImageService {

    private final LsDataSrcRepository srcRepository;
    private final VideoRepository videoRepository;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    /**
     * 프레임 이미지를 stream 으로 응답. 인증/인가는 Controller 의 PreAuthorize 에 위임.
     *
     * @param rawSn    영상 PK
     * @param frameNo  프레임 번호 (0-base)
     * @return image bytes (image/jpeg or image/png or image/webp)
     */
    public ResponseEntity<Resource> serve(Long rawSn, Integer frameNo) throws IOException {
        // 1) 영상 조회 — 비식별 정책 판정용
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));

        // 2) 프레임 조회
        LsDataSrc src = srcRepository.findByRawSnAndFrameNo(rawSn, frameNo)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다."));

        // 3) 비식별 정책 — PRVC/PSDO 는 무조건 deidFilePath 사용 (REVIEWER 도 원본 강제 노출 금지)
        String relPath;
        if (raw.needsDeidentify()) {
            String deid = src.getDeidFilePath();
            if (deid == null || deid.isBlank()) {
                log.warn("[FrameImage] deid path missing for sensitive video rawSn={} frameNo={}", rawSn, frameNo);
                throw new CustomException(ErrorCode.NOT_FOUND, "비식별 처리 미완료");
            }
            relPath = deid;
        } else {
            relPath = src.getFilePath();
        }

        // 4) Path Traversal 방어
        Path baseDir = Paths.get(storageRawPath).toAbsolutePath().normalize();
        Path resolved = resolveSafe(baseDir, relPath);

        // 5) 파일 존재 확인
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            log.warn("[FrameImage] file not found rawSn={} frameNo={}", rawSn, frameNo);
            throw new CustomException(ErrorCode.NOT_FOUND, "이미지 파일이 존재하지 않습니다.");
        }

        // 6) MIME 결정 (allowlist 기반)
        MediaType mediaType = resolveMediaType(resolved);

        // 7) Stream 응답 (대용량 메모리 적재 회피)
        long contentLength = Files.size(resolved);
        InputStream in = Files.newInputStream(resolved);
        InputStreamResource body = new InputStreamResource(in);

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(contentLength)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"frame_" + rawSn + "_" + frameNo + extOf(resolved) + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    /**
     * Path Traversal 방어 (CWE-22) — baseDir 외부 경로는 FORBIDDEN.
     * <p>VisibleForTesting (public static).
     */
    public static Path resolveSafe(Path baseDir, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "이미지 경로가 비어있습니다.");
        }
        Path candidate = Paths.get(filePath);
        Path resolved = candidate.isAbsolute()
                ? candidate.normalize()
                : baseDir.resolve(candidate).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 이미지 경로입니다.");
        }
        return resolved;
    }

    /** 확장자 allowlist 기반 MIME 결정 — 그 외는 거부. VisibleForTesting. */
    public static MediaType resolveMediaType(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (name.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (name.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 이미지 확장자입니다.");
    }

    private static String extOf(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(dot) : "";
    }
}
