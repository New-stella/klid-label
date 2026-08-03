package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.video.dto.StreamUrlResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
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
     * 비식별 누락 신고 구간 판정 <b>단일 원천</b>({@code 'F'} 비교를 여기서 재구현하지 않는다).
     * 라벨 조회 게이트({@code LabelAccessGuard.requireNotUnderDeidentReport})·export 게이트와 같은 판정기다.
     */
    private final DeidentReportGate deidentReportGate;
    /**
     * S6 — 비식별 영상이 co-locate 위치({@code dirname(원본)/{rawSn}/deid/})로 이동하면서, 고정
     * {@code deidentified-path} 단일 base 가드로는 신규 위치가 전부 거부된다(반대로 신규 base 만
     * 허용하면 기존 영상이 깨진다). 허용 base 후보 목록을 만드는 데 사용한다.
     * <p>단위 테스트(생성자 직접 인스턴스화)에서는 null 일 수 있으며, 그 경우 구 위치 base 만 허용한다.
     */
    private final VideoArtifactRootResolver artifactRootResolver;

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
                              LsDeidentProcLogRepository deidentProcLogRepository,
                              VideoArtifactRootResolver artifactRootResolver,
                              DeidentReportGate deidentReportGate) {
        this.videoRepository = videoRepository;
        this.streamUrlSigner = streamUrlSigner;
        this.deidentProcLogRepository = deidentProcLogRepository;
        this.artifactRootResolver = artifactRootResolver;
        this.deidentReportGate = deidentReportGate;
    }

    /**
     * S7-STREAM (HIGH · CWE-359) — <b>비식별 누락 신고 구간 영상 서빙 차단</b>.
     *
     * <h3>판정 범위 = 자기 {@code rawSn} 행 하나</h3>
     * 판정은 {@link DeidentReportGate} 단일 원천에 위임하며, 그 게이트는 <b>자기 행의
     * {@code DE_IDNTF_YN='F'} 만</b> 본다 — {@code ORGNL_RAW_SN} 을 따라 조상으로 올라가지 않는다.
     * 즉 <b>부모 신고는 파생영상 재생을 막지 않는다</b>(파생은 독립 취급 — 확정 정책과 그 함의,
     * 폐기된 전파 안의 이력은 {@link DeidentReportGate} javadoc 참조).
     *
     * <h3>왜 여기(캐시 앞)에서 판정하는가 — CWE-525</h3>
     * {@link #resolveStreamMeta} 는 {@code stream-meta} 캐시 뒤에 있어, 신고 <b>이전에</b> 캐시가 채워지면
     * 이후 요청은 그 메서드에 도달하지 않는다. 판정을 캐시 안쪽에 두면 캐시 히트가 게이트를 우회한다.
     * 따라서 캐시 <b>앞</b>(매 요청)에서 판정한다 — 비용은 PK 인덱스 단일 컬럼 projection 1회다.
     *
     * <h3>응답 코드 = NOT_FOUND (404)</h3>
     * 신고 게이트의 프로젝트 표준은 412({@code PRECONDITION_FAILED})지만, <b>이 엔드포인트의 기존 규약</b>은
     * "비식별이 유효하지 않으면 원본 노출 금지 → 404"({@link #resolveDeidLocation} · {@link #resolveSafe})다.
     * 같은 엔드포인트가 비식별 미완료({@code 'N'})와 신고({@code 'F'})를 서로 다른 코드로 내면 <b>응답 코드가
     * 내부 상태를 알려주는 오라클</b>이 된다(CWE-209). 정보 노출이 더 적은 기존 규약(404)에 맞춘다.
     *
     * <p><b>인가 이후</b> 호출된다 — 컨트롤러가 {@code LabelAccessGuard.verifyRawAccess} 로 영상 단위 인가를
     * 먼저 강제하므로, 미인가자가 이 게이트의 응답으로 영상 상태를 관측할 수 없다.
     */
    private void requireNotUnderDeidentReport(Long rawSn) {
        if (deidentReportGate.isUnderDeidentReport(rawSn)) {
            log.warn("[VideoStream] blocked — deident report open on this video rawSn={}", rawSn);
            throw new CustomException(ErrorCode.NOT_FOUND, "비식별 처리 미완료");
        }
    }

    /**
     * 단기 서명 스트림 URL 발급.
     *
     * <p>&lt;video&gt; 가 Authorization 헤더를 못 붙이는 문제를 우회하기 위해, 인증된 사용자가 호출하면
     * 짧은 TTL HMAC 서명 쿼리를 붙인 스트림 URL 을 반환한다. 영상 존재를 먼저 확인해 없으면 404.
     *
     * <p><b>인가</b>: 영상 단위 접근 권한(REVIEWER 전체 / WORKER 본인 배정)은 컨트롤러 진입부에서
     * {@code LabelAccessGuard.verifyRawAccess} 가 먼저 강제한다(B-ISSUE-63).
     *
     * @param rawSn  영상 PK
     * @param userNo 발급 요청자 subject (JWT sub) — 서명 입력에 바인딩된다. 단, URL 쿼리에도 노출되므로
     *               이 값만으로 URL 재사용이 차단되지는 않는다(아래 nonce 참조).
     * @param nonce  발급자 브라우저에만 내려가는 HttpOnly 쿠키 값 — URL 에 포함되지 않으며, 이 값이 서명
     *               입력에 섞이므로 <b>URL 만 유출된 제3자는 재생할 수 없다</b>(A-ISSUE-11).
     * @return 서명 URL + 만료 epoch-second
     */
    public StreamUrlResponse issueSignedUrl(Long rawSn, String userNo, String nonce) {
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

        // S7-STREAM — 신고 구간(자기 행 DE_IDNTF_YN='F')이면 서명 URL 자체를 발급하지 않는다.
        //
        // ★ 이 호출은 "파생영상을 부모 신고로부터 지킨다"는 근거로 있는 것이 아니다(그 조상 전파 정책은
        //   철회됐다 — DeidentReportGate javadoc 의 "폐기된 안"). 게이트는 자기 rawSn 행만 판정한다.
        //   따라서 신고('F') 차단은 바로 위 `!"Y".equals(deIdntfYn)` 검사와 결과가 겹친다(현재 중복).
        //
        // 그럼에도 남겨두는 이유: 신고 구간 판정의 단일 원천은 DeidentReportGate 이고, 위 인라인 검사는
        //   "비식별 유효성"(미수행 'N' 포함)이라는 다른 관심사다. 나중에 인라인 검사가 완화·이동되면
        //   신고 차단만 조용히 사라지므로, 신고 축은 게이트로 명시해 둔다(stream() 진입부와 동일 규약).
        //   ※ 제거 시에는 '자기 신고 차단'이 위 검사에 실제로 남아 있는지 반드시 확인할 것.
        requireNotUnderDeidentReport(rawSn);

        if (!streamUrlSigner.isConfigured()) {
            // 시크릿 미설정은 서버 설정 오류(권한 거부 아님) → 503 SERVICE_UNAVAILABLE (fail-closed).
            // 스택/내부 경로 등은 노출하지 않는다 (CWE-209).
            log.warn("[VideoStream] stream sign-secret 미설정 — 서명 URL 발급 불가 rawSn={}", rawSn);
            throw new CustomException(ErrorCode.SERVICE_UNAVAILABLE,
                    "스트림 서명 URL 발급이 비활성화되어 있습니다.");
        }
        // CWE-284 — userNo 를 서명 입력에 바인딩하고 URL 쿼리 u={userNo} 에도 포함한다.
        // u 변조는 서명 불일치로 거부되지만, u 가 URL 에 함께 노출되므로 "URL 전체 복사" 재사용은
        // u 만으로 막히지 않는다(A-ISSUE-11 — 구 주석의 "재사용해도 통과 못 함" 서술은 사실이 아니었다).
        // 실제 재사용 차단은 URL 에 없는 nonce(HttpOnly 쿠키)를 서명 입력에 섞어 달성한다.
        StreamUrlSigner.SignedParams params = streamUrlSigner.sign(rawSn, userNo, nonce);
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
        // 0) S7-STREAM (HIGH · CWE-359/525) — 비식별 누락 신고 구간(자기 rawSn 행) 차단.
        //    반드시 stream-meta 캐시 조회 **앞**에서 판정한다 — 신고 이전에 채워진 캐시가 게이트를
        //    우회하지 못하게 하기 위함(상세는 requireNotUnderDeidentReport javadoc).
        requireNotUnderDeidentReport(rawSn);

        // 1~4) 영상 존재 확인 + 비식별 경로 해석 + Path Traversal 방어 + 파일 존재 확인 + MIME/length
        //       해석을 캐시 경유로 수행(성능 — HTTP Range 요청마다 반복되던 stat/MIME/length 재계산 제거).
        //       미완료면 원본 노출 금지 → NOT_FOUND. 비식별 완료 파일은 불변이라 메타 캐싱이 안전하다.
        StreamMeta meta = resolveStreamMetaCached(rawSn);
        if (meta == null) {
            log.warn("[VideoStream] deidentify not completed rawSn={} — refusing raw exposure", rawSn);
            throw new CustomException(ErrorCode.NOT_FOUND, "비식별 처리 미완료");
        }

        // 4') 캐시가 "판정 결과"는 재사용해도 "오픈 안전성"까지 우회하면 안 된다 — 매 요청 실경로 재검증.
        //     (상세: revalidateOpenTarget javadoc. 캐시 TTL(5분) 만큼 벌어져 있던 TOCTOU 창을 닫는다.)
        OpenTarget target = revalidateOpenTarget(rawSn, meta);

        // 실제 open 은 NOFOLLOW — 판정 이후 최종 컴포넌트가 심링크로 바뀌어도 링크를 따라가지 않는다.
        // Resource 는 재구성이 저렴하므로 캐시하지 않고 매 요청마다 만든다(스트림은 write 시점에 열린다).
        Resource videoResource = new NoFollowFileResource(target.path(), target.size());
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
                    .cacheControl(noStoreForGatedMedia())
                    .body(region);
        }

        // 6) Range 없으면 전체 파일 (Accept-Ranges 헤더로 시크 지원 광고)
        ResourceRegion region = new ResourceRegion(videoResource, 0, contentLength);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(noStoreForGatedMedia())
                .body(region);
    }

    /**
     * 신고 게이트가 걸린 미디어 응답의 캐시 정책 — {@code no-store}.
     *
     * <p><b>왜 장기 캐시를 둘 수 없나 (CWE-359/525)</b>: 이 응답은 매 요청 {@code requireNotUnderDeidentReport}
     * 게이트를 통과해야 나가지만, 브라우저가 이미 받은 200/206 바이트를 {@code max-age} 동안 재사용하면
     * <b>서버에 오지 않는다</b>. 즉 비식별 누락 신고({@code DE_IDNTF_YN='F'}) 직후에도 캐시된 마스킹 실패
     * 영상이 최대 max-age 동안 계속 재생돼 게이트가 무력화된다(파생영상 재생 → 부모 신고 시나리오에서 실증).
     *
     * <p><b>왜 {@code no-cache} 가 아니라 {@code no-store} 인가</b>: 본 응답에는 검증자(ETag/Last-Modified)가
     * 없어 {@code no-cache}(재검증 강제)로 해도 304 성립이 불가능해 <b>매번 전량 재전송</b>이다 — 대역폭
     * 이득이 없으면서 마스킹 실패 영상이 디스크 캐시에 잔존하는 위험만 남는다. 따라서 프레임 비식별 이미지
     * 경로({@code FrameImageService#serveDeidentified})와 <b>동일하게</b> {@code no-store} 로 통일한다.
     *
     * <p><b>성능 영향</b>: 시크(되감기)마다 Range 재요청이 BE 로 오지만, 경로/크기/MIME 해석은 서버측
     * {@code stream-meta} 캐시가 흡수하므로 요청당 추가 비용은 로컬 NAS 파일 read 뿐이다. 청크 상한(기본
     * 8MB)이 재요청 빈도를 억제한다.
     */
    private static CacheControl noStoreForGatedMedia() {
        return CacheControl.noStore();
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
        DeidLocation location = resolveDeidLocation(rawSn);
        if (location == null) {
            return null;
        }

        // 3) Path Traversal 방어 (CWE-22) — 구/신 비식별 위치 <b>2-way allowlist</b>(S6). 위반 시 거부.
        VerifiedDeidFile verified = resolveSafe(
                allowedDeidBases(rawSn, location.rawFilePathNm()), location.deidPath());

        // 4) 파일 존재 확인 — 비식별 파일 부재 시 원본 노출 금지(privacy) → NOT_FOUND(캐시 안 됨).
        //    확인은 <b>NOFOLLOW</b> 로 한다 — 링크를 따라간 stat 으로 "정상 파일"이라 판정해 놓고 실제
        //    open 은 링크 대상(원본)을 여는 어긋남을 만들지 않기 위함이다(판정 대상 == 응답 대상).
        BasicFileAttributes attrs = readAttributesNoFollow(rawSn, verified.realPath());
        if (attrs == null || !attrs.isRegularFile()) {
            log.warn("[VideoStream] deid file not found or not a regular file rawSn={}", rawSn);
            throw new CustomException(ErrorCode.NOT_FOUND, "비식별 영상 파일이 존재하지 않습니다.");
        }

        // 5) MIME + length 결정 (파일 불변이라 캐시 안전). 크기는 위 NOFOLLOW stat 값을 그대로 쓴다.
        MediaType mediaType = MediaTypeFactory.getMediaType(verified.realPath().getFileName().toString())
                .orElse(MediaType.parseMediaType("video/mp4"));
        return new StreamMeta(verified.realPath(), verified.base(), attrs.size(), mediaType);
    }

    /**
     * 스트림 메타 캐시 값 — 비식별 파일이 불변이므로 rawSn 키로 함께 캐싱 가능한 해석 결과.
     *
     * @param path          Path Traversal + 실경로 검증을 통과해 해석된 비식별 파일 <b>실경로</b>
     * @param base          그 실경로가 하위임이 확인된 허용 base — <b>캐시 히트 시 재검증</b>에 쓴다
     *                      (재검증에 필요한 값을 캐시에 함께 담아야 DB 재조회 없이 매 요청 판정이 가능하다)
     * @param contentLength 파일 크기(bytes) — 매 Range 요청의 파일 stat 을 대체
     * @param mediaType     결정된 MIME
     */
    public record StreamMeta(Path path, Path base, long contentLength, MediaType mediaType) {
    }

    /**
     * <b>캐시 히트 경로의 매 요청 실경로 재검증</b> (HIGH · CWE-367/59/359).
     *
     * <h3>왜 필요한가 — 캐시가 판정을 우회한다</h3>
     * <p>{@link #resolveStreamMeta} 의 실경로 판정은 {@code stream-meta} 캐시
     * ({@link kr.co.cudo.authoring.common.config.CacheConfig#STREAM_META_TTL} 5분) <b>뒤</b>에 있다.
     * 정상 요청 1회로 캐시가 채워진 뒤 그 실경로 파일을 원본(마스킹 전) 영상 심링크로 치환하면,
     * 후속 요청은 판정 메서드에 도달하지 않고 캐시된 경로를 그대로 열어 <b>원본을 200/206 으로 서빙</b>한다.
     * 즉 TOCTOU 창이 마이크로초가 아니라 <b>TTL 만큼(최대 5분, 그 뒤 재판정 → 다시 반복 가능)</b>이었다.
     * (실측 PoC: 캐시 적재 후 치환 → 200 OK 로 원본 바이트가 그대로 나갔다.)
     *
     * <h3>판정 로직을 복제하지 않는다</h3>
     * <p>{@link #resolveSafe} 와 <b>같은 정적 판정기</b>
     * {@link VideoArtifactRootResolver#resolveRealPathUnder} 를 재사용한다. 재검증 입력(경로 + base)은
     * 캐시된 {@link StreamMeta} 안에 있으므로 <b>DB 재조회는 없고</b> 비용은 realpath 해석 + stat 뿐이다
     * (요청당 8MB 청크 전송에 비해 무시할 수준).
     *
     * <h3>세 겹 판정</h3>
     * <ol>
     *   <li>실경로가 여전히 허용 base 하위인가 — base <b>밖</b>을 가리키는 링크 치환을 잡는다.</li>
     *   <li>실경로가 <b>캐시 판정 당시와 동일</b>한가 — {@code STORAGE_RAW_PATH == STORAGE_DEIDENTIFIED_PATH}
     *       형상에서는 <b>같은 base 안</b>의 원본을 가리키는 링크도 가능하므로 1)만으로는 부족하다.</li>
     *   <li>NOFOLLOW stat 이 <b>정규 파일</b>인가 — 최종 컴포넌트가 링크/디렉터리로 바뀐 경우를 잡는다.</li>
     * </ol>
     * 어느 하나라도 실패하면 이 엔드포인트 규약대로 {@link ErrorCode#NOT_FOUND} 로 정규화한다(경로 원문
     * 미노출 — CWE-209). 최종 방어는 실제 open 의 {@code NOFOLLOW_LINKS}
     * ({@link NoFollowFileResource})이며, 이 재검증은 그 실패를 <b>응답 커밋 전에</b> 깔끔한 404 로
     * 바꿔주는 앞단이다(스트림은 메시지 컨버터가 write 시점에 열기 때문).
     */
    private OpenTarget revalidateOpenTarget(Long rawSn, StreamMeta meta) {
        Path real;
        try {
            real = VideoArtifactRootResolver.resolveRealPathUnder(meta.path(), meta.base());
        } catch (RuntimeException e) {
            log.warn("[VideoStream] cached deid path failed realpath re-check rawSn={} — refusing", rawSn);
            throw new CustomException(ErrorCode.NOT_FOUND, "비식별 영상 파일이 존재하지 않습니다.");
        }
        if (!real.equals(meta.path())) {
            log.warn("[VideoStream] cached deid path changed since resolution rawSn={} — refusing", rawSn);
            throw new CustomException(ErrorCode.NOT_FOUND, "비식별 영상 파일이 존재하지 않습니다.");
        }
        BasicFileAttributes attrs = readAttributesNoFollow(rawSn, real);
        if (attrs == null || !attrs.isRegularFile()) {
            log.warn("[VideoStream] cached deid path is not a regular file rawSn={} — refusing", rawSn);
            throw new CustomException(ErrorCode.NOT_FOUND, "비식별 영상 파일이 존재하지 않습니다.");
        }
        return new OpenTarget(real, attrs.size());
    }

    /**
     * 링크를 따라가지 않는 stat — 실패(부재/권한/깨진 링크)는 내부 원인 노출 없이 {@code null} 로 정규화한다
     * (CWE-209). 호출부가 NOT_FOUND 로 마감한다.
     */
    private static BasicFileAttributes readAttributesNoFollow(Long rawSn, Path file) {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            log.warn("[VideoStream] deid file stat failed rawSn={} reason={}",
                    rawSn, e.getClass().getSimpleName());
            return null;
        }
    }

    /** 재검증을 통과해 실제로 열 대상 — 경로와 그 시점 크기를 같은 stat 에서 얻은 한 쌍. */
    private record OpenTarget(Path path, long size) {
    }

    /**
     * <b>심링크를 따라가지 않는 파일 Resource</b> — 스트리밍 응답 본문의 open 규약.
     *
     * <p>구 구현은 {@code new UrlResource(path.toUri())} 였는데, 이 Resource 의 스트림은 메시지 컨버터가
     * <b>응답 write 시점</b>에 기본 옵션으로 열어 <b>심링크를 따라간다</b>. 그래서 경로 판정을 실경로로
     * 아무리 정확히 해도 판정~open 사이에 최종 컴포넌트를 원본 영상 링크로 바꾸면 마스킹 전 픽셀이
     * "비식별 영상"으로 나갔다(CWE-59/367/359).
     *
     * <p>open 은 프레임 이미지 4경로와 <b>동일한 단일 헬퍼</b> {@link FrameImageService#openNoFollow}
     * 를 재사용한다 — CLAUDE.md 규약("판정이 돌려준 실경로를 {@code NOFOLLOW_LINKS} 로 연다")을 서빙
     * 경로마다 재구현하지 않기 위함이다. 링크면 open 자체가 실패해 <b>바이트가 한 개도 나가지 않는다</b>
     * (fail-closed).
     *
     * <p>{@code contentLength} 는 <b>같은 NOFOLLOW stat</b> 에서 얻은 값을 쓴다(판정 대상 == 응답 대상).
     * {@code getDescription()} 에는 내부 경로를 싣지 않는다(CWE-209).
     */
    public static final class NoFollowFileResource extends AbstractResource {

        private final Path path;
        private final long contentLength;

        public NoFollowFileResource(Path path, long contentLength) {
            this.path = path;
            this.contentLength = contentLength;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return FrameImageService.openNoFollow(path).stream();
        }

        @Override
        public boolean exists() {
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        public boolean isReadable() {
            return true;
        }

        @Override
        public long contentLength() {
            return contentLength;
        }

        @Override
        public String getFilename() {
            Path name = path.getFileName();
            return name == null ? null : name.toString();
        }

        @Override
        public String getDescription() {
            return "no-follow deidentified video resource";
        }
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
        DeidLocation location = resolveDeidLocation(rawSn);
        return location == null ? null : location.deidPath();
    }

    /** {@link #resolveDeidPath} 와 동일 판정 + co-locate base 도출용 원본 경로를 함께 반환한다. */
    private DeidLocation resolveDeidLocation(Long rawSn) {
        // 영상 존재 확인 (없으면 404) + 비식별 유효성 플래그 확인을 단일 조회로 수행.
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));

        // 비식별 유효성 게이트 — DE_IDNTF_YN != 'Y'(신고 'F'/미수행 'N') 면 원본/노출본 서빙 거부.
        if (!"Y".equals(raw.getDeIdntfYn())) {
            log.warn("[VideoStream] deident not valid rawSn={} deIdntfYn={} — refusing stream",
                    rawSn, raw.getDeIdntfYn());
            return null;
        }

        // 비식별 영상 경로의 진실원은 DE_IDNTF_FILE_PATH_NM 단 하나다 — 파일명을 조합/추측하지 않는다
        // (mock='deidentified.mp4' · KPST='{원본stem}-mask{ext}' 로 이름이 다르다).
        String deidPath = deidentProcLogRepository.findLatestSuccessByDataRawSn(rawSn)
                .map(LsDeidentProcLog::getDeIdntfFilePathNm)
                .filter(p -> p != null && !p.isBlank())
                .orElse(null);
        return deidPath == null ? null : new DeidLocation(deidPath, raw.getRawFilePathNm());
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
     * 허용 base 후보 목록 (S6 — <b>2-way allowlist</b>).
     * <ol>
     *   <li>구 위치 — {@code STORAGE_DEIDENTIFIED_PATH} 하위(배포 전 비식별본·해상도 파생본).</li>
     *   <li>신 위치 — co-locate 비식별 영상 디렉터리({@code dirname(원본)/{rawSn}/deid/}).
     *       도출/검증에 실패하면 후보에서 조용히 빠진다(구 위치만으로 판정 — fail-secure).</li>
     * </ol>
     * 둘 중 <b>하나라도</b> 만족하면 통과한다. 비식별 영상의 실제 경로/파일명은 언제나
     * {@code LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM} 값을 쓰며 조합/추측하지 않는다.
     */
    private List<Path> allowedDeidBases(Long rawSn, String rawFilePathNm) {
        if (artifactRootResolver == null) {
            return List.of(Paths.get(deidentifiedPath).toAbsolutePath().normalize());
        }
        // 판정 축은 리졸버 한 곳(readableDeidVideoBases) — 같은 DB 값(DE_IDNTF_FILE_PATH_NM)을 읽는
        // 다른 소비자(프레임 추출기)와 가드가 갈라지면 한쪽만 새거나 한쪽만 막힌다(실측: 프레임 추출기가
        // co-locate 산출물을 거부해 비식별 프레임 벌이 통째로 결손). 읽기 후보는 <전략과 무관>하게 구/신
        // 두 위치 모두다(S8) — 롤백 플래그를 되돌려도 이미 적재된 co-locate 경로가 깨지지 않아야 하며,
        // 반대로 전환 직후에도 구 위치 행이 계속 읽혀야 한다.
        return artifactRootResolver.readableDeidVideoBases(rawSn, rawFilePathNm);
    }

    /**
     * Path Traversal(CWE-22) <b>+ 심링크 치환(CWE-59/367)</b> 방어 — 허용 base 후보 중 <b>하나라도</b>
     * 만족해야 통과하며, 통과한 <b>실경로</b>를 돌려준다.
     *
     * <h3>왜 lexical 검사만으로는 부족한가 (B-ISSUE-81)</h3>
     * <p>허용 base 에는 co-locate 비식별 디렉터리({@code dirname(원본)/{rawSn}/deid/})가 포함되는데,
     * 이는 <b>외부 비식별 벤더(KPST)가 공유 마운트로 직접 산출물을 쓰는 영역</b>이다. 그 안의 대상 파일을
     * 원본(비식별 이전) 영상 심링크로 바꿔 두면 {@code normalize()+startsWith} 는 그대로 통과하고,
     * 반환된 lexical 경로를 {@code UrlResource} 로 열 때 커널이 링크를 따라가 <b>마스킹 전 원본 영상이
     * "비식별 영상" 으로 200 서빙</b>된다(CWE-359). 신고 게이트({@code DE_IDNTF_YN})는 이 경로를 {@code 'Y'}
     * 로 보므로 뒤에서 막아주지 않는다.
     *
     * <h3>판정 로직을 복제하지 않는다</h3>
     * <p>실경로 판정은 같은 DB 값({@code DE_IDNTF_FILE_PATH_NM})을 읽는 형제 소비자
     * ({@code FfmpegFrameExtractor})가 쓰는 <b>같은 정적 판정기</b>
     * {@link VideoArtifactRootResolver#resolveRealPathUnder} 를 그대로 호출한다 — 이 결함군의 뿌리가
     * "가드가 여러 벌로 갈라져 하나씩 샌다" 였으므로 여기서 {@code toRealPath} 비교를 재구현하지 않는다.
     *
     * <h3>판정 대상 == 사용 대상 (TOCTOU 차단)</h3>
     * <p>판정이 돌려준 <b>실경로</b>를 그대로 반환해 호출부({@link #resolveStreamMeta})가 존재 확인·
     * Resource 생성에 사용하게 한다. lexical 경로를 돌려주면 검증 이후 다시 링크를 따라가므로
     * "실경로로 검증하고 lexical 경로로 연다"가 되어 창이 닫히지 않는다.
     *
     * <p><b>통과한 base 도 함께</b> 돌려준다 — 이 판정은 캐시({@code stream-meta}) 뒤에 있어 캐시 히트
     * 시에는 실행되지 않으므로, 호출부가 매 요청 재검증({@link #revalidateOpenTarget})을 하려면 base 가
     * 캐시 값에 함께 실려 있어야 한다(재검증을 위해 DB 를 다시 읽지 않기 위함).
     *
     * <p>어느 base 에도 속하지 않거나 실경로 검증에 실패하면 {@link ErrorCode#NOT_FOUND} 로 정규화한다
     * (S7 — 존재/권한 여부를 응답으로 구분해주지 않는 편이 원본 미노출 정책과 동급). 경로 원문은 로그에도
     * 남기지 않는다(CWE-209).
     */
    static VerifiedDeidFile resolveSafe(List<Path> baseDirs, String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "영상 경로가 비어있습니다.");
        }
        Path candidate = Paths.get(filePath);
        for (Path baseDir : baseDirs) {
            Path resolved = candidate.isAbsolute()
                    ? candidate.normalize()
                    : baseDir.resolve(candidate).normalize();
            if (!resolved.startsWith(baseDir)) {
                continue;
            }
            try {
                return new VerifiedDeidFile(
                        VideoArtifactRootResolver.resolveRealPathUnder(resolved, baseDir), baseDir);
            } catch (RuntimeException e) {
                // 실경로가 base 밖(심링크 치환) 또는 해석 불가 — 이 base 로는 허용하지 않고 다음 후보로.
                // 예외로 즉시 종료하지 않는 이유: 2-way allowlist 에서 앞 후보가 거부돼도 뒤 후보가
                // 정당하게 성립할 수 있다. 모든 후보가 실패하면 아래에서 NOT_FOUND 로 정규화한다.
                log.warn("[VideoStream] deid path realpath check failed for a base — trying next");
            }
        }
        log.error("[VideoStream] deid path outside all allowed bases (lexical or realpath) — refusing");
        throw new CustomException(ErrorCode.NOT_FOUND, "비식별 영상 파일이 존재하지 않습니다.");
    }

    /** 비식별 경로 + 그 base 도출에 필요한 원본 경로(co-locate). */
    private record DeidLocation(String deidPath, String rawFilePathNm) {
    }

    /**
     * 검증을 통과한 비식별 파일 — <b>실경로</b>와 그 판정에 쓰인 허용 base 의 한 쌍.
     * (base 를 함께 들고 다녀야 캐시 히트 시 같은 판정기로 재검증할 수 있다.)
     */
    record VerifiedDeidFile(Path realPath, Path base) {
    }
}
