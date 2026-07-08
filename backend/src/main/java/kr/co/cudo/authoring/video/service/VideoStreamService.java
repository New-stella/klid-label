package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.StreamUrlResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
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
 * <p>Privacy (Phase 2): 마킹 화면 스트림은 <b>항상 비식별 영상</b>을 서빙한다. 비식별 결과 경로는
 * 최신 성공 {@link LsDeidentProcLog} 에서 도출하며, 비식별이 미완료(경로/파일 부재)면 원본을
 * 절대 노출하지 않고 NOT_FOUND 로 거부한다 — 전체 무조건 비식별 정책상 모든 영상이 대상.
 *
 * <p>보안 (HIGH):
 * <ul>
 *   <li><b>Path Traversal (CWE-22)</b>: 비식별 경로가 deidentified base 외부이면 FORBIDDEN.</li>
 *   <li><b>비식별 미완료 원본 노출 차단</b>: deid 경로/파일 부재 시 NOT_FOUND.</li>
 *   <li>로그에 사용자 입력 평문 path 미노출.</li>
 * </ul>
 */
@Slf4j
@Service
@Transactional(value = "controlTransactionManager", readOnly = true)
public class VideoStreamService {

    /** 기본 청크 상한 8MB — Range 요청 시 요청당 최대 전송 크기(설정 미지정/불량 시 fail-safe 기본값). */
    private static final long DEFAULT_CHUNK_SIZE = 8_388_608L;

    /** 최소 청크 상한 1MB — 이보다 작게 설정하면 재요청 폭증으로 4배속 버벅임이 재발하므로 하한 가드. */
    private static final long MIN_CHUNK_SIZE = 1_048_576L;

    /**
     * 최대 청크 상한 64MB — 비정상 대형 설정값(운영자/env 오주입)이 {@code start + chunk - 1} 의 long
     * 오버플로로 음수 count(→ ResourceRegion IllegalArgumentException → 500)를 일으키는 것을 막는 상한
     * 가드 (CWE-190). 단일 Range 응답으로 64MB 이상 전송할 실익도 없다.
     */
    private static final long MAX_CHUNK_SIZE = 67_108_864L;

    private final VideoRepository videoRepository;
    private final StreamUrlSigner streamUrlSigner;
    private final LsDeidentProcLogRepository deidentProcLogRepository;

    /**
     * 자기 참조(프록시) — {@code @Cacheable} 는 자기호출(self-invocation) 시 프록시를 거치지 않아
     * 캐시가 동작하지 않는다. {@link #stream} 이 캐시 적용 메서드 {@link #resolveStreamMeta} 를
     * 프록시 경유로 호출하도록 {@code @Lazy} 자기 주입을 사용한다.
     * 단위 테스트는 생성자로 직접 인스턴스화(프록시 없음)하므로 이 필드가 null 이며, 그 경우
     * 캐시 없이 직접 호출로 폴백한다 — 캐시는 통합(Spring 컨텍스트)에서만 활성화된다.
     */
    @Lazy
    @Autowired(required = false)
    private VideoStreamService self;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String deidentifiedPath;

    /**
     * Range 요청당 반환 상한(bytes). 4배속 재생은 같은 벽시계 시간에 4배 데이터를 소비하므로,
     * 청크 상한이 작으면(구 1MB) 재요청 빈도가 폭증해 디코드 버퍼를 못 따라가 버벅인다. 기본 8MB 로
     * 재요청 빈도를 1/8 로 줄인다. {@link #effectiveChunkSize()} 가 하한(1MB) 미만·미설정(0) 값을
     * fail-safe 기본값으로 폴백하므로, 잘못된 설정이나 단위 테스트(0) 에서도 안전하다.
     */
    @Value("${authoring.storage.stream-chunk-size:8388608}")
    private long streamChunkSize;

