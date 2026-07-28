package kr.co.cudo.authoring.label.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.video.service.FrameImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 프레임 이미지 바이너리 서빙 — 라벨링 캔버스용.
 *
 * <p>보안 (Critical):
 * <ul>
 *   <li><b>인증 필수</b> — REVIEWER/WORKER/PORTAL_USER 만 접근 (PreAuthorize).</li>
 *   <li><b>IDOR 방어 (CWE-639)</b> — LabelAccessGuard 위임. WORKER 는 본인 배정 영상만.</li>
 *   <li><b>Path Traversal 방어 (CWE-22)</b> — DB 의 FILE_PATH 를 baseDir 기준 normalize 후
 *       startsWith 검증. baseDir 외부 경로는 403.</li>
 *   <li><b>Information Leak 방어 (CWE-209)</b> — 파일 부재/오류 시 내부 경로 노출 금지.</li>
 *   <li><b>MIME 검증</b> — 확장자 allowlist (.jpg/.jpeg/.png/.webp) 만 서빙.</li>
 * </ul>
 *
 * <p>응답:
 * <ul>
 *   <li>200 + image/jpeg(or png) — 이미지 바이트 스트림 (Cache-Control: no-store — 비식별 누락 신고
 *       게이트가 매 요청 평가되도록 클라이언트 캐시 재사용 금지)</li>
 *   <li>403 — 본인 배정 아님 / Path traversal 의심</li>
 *   <li>404 — 프레임 또는 파일 없음</li>
 * </ul>
 *
 * <p>이미지는 {@code <img>} 태그로 직접 로드되지 않는다 (인증 헤더 미전달).
 * FE 는 axios 로 fetch → blob URL 변환 → konva Image 에 전달한다.
 */
