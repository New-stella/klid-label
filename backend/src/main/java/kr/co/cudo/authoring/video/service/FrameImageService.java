package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.storage.StorageSubtreePolicy;
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
