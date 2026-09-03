package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.portal.dto.PortalStreamUrlResponse;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import kr.co.cudo.authoring.video.service.VideoStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 포털 업로드 영상 재생 — 구간 요청 스트리밍 + 단기 서명 주소 발급.
 *
 * <h3>★ 서빙 대상은 사용자가 올린 <b>원본</b>이다</h3>
 * <p>이 경로의 자산에는 비식별 단계가 없다 — 본인 데이터라 가려진 사본이 만들어지지 않으므로 원본을
 * 그대로 서빙한다. 내부 채널 영상 스트리밍이 갖는 「항상 가려진 사본을 서빙하고 그것이 없으면 원본을
 * 감춘다」는 규칙을 <b>이 경로로 옮겨 오지 않는다</b>: 그 규칙은 가려진 사본이 존재한다는 전제 위에
 * 서 있고 여기에는 그 전제가 없다. 옮겨 오면 정상 자산이 전부 감춰진다.
 *
 * <p>같은 이유로 <b>비식별 누락 신고 게이트의 대상이 아니다</b>(형제 경로인 업로드 프레임 이미지
 * 서빙과 같은 판단).
 *
 * <h3>상태를 묻지 않는다</h3>
 * <p>추출이 끝났는지를 조건으로 걸지 않는다 — 마킹이 추출보다 앞서므로 추출 전에 재생할 수 있어야
 * 하고, 끝난 뒤에도 다시 볼 수 있어야 한다. 재생을 막는 것은 <b>파일이 아직 갖춰지지 않은 경우</b>
 * 하나뿐이다.
 *
 * @design API-238
 * @design API-239
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalUploadStreamService {

    /** 한 번에 내려보내는 구간 상한(8MB) — 재요청 빈도와 메모리 사이의 절충. */
    private static final long CHUNK_BYTES = 8L * 1024 * 1024;

    /** 재생 주소 경로 틀 — 발급과 실제 창구가 어긋나지 않도록 한 곳에 둔다. */
    private static final String STREAM_PATH = "/api/v1/portal/uploads/%d/stream";

    private final PortalUploadAssetRepository assetRepository;
    private final PortalStoragePathGuard pathGuard;
    private final PortalStreamUrlSigner signer;

    // ==================================================================
    // 서명 주소 발급
    // ==================================================================

    /**
     * 단기 서명 주소 발급 — <b>발급 시점에</b> 소유자와 자산 종류를 판정하고 그 결과를 서명에 담는다.
     * 그래서 서명 주소로 하는 재생과 토큰을 실은 직접 재생의 최종 접근 가능 주체가 같다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public PortalStreamUrlResponse issueSignedUrl(Long uldSn, String portalUserNo) {
        PortalUploadAsset asset = requirePlayable(uldSn, portalUserNo);
        if (!signer.isConfigured()) {
            // 설정이 없으면 서명 없이 내주지 않는다 — 없는 채로 열면 아무나 재생할 수 있는 주소가 나간다.
            log.warn("[PortalStream] sign-secret 미설정 — 서명 주소 발급 불가 uldSn={}", uldSn);
            throw new CustomException(ErrorCode.SERVICE_UNAVAILABLE, "재생 주소 발급을 사용할 수 없습니다.");
        }
        requireFileReady(asset, uldSn);
        PortalStreamUrlSigner.SignedParams params = signer.sign(uldSn, portalUserNo);
        String url = String.format(STREAM_PATH, uldSn)
                + "?exp=" + params.exp()
                + "&u=" + java.net.URLEncoder.encode(portalUserNo, java.nio.charset.StandardCharsets.UTF_8)
                + "&sig=" + params.sig();
        return new PortalStreamUrlResponse(url, params.exp(), params.ttlSeconds());
    }

    // ==================================================================
    // 스트리밍
    // ==================================================================

    /**
     * 구간 요청 스트리밍. 구간 헤더가 없으면 전체(200), 있으면 부분(206)이다.
     *
     * <p>응답은 <b>캐시하지 않도록</b> 내려보낸다 — 본인 자산이라도 중간 저장소에 남기지 않는다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public ResponseEntity<ResourceRegion> stream(Long uldSn, String portalUserNo, HttpHeaders headers) {
        PortalUploadAsset asset = requirePlayable(uldSn, portalUserNo);
        Path realFile = requireFileReady(asset, uldSn);

        long contentLength;
        try {
            contentLength = Files.size(realFile);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.NOT_FOUND, "재생할 파일이 아직 없습니다.");
        }
        MediaType mediaType = resolveMediaType(asset.mimeTypeNm());
        Resource resource = new VideoStreamService.NoFollowFileResource(realFile, contentLength);

        List<HttpRange> ranges;
        try {
            ranges = headers.getRange();
        } catch (IllegalArgumentException ex) {
            return rangeNotSatisfiable(contentLength);
        }
        if (!ranges.isEmpty()) {
            HttpRange range = ranges.get(0);
            long start = range.getRangeStart(contentLength);
            if (start >= contentLength) {
                return rangeNotSatisfiable(contentLength);
            }
            long end = Math.min(start + CHUNK_BYTES - 1, range.getRangeEnd(contentLength));
            // Content-Range/Content-Length 는 컨버터가 write 시점에 직접 add 한다 — 여기서 미리 set 하면
            // 불변 헤더 맵에 중복 add 가 되어 500 이 된다.
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .contentType(mediaType)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header("X-Content-Type-Options", "nosniff")
                    .cacheControl(CacheControl.noStore())
                    .body(new ResourceRegion(resource, start, end - start + 1));
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(new ResourceRegion(resource, 0, contentLength));
    }

    // ==================================================================
    // 내부
    // ==================================================================

    /**
     * 소유자·자산 종류 판정. 남의 자산과 없는 자산을 <b>같은 코드</b>로 거절해 실재 여부가 드러나지
     * 않게 한다. 영상이 아닌 자산은 기다려도 달라지지 않는 영구 조건이라 입력 오류로 돌려준다.
     */
    private PortalUploadAsset requirePlayable(Long uldSn, String portalUserNo) {
        if (portalUserNo == null || portalUserNo.isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
        PortalUploadAsset asset = assetRepository.findByOwner(uldSn, portalUserNo)
                .orElseThrow(() -> new CustomException(ErrorCode.FORBIDDEN,
                        "본인 자산이 아니거나 존재하지 않습니다."));
        if (!PortalUploadLedger.TYPE_VIDEO.equals(asset.uldTypeCd())) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "영상 자산만 재생할 수 있습니다.");
        }
        return asset;
    }

    /**
     * 재생할 파일이 실제로 갖춰졌는지 확인하고 <b>실경로</b>를 돌려준다.
     *
     * <p>경로 판정과 실제 열기의 대상이 같아야 한다(CWE-22/59/367) — 자산 식별자로 지정된 것 밖의
     * 파일에 닿지 못하도록 저장 경로 밖 이탈은 거절하고, 부재는 「아직 없다」로 답한다.
     */
    private Path requireFileReady(PortalUploadAsset asset, Long uldSn) {
        String stored = asset.filePathNm();
        if (stored == null || stored.isBlank()) {
            throw new CustomException(ErrorCode.NOT_FOUND, "재생할 파일이 아직 없습니다.");
        }
        Path baseDir = pathGuard.baseDir();
        Path resolved = pathGuard.resolveSafe(baseDir, Paths.get(stored));
        PortalStoragePathGuard.RealPathCheck check = pathGuard.checkRealWithinBase(baseDir, resolved);
        return switch (check.verdict()) {
            case OK -> {
                if (!Files.isRegularFile(check.realPath(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new CustomException(ErrorCode.NOT_FOUND, "재생할 파일이 아직 없습니다.");
                }
                yield check.realPath();
            }
            case ESCAPED -> {
                log.warn("[PortalStream] path escaping base rejected uldSn={}", uldSn);
                throw new CustomException(ErrorCode.FORBIDDEN, "본인 자산이 아니거나 존재하지 않습니다.");
            }
            case ABSENT, UNRESOLVABLE -> throw new CustomException(ErrorCode.NOT_FOUND,
                    "재생할 파일이 아직 없습니다.");
        };
    }

    /** 저장된 확정 매체 유형만 쓴다 — 확장자 추정 금지. */
    private static MediaType resolveMediaType(String mimeTypeNm) {
        if (mimeTypeNm == null || mimeTypeNm.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(mimeTypeNm);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private static ResponseEntity<ResourceRegion> rangeNotSatisfiable(long contentLength) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + contentLength)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