    public VideoStreamService(VideoRepository videoRepository,
                              StreamUrlSigner streamUrlSigner,
                              LsDeidentProcLogRepository deidentProcLogRepository) {
        this.videoRepository = videoRepository;
        this.streamUrlSigner = streamUrlSigner;
        this.deidentProcLogRepository = deidentProcLogRepository;
    }

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
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));

        // 비식별 유효성 게이트 (HIGH — privacy, CWE-359) — DE_IDNTF_YN != 'Y'(신고 'F'/미수행 'N') 면
        // 서명 URL 자체를 발급하지 않는다. 발급된 URL 은 stream() 게이트로도 차단되나(defense-in-depth),
        // 노출본 대상 URL 발급을 사전 차단한다. 원본 노출 금지 → NOT_FOUND(내부 상태 미노출).
        if (!"Y".equals(raw.getDeIdntfYn())) {
            log.warn("[VideoStream] deident not valid rawSn={} — refusing signed url", rawSn);
            throw new CustomException(ErrorCode.NOT_FOUND, "비식별 처리 미완료");
        }

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
        // 1~4) 영상 존재 확인 + 비식별 경로 해석 + Path Traversal 방어 + 파일 존재 확인 + MIME/length
        //       해석을 캐시 경유로 수행(성능 — HTTP Range 요청마다 반복되던 stat/MIME/length 재계산 제거).
        //       미완료면 원본 노출 금지 → NOT_FOUND. 비식별 완료 파일은 불변이라 메타 캐싱이 안전하다.
        StreamMeta meta = resolveStreamMetaCached(rawSn);
        if (meta == null) {
            log.warn("[VideoStream] deidentify not completed rawSn={} — refusing raw exposure", rawSn);
            throw new CustomException(ErrorCode.NOT_FOUND, "비식별 처리 미완료");
        }

        // UrlResource 는 URI 로부터의 재구성이 저렴하므로 캐시하지 않고 매 요청마다 만든다(디스크 stat 없음).
        UrlResource videoResource = new UrlResource(meta.path().toUri());
        MediaType mediaType = meta.mediaType();
        long contentLength = meta.contentLength();

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
            // 설정된 청크 상한(기본 8MB)으로 상한 → 재요청 빈도 감소(4배속 버벅임 방지) + 메모리 보호.
            long end = Math.min(start + effectiveChunkSize() - 1, rangeEnd);
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
     * 유효 청크 상한 반환 — 설정값을 [MIN(1MB), MAX(64MB)] 로 클램프한다.
     * <ul>
     *   <li>하한(1MB) 미만·미설정(0)·음수 → fail-safe 기본값(8MB) 로 폴백(재요청 폭증/음수 길이 방지).</li>
     *   <li>상한(64MB) 초과(비정상 대형 env) → 64MB 로 클램프({@code start + chunk - 1} long 오버플로로
     *       인한 음수 count·500 방지, CWE-190).</li>
     * </ul>
     */
    private long effectiveChunkSize() {
        if (streamChunkSize < MIN_CHUNK_SIZE) {
            return DEFAULT_CHUNK_SIZE;
        }
        return Math.min(streamChunkSize, MAX_CHUNK_SIZE);
    }

    /**
     * 스트림 메타 해석을 캐시 경유로 호출 (성능 — HTTP Range 요청마다 반복되던 DB 조회 + 파일 stat +
     * MIME/length 재계산 방지).
     *
     * <p>{@code @Cacheable} 는 자기호출 시 프록시를 우회하므로, Spring 컨텍스트에서는 {@link #self}
     * (프록시)를 거쳐 캐시를 적용한다. 단위 테스트(직접 생성자 인스턴스화)에서는 {@code self} 가 null
     * 이라 캐시 없이 직접 호출로 폴백한다 — 동작 의미는 동일하고 캐시는 투명하다.
     */
    private StreamMeta resolveStreamMetaCached(Long rawSn) throws IOException {
        return self != null ? self.resolveStreamMeta(rawSn) : resolveStreamMeta(rawSn);
    }

    /**
     * 비식별 스트림 메타(해석된 파일 경로 + contentLength + mediaType)를 rawSn 키로 해석·캐싱.
     *
     * <p>비식별 완료 파일은 <b>불변</b>이므로 경로·크기·MIME 을 안전하게 캐시할 수 있다. HTTP Range 요청은
     * 재생/시크마다 수십~수백 회 발생하는데, 매번 {@code Files.exists}/{@code isRegularFile}/
     * {@code MediaTypeFactory}/{@code contentLength()}(파일 stat) 를 재계산하던 오버헤드를 제거한다.
     * 이는 4배속 시 재요청 빈도 폭증과 맞물려 버벅임을 유발했다.
     *
     * <p>보안 가드는 캐시 채우기(miss) 시점에 그대로 수행된다 — Path Traversal(CWE-22) 위반은 FORBIDDEN,
     * 비식별 미완료/파일 부재는 원본 노출 금지(NOT_FOUND). 예외는 캐시되지 않는다. 미완료(null) 결과는
     * {@code unless="#result==null"} 로 캐시하지 않아 async 비식별 완료 후 stale NOT_FOUND 가 고정되지 않는다.
     *
     * @return 스트림 메타(non-null) 또는 비식별 미완료 시 null
     * @throws CustomException 영상 미존재(NOT_FOUND) / 경로 위반(FORBIDDEN) / 파일 부재(NOT_FOUND)
     */
    @Cacheable(cacheNames = "stream-meta", key = "#rawSn", unless = "#result == null")
    public StreamMeta resolveStreamMeta(Long rawSn) throws IOException {
        // 1~2) 영상 존재 확인 + 비식별 경로 해석. 미완료면 원본 노출 금지 → null (호출부가 NOT_FOUND).
        String deidPath = resolveDeidPath(rawSn);
        if (deidPath == null) {
            return null;
        }

        // 3) Path Traversal 방어 (CWE-22) — 비식별 저장 base 정합. 위반 시 FORBIDDEN(캐시 안 됨).
        Path baseDir = Paths.get(deidentifiedPath).toAbsolutePath().normalize();
        Path resolved = resolveSafe(baseDir, deidPath);

        // 4) 파일 존재 확인 — 비식별 파일 부재 시 원본 노출 금지(privacy) → NOT_FOUND(캐시 안 됨).
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            log.warn("[VideoStream] deid file not found rawSn={}", rawSn);
            throw new CustomException(ErrorCode.NOT_FOUND, "비식별 영상 파일이 존재하지 않습니다.");
        }

        // 5) MIME + length 결정 (파일 불변이라 캐시 안전)
        UrlResource videoResource = new UrlResource(resolved.toUri());
        MediaType mediaType = MediaTypeFactory.getMediaType(videoResource)
                .orElse(MediaType.parseMediaType("video/mp4"));
        return new StreamMeta(resolved, videoResource.contentLength(), mediaType);
    }

    /**
     * 스트림 메타 캐시 값 — 비식별 파일이 불변이므로 rawSn 키로 함께 캐싱 가능한 해석 결과.
     *
     * @param path          Path Traversal 검증을 통과해 해석된 비식별 파일 경로
     * @param contentLength 파일 크기(bytes) — 매 Range 요청의 파일 stat 을 대체
     * @param mediaType     결정된 MIME
     */
    public record StreamMeta(Path path, long contentLength, MediaType mediaType) {
    }

    /**
     * 영상 존재 확인 + 비식별 유효성 게이트 + 비식별 결과 경로 해석.
     *
     * <p>마킹 화면 스트림은 항상 비식별 영상을 서빙한다(Privacy, Phase 2). 최신 성공 procLog 에서
     * 비식별 경로를 도출하며, 미완료면 {@code null} 을 반환한다(호출부가 NOT_FOUND 로 거부).
     *
     * <p><b>비식별 유효성 게이트 (HIGH — privacy, CWE-359)</b>: {@code LS_DATA_RAW.DE_IDNTF_YN} 가
     * 유효('Y')가 아니면 {@code null} 을 반환해 서빙을 거부한다. 개인정보 누락 신고 시
     * {@code DeidentReportService.report} 가 {@code DE_IDNTF_YN='F'} 로 마킹하지만 <b>옛 성공 procLog 는
     * 그대로 남으므로</b>, procLog 만으로 서빙하면 신고된 노출본이 재비식별 완료 전까지 계속 스트리밍된다.
     * 정상 흐름은 비식별 완료 시 {@code markDeidentified("Y")} 와 성공 procLog 가 동일 커밋에 함께
     * 설정되므로('Y'⟺유효), 이 게이트는 정상 마킹 재생을 깨지 않는다.
     *
     * <p>더 이상 {@code @Cacheable} 를 붙이지 않는다 — 본 메서드는 상위 {@link #resolveStreamMeta}
     * (@Cacheable {@code stream-meta})의 MISS 시에만 호출되므로 자체 캐시가 불필요하며(구
     * {@code stream-deid} 캐시는 self-invocation 으로 도달 불가한 dead cache 였다), 무효화 표면을
     * 단일 {@code stream-meta} 로 통합한다.
     *
     * @return 비식별 결과 경로(non-null) 또는 미완료/비식별 무효('Y' 아님) 시 null
     * @throws CustomException 영상 자체가 존재하지 않으면 NOT_FOUND
     */
    public String resolveDeidPath(Long rawSn) {
        // 영상 존재 확인 (없으면 404) + 비식별 유효성 플래그 확인을 단일 조회로 수행.
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));

        // 비식별 유효성 게이트 — DE_IDNTF_YN != 'Y'(신고 'F'/미수행 'N') 면 원본/노출본 서빙 거부.
        if (!"Y".equals(raw.getDeIdntfYn())) {
            log.warn("[VideoStream] deident not valid rawSn={} deIdntfYn={} — refusing stream",
                    rawSn, raw.getDeIdntfYn());
            return null;
        }

        return deidentProcLogRepository.findLatestSuccessByDataRawSn(rawSn)
                .map(LsDeidentProcLog::getDeIdntfFilePathNm)
                .filter(p -> p != null && !p.isBlank())
                .orElse(null);
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
