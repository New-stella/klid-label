package kr.co.cudo.authoring.label.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.video.service.FrameImageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 프레임 이미지 바이너리 서빙 — 라벨링 캔버스용.
 *
 * <p><b>서빙 대상은 기본적으로 비식별(DEID) 프레임</b>이다 — 라벨링은 비식별 영상의 프레임으로
 * 수행하는 것이 설계이며, 이 경로만 원본을 서빙하던 미배선 상태를 정합했다. 원본은 REVIEWER 가
 * {@code raw=true} 를 명시할 때만 나간다. 판정·검증은 전부
 * {@link FrameImageService#serveBySrcSn} 단일 원천에 위임한다.
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
@Tag(name = "FrameImage", description = "프레임 이미지 바이너리 서빙 — 라벨링 캔버스용. 인증/IDOR/Path Traversal 방어.")
@RestController
@RequestMapping("/v1/frames")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class FrameImageController {

    /**
     * 이미지 서빙 위임 — 인가·신고 게이트·비식별/원본 판정·경로 검증(StorageSubtreePolicy)을
     * 한 곳에서만 수행한다. 컨트롤러는 HTTP 계약(경로·파라미터·역할)만 담당한다.
     */
    private final FrameImageService frameImageService;

    @Operation(
            summary = "프레임 이미지 다운로드",
            description = "프레임 이미지 바이너리 반환. <b>기본은 비식별(DEID) 프레임</b>이며 REVIEWER 가 "
                    + "<code>raw=true</code> 를 명시할 때만 원본을 서빙한다(WORKER 의 raw=true 는 무시). "
                    + "비식별 경로가 없으면 PRVC/PSDO 는 404, ANONY 는 원본 폴백. 인증 필수, WORKER 는 "
                    + "본인 배정 프레임만 (CWE-639). Path traversal/심링크 방어 (CWE-22/59)."
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
            @Parameter(description = "REVIEWER 전용 — true 면 원본(비식별 전) 프레임 요청. WORKER 는 무시되고 DEID 강제.")
            @RequestParam(name = "raw", required = false, defaultValue = "false") boolean raw,
            @AuthenticationPrincipal TokenClaims actor) throws IOException {
        // 인가(CWE-639) → 신고 구간 게이트(412) → 비식별/원본 판정 → 경로 검증 → 스트림 응답까지
        // 전부 서비스가 수행한다. 컨트롤러가 판정을 재구현하면 (rawSn, frameNo) 경로와 갈라져
        // "한쪽만 원본이 새는" 상태가 된다(이 결함의 원인) — 단일 원천에만 위임한다.
        return frameImageService.serveBySrcSn(srcSn, raw, actor);
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

    // 경로 검증(resolveSafe) · MIME allowlist(resolveMediaType) 는 FrameImageService 로 일원화했다 —
    // 컨트롤러가 같은 로직을 복사 보유하면 정책이 갈라진다(이 결함의 원인).
}
