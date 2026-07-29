package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;

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
    /**
     * S7 (DEV_FIX-A/H1) — 비식별 누락 신고 구간 게이트 재사용. 배선 누락이 결함의 원인이었으므로
     * 판정 로직을 복제하지 않고 단일 지점({@link LabelAccessGuard#requireNotUnderDeidentReport})만 호출한다.
     */
    private final LabelAccessGuard accessGuard;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    /**
     * 비식별 프레임 base — 비식별 프레임({@code DE_IDNTF_SRC_FILE_PATH_NM})은 이 base 하위
     * ({@code frames/deid/{rawSn}})에 저장되므로, 그 경로의 검증 base 도 여기여야 한다.
     */
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String storageDeidentifiedPath;

    /**
     * 프레임 이미지를 stream 으로 응답 — 기본 시그니처 (raw=false). 기존 호출자 호환.
     */
    public ResponseEntity<Resource> serve(Long rawSn, Integer frameNo) throws IOException {
        return serve(rawSn, frameNo, false, null);
    }

    /**
     * Phase 3 — V2 비식별 정책 갱신.
     *
     * <ul>
     *   <li>모든 영상은 기본 DEID 프레임을 서빙 (라벨러는 RAW 못 봄).</li>
     *   <li>REVIEWER 가 명시적으로 {@code raw=true} 요청 시에만 원본(filePath) 서빙.</li>
     *   <li>WORKER 의 {@code raw=true} 는 무시 (강제 DEID).</li>
     *   <li>PRVC/PSDO 영상에서 DEID 가 준비되지 않은 경우: NOT_FOUND (기존 회귀 유지).</li>
     * </ul>
     *
     * @param rawSn     영상 PK
     * @param frameNo   프레임 번호 (0-base)
     * @param allowRaw  REVIEWER 한정 — true 면 원본 경로 사용
     * @param actor     호출자 (null 이면 raw 옵션 무시)
     */
    public ResponseEntity<Resource> serve(Long rawSn, Integer frameNo, boolean allowRaw, TokenClaims actor)
            throws IOException {
        // 1) 영상 조회 — 비식별 정책 판정용
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));

        // 1-1) S7 (DEV_FIX-A/H1 — HIGH, CWE-359) — 비식별 누락 신고 구간(DE_IDNTF_YN='F')에는 프레임
        //      이미지를 서빙하지 않는다. 신고 시점에는 DE_IDNTF_SRC_FILE_PATH_NM 이 이미 채워져 있으므로
        //      아래 needsDeidentify() 분기만으로는 "얼굴이 안 지워진 그 비식별본"이 200 으로 나간다
        //      (라벨 좌표보다 상위 위험 = 실제 PII 이미지). 인가는 호출 측(VideoController.verifyRawAccess)이
        //      이미 수행했고, 이 게이트는 그 뒤의 프리컨디션이라 인가를 대체하지 않는다. rawSn 단위 1회 판정.
        //      resolve('F'→'Y') 로 자동 해제된다.
        accessGuard.requireNotUnderDeidentReport(rawSn);

        // 2) 프레임 조회
        LsDataSrc src = srcRepository.findByRawSnAndFrameNo(rawSn, frameNo)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다."));

        // 3) Phase 3 — V2 정책: 기본 DEID, REVIEWER 가 명시적으로 raw=true 요청 시에만 원본 허용
        boolean reviewerRequestedRaw = allowRaw && actor != null && actor.role() == Role.REVIEWER;
        String relPath;
        // 선택된 경로가 <b>비식별 컬럼</b>에서 왔는지 추적한다 — 경로 검증 base 를 그 출처에 맞춰 고른다.
        boolean fromDeidColumn = false;
        if (reviewerRequestedRaw) {
            relPath = src.getSrcFilePathNm();
        } else {
            String deid = src.getDeidFilePath();
            if (deid != null && !deid.isBlank()) {
                relPath = deid;
                fromDeidColumn = true;
            } else if (raw.needsDeidentify()) {
                // PRVC/PSDO — DEID 미준비 시 원본 노출 금지 (기존 회귀)
                log.warn("[FrameImage] deid path missing for sensitive video rawSn={} frameNo={}", rawSn, frameNo);
                throw new CustomException(ErrorCode.NOT_FOUND, "비식별 처리 미완료");
            } else {
                // ANONY + DEID 미준비 → 원본 폴백 (V2 정책상 비식별 우선이지만 ANONY 는 정책상 원본 노출 무방)
                relPath = src.getSrcFilePathNm();
            }
        }

        // 4) Path Traversal 방어 — 경로의 <b>출처 컬럼</b>에 맞는 base 로 검증한다(CWE-22 + CWE-359).
        //    비식별 프레임은 deidentified-path 하위에 저장되므로 rawBase 로만 검증하면 정상 비식별본이
        //    전부 FORBIDDEN 이 된다(실측: rawSn=26 프레임 403). 반대로 원본 경로에 deidBase 를 허용하면
        //    격리가 깨지므로, 출처별로 단일 base 를 고르고 비식별은 서브트리까지 강제한다.
        Path resolved;
        if (fromDeidColumn) {
            Path deidBase = Paths.get(storageDeidentifiedPath).toAbsolutePath().normalize();
            // H-2 — 서빙도 export 와 <b>같은 단일 판정기</b>를 쓴다: base 포함 + 존재/정규파일 +
            // <b>실경로(toRealPath) 기준</b> 비식별 서브트리. lexical 검사만 하면 base 내부 심링크
            // (frames/deid/x.jpg → frames/raw/x.jpg)로 원본 프레임이 "비식별본"으로 서빙된다(CWE-59/359).
            StorageSubtreePolicy.Verification v =
                    StorageSubtreePolicy.verifyDeidentifiedFile(deidBase, relPath);
            if (!v.ok()) {
                log.warn("[FrameImage] deid frame rejected rawSn={} frameNo={} verdict={}", rawSn, frameNo, v.verdict());
                throw switch (v.verdict()) {
                    case MISSING, NOT_REGULAR_FILE, REALPATH_FAILED, BLANK ->
                            new CustomException(ErrorCode.NOT_FOUND, "이미지 파일이 존재하지 않습니다.");
                    default -> new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 이미지 경로입니다.");
                };
            }
            resolved = v.path();
        } else {
            Path rawBase = Paths.get(storageRawPath).toAbsolutePath().normalize();
            resolved = resolveSafe(rawBase, relPath);
            // 5) 파일 존재 확인
            if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
                log.warn("[FrameImage] file not found rawSn={} frameNo={}", rawSn, frameNo);
                throw new CustomException(ErrorCode.NOT_FOUND, "이미지 파일이 존재하지 않습니다.");
            }
            // H-2 — 원본 경로도 실경로 재검증(심링크로 base 밖 파일을 서빙하는 우회 차단).
            try {
                if (!resolved.toRealPath().startsWith(rawBase.toRealPath())) {
                    log.warn("[FrameImage] symlink escaping base rawSn={} frameNo={}", rawSn, frameNo);
                    throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 이미지 경로입니다.");
                }
            } catch (IOException e) {
                log.warn("[FrameImage] realpath resolution failed rawSn={} frameNo={}", rawSn, frameNo);
                throw new CustomException(ErrorCode.NOT_FOUND, "이미지 파일이 존재하지 않습니다.");
            }
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
                // 신고 게이트(위 1-1)가 매 요청 평가되려면 클라이언트 캐시가 응답을 재사용하면 안 된다 —
                // max-age 동안 캐시된 마스킹 실패 프레임이 그대로 재노출된다(CWE-359/525).
                // 비식별 프레임 경로(serveDeidentified)·영상 스트림과 동일하게 no-store 로 통일.
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"frame_" + rawSn + "_" + frameNo + extOf(resolved) + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    /**
     * Phase 1 — <b>비식별 프레임 이미지</b>를 프레임 PK({@code SRC_SN}) 로 서빙한다.
     * ({@code GET /v1/frames/{srcSn}/deid-image} 백엔드)
     *
     * <h3>왜 {@link #serve(Long, Integer, boolean, TokenClaims)} 를 재사용하지 않는가</h3>
     * 위 메서드는 (rawSn, frameNo) 키이며 ANONY 영상에서 비식별본이 없으면 <b>원본으로 폴백</b>한다.
     * 해상도 파생 프레임은 원본 픽셀이 실재하지 않아 {@code SRC_FILE_PATH_NM} 이 null 이고
     * (E-ISSUE-41 정책 A), 이 엔드포인트의 계약은 "비식별 벌만 서빙"이다. 그래서 폴백이 없는
     * 별도 경로를 둔다 — 이 메서드는 {@code getSrcFilePathNm()} 을 <b>참조하지 않는다</b>.
     *
     * <h3>순서 고정 (보안)</h3>
     * ①인가({@code LabelAccessGuard} — CWE-639 IDOR) → ②신고 구간 게이트(CWE-359, 412) →
     * ③경로 해석. 게이트를 인가보다 앞에 두면 미배정 WORKER 가 412/404 로 프레임 존재 여부를
     * 탐색할 수 있으므로 인가가 항상 먼저다.
     *
     * <p>경로 검증은 {@link StorageSubtreePolicy#verifyDeidentifiedFile} <b>단일 판정기</b>에 위임한다
     * (raw·deid 두 base 동일 운영 형상 대응 + {@code toRealPath} 심링크 우회 차단, CWE-22/59).
     *
     * @param srcSn 프레임 PK
     * @param actor 호출자 토큰 클레임
     */
    public ResponseEntity<Resource> serveDeidentified(Long srcSn, TokenClaims actor) throws IOException {
        // 1) 인가 먼저 — 프레임 조회 포함(N+1 회피). null/미존재는 여기서 4xx 로 끝난다.
        LsDataSrc src = accessGuard.verifyAndGet(srcSn, actor);

        // 2) 인가 직후 신고 구간 게이트 — 역할 무관 프리컨디션(412). resolve('F'→'Y') 로 자동 해제.
        //    판정은 공유 게이트(DeidentReportGate)가 <b>이 영상 행</b> 기준으로 수행한다. 여기서
        //    'F' 비교를 국소 재구현하지 않는다(게이트 이원화 금지).
        accessGuard.requireNotUnderDeidentReport(src.getRawSn());

        // 3) 비식별 경로만 사용 — 원본 폴백 금지. 비면 404(원본 유출 차단).
        Path deidBase = Paths.get(storageDeidentifiedPath).toAbsolutePath().normalize();
        StorageSubtreePolicy.Verification v =
                StorageSubtreePolicy.verifyDeidentifiedFile(deidBase, src.getDeidFilePath());
        if (!v.ok()) {
            log.warn("[FrameDeidImage] rejected srcSn={} verdict={}", srcSn, v.verdict());
            throw switch (v.verdict()) {
                case BLANK, MISSING, NOT_REGULAR_FILE, REALPATH_FAILED ->
                        new CustomException(ErrorCode.NOT_FOUND, "비식별 이미지 파일이 존재하지 않습니다.");
                default -> new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 이미지 경로입니다.");
            };
        }
        Path resolved = v.path();

        // 4) 확장자 allowlist 기반 MIME (CWE-434)
        MediaType mediaType = resolveMediaType(resolved);

        // 5) 스트림 응답 — 대용량 메모리 적재 회피.
        //    NOFOLLOW_LINKS 필수(TOCTOU, CWE-367/59): 판정({@code verifyDeidentifiedFile})은 실경로
        //    기준이지만, 판정~open 사이에 그 <b>최종 컴포넌트</b>를 원본 프레임을 가리키는 심링크로
        //    교체하면 링크를 따라가 원본 픽셀이 "비식별본"으로 서빙된다. frames/deid/** 에 심링크는
        //    정상 산출물이 아니므로 링크면 열지 않고 실패시킨다(fail-closed). 크기도 같은 옵션으로 읽어
        //    판정 대상과 응답 대상이 어긋나지 않게 한다.
        long contentLength;
        InputStream in;
        try {
            contentLength = Files.readAttributes(resolved, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).size();
            in = Files.newInputStream(resolved, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            // 권한·교체·삭제 등 — 내부 경로/원인 노출 없이 규약 4xx 로 끝낸다(CWE-209, OWASP A10).
            log.warn("[FrameDeidImage] open failed srcSn={} reason={}", srcSn, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.NOT_FOUND, "비식별 이미지 파일이 존재하지 않습니다.");
        }

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(contentLength)
                // 신고(비식별 누락) 즉시 차단이 클라이언트에서도 성립해야 한다 — 캐시된 마스킹 실패
                // 이미지를 max-age 동안 재노출하면 방금 세운 412 게이트가 무력화된다(CWE-359).
                .cacheControl(CacheControl.noStore())
                // 헤더에는 서버가 통제하는 값만 넣는다 — 파일명 유래 문자열을 넣으면 CRLF 주입
                // (CWE-113) 표면이 생기므로 srcSn + MIME 파생 확장자로만 조립한다.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"frame_deid_" + srcSn + extOf(mediaType) + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(in));
    }

    /** allowlist MIME → 확장자. 사용자/파일시스템 유래 문자열을 헤더에 싣지 않기 위한 역매핑. */
    private static String extOf(MediaType mediaType) {
        if (MediaType.IMAGE_PNG.equals(mediaType)) {
            return ".png";
        }
        if ("webp".equals(mediaType.getSubtype())) {
            return ".webp";
        }
        return ".jpg";
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