@Slf4j
@Tag(name = "FrameImage", description = "프레임 이미지 바이너리 서빙 — 라벨링 캔버스용. 인증/IDOR/Path Traversal 방어.")
@RestController
@RequestMapping("/v1/frames")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class FrameImageController {

    private final LsDataSrcRepository srcRepository;
    private final LabelAccessGuard accessGuard;
    /** 비식별 이미지 서빙 위임 — 비식별 경로 판정(StorageSubtreePolicy)을 한 곳에서만 수행한다. */
    private final FrameImageService frameImageService;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    @Operation(
            summary = "프레임 이미지 다운로드",
            description = "프레임 이미지 바이너리 반환. 인증 필수, WORKER 는 본인 배정 프레임만 (CWE-639). " +
                    "Path traversal 방어 (CWE-22). DEV/LOCAL 시드는 합성 placeholder."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공 — image/jpeg or image/png"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 / Path traversal 의심"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임 또는 이미지 파일 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "412", description = "비식별 누락 신고 구간(재비식별 대기) — 이미지 서빙 차단")
    })
    @GetMapping("/{srcSn}/image")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER', 'PORTAL_USER')")
    public ResponseEntity<Resource> getImage(
            @Parameter(description = "프레임 PK (SRC_SN)", required = true, example = "1") @PathVariable Long srcSn,
            @AuthenticationPrincipal TokenClaims actor) throws IOException {

        // 1) 프레임 조회 + IDOR 가드 (LabelAccessGuard 가 NOT_FOUND/FORBIDDEN 처리)
        accessGuard.verifyAccess(srcSn, actor);
        LsDataSrc src = srcRepository.findById(srcSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다."));

        // 1-1) S7 (DEV_FIX-A/H1 — HIGH, CWE-359) — 비식별 누락 신고 구간(DE_IDNTF_YN='F')에는 프레임
        //      이미지를 서빙하지 않는다. 이 경로는 비식별 판정 없이 <b>원본 프레임</b>을 그대로 서빙하므로
        //      신고(=비식별 누락 확인) 상태에서 열려 있으면 PII 이미지가 그대로 나간다. 인가(verifyAccess)
        //      <b>이후</b> 평가해 게이트가 인가를 대체하지 않게 한다. resolve('F'→'Y') 로 자동 해제.
        //      (원본 프레임 자체를 WORKER 에게 서빙하는 정책 문제는 본 수정 범위 밖 — 신고 구간만 차단한다.)
        accessGuard.requireNotUnderDeidentReport(src.getRawSn());

        // 2) Path traversal 방어 — baseDir 기준 normalize + startsWith 검증
        Path baseDir = Paths.get(storageRawPath).toAbsolutePath().normalize();
        Path resolved = resolveSafe(baseDir, src.getSrcFilePathNm());

        // 3) 파일 존재 확인 — 부재 시 404 (내부 경로 노출 금지)
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            log.warn("[FrameImage] file not found srcSn={} path-not-found (raw rel: {})",
                    srcSn, src.getSrcFilePathNm());
            throw new CustomException(ErrorCode.NOT_FOUND, "이미지 파일이 존재하지 않습니다.");
        }

        // 4) 확장자 allowlist 기반 MIME 결정
        MediaType mediaType = resolveMediaType(resolved);

        // 5) InputStream → InputStreamResource 로 응답 (대용량 메모리 적재 회피)
        long contentLength = Files.size(resolved);
        InputStream in = Files.newInputStream(resolved);
        InputStreamResource body = new InputStreamResource(in);

        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(contentLength)
                // 위 1-1 신고 게이트가 매 요청 평가되도록 클라이언트 캐시 재사용을 금지한다 —
                // max-age 동안 캐시된 프레임(PII 노출분)이 게이트를 우회해 재노출된다(CWE-359/525).
                // /deid-image · 영상 /stream 과 동일 정책(no-store).
                .cacheControl(CacheControl.noStore())
                // 보안 헤더 보강 — 다운로드 강제 X (캔버스 inline 표시 목적)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"frame_" + srcSn + extOf(resolved) + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    /**
     * Phase 1 — 비식별 프레임 이미지 서빙 (해상도 파생 프레임 대응).
     *
     * <p>기존 {@code /image} 와 <b>별도 sub-resource</b> 로 둔다(쿼리 파라미터 행위 분기 금지 —
     * {@code rules/api-design.md}). 해상도 파생 프레임은 원본 픽셀이 실재하지 않아
     * {@code SRC_FILE_PATH_NM} 이 null 이므로 {@code /image} 로는 서빙되지 않는다.
     *
     * <p>인가·게이트·경로 검증은 모두 {@link FrameImageService#serveDeidentified} 가 수행한다
     * (비식별 판정기 단일화 — 컨트롤러에서 검증 로직을 재구현하지 않는다).
     */
    @Operation(
            summary = "비식별 프레임 이미지 다운로드",
            description = "프레임의 <b>비식별</b> 이미지 바이너리 반환(DE_IDNTF_SRC_FILE_PATH_NM). "
                    + "원본 경로 폴백 없음 — 비식별 경로가 없으면 404. 인증 필수, WORKER 는 본인 배정 "
                    + "프레임만(CWE-639). 심링크/경로순회 차단(CWE-22/59)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공 — image/jpeg or image/png"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 / 비식별 서브트리 밖 경로 / 허용 외 확장자"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임 없음 / 비식별 이미지 파일 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "412", description = "비식별 누락 신고 구간(재비식별 대기) — 이미지 서빙 차단")
    })
    @GetMapping("/{srcSn}/deid-image")
    // 내부 전용 — PORTAL 채널은 /v1/portal/** 전용 경로를 쓴다(기존 /image 와 동일 정책).
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ResponseEntity<Resource> getDeidImage(
            @Parameter(description = "프레임 PK (SRC_SN)", required = true, example = "1") @PathVariable Long srcSn,
            @AuthenticationPrincipal TokenClaims actor) throws IOException {
        return frameImageService.serveDeidentified(srcSn, actor);
    }

    /**
     * Path traversal 방어 (CWE-22).
     * <p>filePath 가 baseDir 밖으로 나가면 FORBIDDEN 으로 거부.
     * 절대 경로면 그대로(단 baseDir 안), 상대 경로면 baseDir 기준 resolve.
     *
     * <p>VisibleForTesting — 단위 테스트에서 직접 검증하기 위해 public.
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
            // 침입 시도일 가능성 — 403 으로 거부, 상세 경로 노출 금지
            throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 이미지 경로입니다.");
        }
        return resolved;
    }

    /** 확장자 allowlist 기반 MIME 결정 — 알 수 없는 확장자는 거부. VisibleForTesting. */
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
        // allowlist 외 — 정책상 거부 (CWE-434 차단)
        throw new CustomException(ErrorCode.FORBIDDEN, "허용되지 않은 이미지 확장자입니다.");
    }

    private static String extOf(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(dot) : "";
    }
}
