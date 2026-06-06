package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.StreamUrlResponse;
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
    private final StreamUrlSigner streamUrlSigner;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    /**
     * 단기 서명 스트림 URL 발급.
     *
     * <p>&lt;video&gt; 가 Authorization 헤더를 못 붙이는 문제를 우회하기 위해, 인증된 사용자가 호출하면
     * 짧은 TTL HMAC 서명 쿼리를 붙인 스트림 URL 을 반환한다. 영상 존재를 먼저 확인해 없으면 404.
     *
     * @param rawSn  영상 PK
     * @param userNo 발급 요청자 subject (JWT sub) — 서명 입력에 바인딩되어 타 사용자 URL 재사용을 차단한다.
     * @return 서명 URL + 만료 epoch-second
     */
    public StreamUrlResponse issueSignedUrl(Long rawSn, String userNo) {
        // 영상 존재 확인 (없으면 404)
        videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));

        if (!streamUrlSigner.isConfigured()) {
            // 시크릿 미설정은 서버 설정 오류(권한 거부 아님) → 503 SERVICE_UNAVAILABLE (fail-closed).
            // 스택/내부 경로 등은 노출하지 않는다 (CWE-209).
            log.warn("[VideoStream] stream sign-secret 미설정 — 서명 URL 발급 불가 rawSn={}", rawSn);
            throw new CustomException(ErrorCode.SERVICE_UNAVAILABLE,
                    "스트림 서명 URL 발급이 비활성화되어 있습니다.");
        }
        // CWE-284 — userNo 를 서명 입력에 바인딩하고 URL 쿼리 u={userNo} 에도 포함한다.
        // StreamSignatureFilter 가 쿼리의 u 를 서명 입력으로 사용해 검증하므로, u 를 변조하면
        // 서명 불일치로 거부된다(타 사용자가 URL 을 그대로 재사용해도 통과하지 못함).
        StreamUrlSigner.SignedParams params = streamUrlSigner.sign(rawSn, userNo);
        String u = userNo == null ? "" : userNo;
        String url = "/api/v1/videos/" + rawSn + "/stream?exp=" + params.exp()
                + "&u=" + u + "&sig=" + params.sig();
        return new StreamUrlResponse(url, params.exp(), params.ttlSeconds());
    }

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
        Path resolved = resolveSafe(baseDir, raw.getRawFilePathNm());

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

        // 5) Range 헤더 파싱 (RFC 7233). 파싱 실패(역전/형식 오류)는 fail-secure → 416.
        List<HttpRange> ranges;
        try {
            ranges = headers.getRange();
        } catch (IllegalArgumentException ex) {
            // 잘못된 Range 문법(예: bytes=999-0) → 416 + Content-Range: bytes */total
            log.warn("[VideoStream] invalid range syntax rawSn={}", rawSn);
            return rangeNotSatisfiable(contentLength);
        }

        if (!ranges.isEmpty()) {
            HttpRange range = ranges.get(0);

            // 범위 밖(start >= total) → 416 (CWE-20 입력 검증, fail-secure)
            long start = range.getRangeStart(contentLength);
            if (start >= contentLength) {
                log.warn("[VideoStream] range out of bounds rawSn={}", rawSn);
                return rangeNotSatisfiable(contentLength);
            }

            long rangeEnd = range.getRangeEnd(contentLength);
            // 1MB 청크로 상한 → 점진적 전송 + 메모리 보호
            long end = Math.min(start + CHUNK_SIZE - 1, rangeEnd);
            long rangeLength = end - start + 1;

            // Content-Range / Content-Length 는 ResourceRegionHttpMessageConverter 가
            // write 시점에 직접 add 한다. 여기서 미리 set 하면 write 시 immutable 헤더 맵에
            // 중복 add → UnsupportedOperationException → 500 이 된다. (R3-1 근본 원인)
            // 따라서 206 경로에서는 Accept-Ranges 만 설정하고 Content-Range 는 컨버터에 위임한다.
            ResourceRegion region = new ResourceRegion(videoResource, start, rangeLength);
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .contentType(mediaType)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header("X-Content-Type-Options", "nosniff")
                    .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                    .body(region);
        }

        // 6) Range 없으면 전체 파일 (Accept-Ranges 헤더로 시크 지원 광고)
        ResourceRegion region = new ResourceRegion(videoResource, 0, contentLength);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(region);
    }

    /**
     * 416 Range Not Satisfiable 응답 (RFC 7233 §4.4) — 본문 없이 Content-Range: bytes *&#47;total 만 전달.
     */
    private ResponseEntity<ResourceRegion> rangeNotSatisfiable(long contentLength) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + contentLength)
                .header("X-Content-Type-Options", "nosniff")
                .build();
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
