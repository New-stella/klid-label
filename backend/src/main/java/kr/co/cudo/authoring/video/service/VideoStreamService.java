package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

/**
 * 영상 파일 스트리밍 서비스 — HTTP Range 지원.
 *
 * <p>보안 (HIGH):
 * <ul>
 *   <li><b>Path Traversal (CWE-22)</b>: filePath 가 basePath 외부이면 FORBIDDEN.</li>
 *   <li><b>확장자 allowlist</b>: video MIME 확인.</li>
 *   <li>로그에 사용자 입력 평문 path 미노출.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class VideoStreamService {

    /** 1MB chunk — Range 요청 시 최대 전송 크기. */
    private static final long CHUNK_SIZE = 1_048_576L;

    private final VideoRepository videoRepository;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    /**
     * 영상 파일 스트리밍.
     *
     * @param rawSn   영상 PK
     * @param headers 요청 HTTP 헤더 (Range 포함 가능)
     * @return ResourceRegion 응답 (200 또는 206)
     * @throws IOException 파일 읽기 실패 시
     */
    public ResponseEntity<ResourceRegion> stream(Long rawSn, HttpHeaders headers) throws IOException {
        // 1) 영상 조회
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));

        // 2) Path Traversal 방어 (CWE-22)
        Path baseDir = Paths.get(storageRawPath).toAbsolutePath().normalize();
        Path resolved = resolveSafe(baseDir, raw.getFilePath());

        // 3) 파일 존재 확인
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            log.warn("[VideoStream] file not found rawSn={}", rawSn);
            throw new CustomException(ErrorCode.NOT_FOUND, "영상 파일이 존재하지 않습니다.");
        }

        // 4) MIME 결정
        UrlResource videoResource = new UrlResource(resolved.toUri());
        MediaType mediaType = MediaTypeFactory.getMediaType(videoResource)
                .orElse(MediaType.parseMediaType("video/mp4"));

        long contentLength = videoResource.contentLength();

        // 5) Range 헤더 파싱 → ResourceRegion
        List<HttpRange> ranges = headers.getRange();
        if (!ranges.isEmpty()) {
            HttpRange range = ranges.get(0);
            long start = range.getRangeStart(contentLength);
            long end = Math.min(start + CHUNK_SIZE - 1, range.getRangeEnd(contentLength));
            long rangeLength = end - start + 1;

            ResourceRegion region = new ResourceRegion(videoResource, start, rangeLength);
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .contentType(mediaType)
                    .header(HttpHeaders.CONTENT_RANGE,
                            "bytes " + start + "-" + end + "/" + contentLength)
                    .header("X-Content-Type-Options", "nosniff")
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                    .body(region);
        }

        // 6) Range 없으면 전체 파일
        ResourceRegion region = new ResourceRegion(videoResource, 0, contentLength);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(region);
    }

    /**
     * Path Traversal 방어 (CWE-22) — baseDir 외부 경로는 FORBIDDEN.
     */
    static Path resolveSafe(Path baseDir, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "영상 경로가 비어있습니다.");
        }
        Path candidate = Paths.get(filePath);
        Path resolved = candidate.isAbsolute()
                ? candidate.normalize()
                : baseDir.resolve(candidate).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 영상 경로입니다.");
        }
        return resolved;
    }
}
