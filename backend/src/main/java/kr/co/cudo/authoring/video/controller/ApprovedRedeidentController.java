package kr.co.cudo.authoring.video.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.video.dto.RedeidentResponse;
import kr.co.cudo.authoring.video.service.ApprovedRedeidentService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 검수완료 영상 재비식별(Approved Re-deidentification) API — Phase 4 / UC018. REVIEWER 전용.
 *
 * <p>REVIEWER 가 검수완료(APPROVED) 영상의 비식별 재수행을 요청하면, {@link ApprovedRedeidentService}
 * 가 전제조건(APPROVED/기비식별/락) 을 검증하고 작업락을 선점한 뒤 KPST 위탁(REDEIDENT) 을 시작한다.
 * 완료(프레임 attach + DE_IDNTF_YN='Y') 는 폴링 잡이 비동기로 이어받으므로, 본 엔드포인트는 수락
 * 사실(ACCEPTED)만 동기 반환한다(202 Accepted + status=ACCEPTED — 비동기 처리 수락 시맨틱).
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>인가(CWE-285/863): {@code @PreAuthorize("hasRole('REVIEWER')")} 로 수직 권한 상승 차단.
 *       SecurityConfig 의 메서드 시큐리티가 활성(다른 컨트롤러와 동일) 상태에서 강제된다.</li>
 *   <li>입력검증(CWE-20): {@code rawSn} 은 {@code Long} 경로변수로 타입 강제(비숫자 → 400/매핑 미스).
 *       null/존재 검증은 서비스에서 NOT_FOUND/INVALID_INPUT 으로 처리된다.</li>
 *   <li>Mass Assignment(CWE-915): 요청 바디 없음 — 경로변수 + 인증 principal 만 사용한다.</li>
 *   <li>비즈니스 로직은 서비스에 위임(컨트롤러는 위임만). Entity 직접 반환 없음(RedeidentResponse DTO).</li>
 * </ul>
 */
@Tag(name = "ApprovedRedeident", description = "검수완료 영상 재비식별 — REVIEWER 전용 (UC018).")
@RestController
@RequestMapping("/v1/videos")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
// 의존하는 ApprovedRedeidentService 와 동일 조건부 등록 — KPST 토글 OFF 환경에서 컨트롤러가
// 미존재 서비스 빈을 요구해 ApplicationContext 가 깨지는 것을 방지(@ConditionalOnProperty 정합).
@ConditionalOnProperty(prefix = "kpst.deid", name = "enabled", havingValue = "true")
public class ApprovedRedeidentController {

    private final ApprovedRedeidentService approvedRedeidentService;

    @Operation(
            summary = "검수완료 영상 재비식별 요청 (REVIEWER)",
            description = "검수완료(APPROVED) 영상의 비식별을 재수행 요청한다. 전제조건 검증 + 작업락 선점 후 " +
                    "KPST 위탁(REDEIDENT) 을 시작하며, 완료는 폴링 잡이 비동기로 이어받는다. " +
                    "응답은 수락 사실(status=ACCEPTED) + 위탁 식별자(procLogSn/kpstPrjId) 만 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "수락(비동기 처리 시작) — status=ACCEPTED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "미검수 / 이미 비식별됨 / 이미 처리 중")
    })
    @PostMapping("/{rawSn}/redeident")
    @PreAuthorize("hasRole('REVIEWER')")
    public ResponseEntity<ApiResponse<RedeidentResponse>> redeident(
            @Parameter(description = "검수완료 영상 PK", required = true, example = "1") @PathVariable Long rawSn,
            @AuthenticationPrincipal TokenClaims actor) {
        // 비동기 처리 수락(폴링 잡이 완료를 이어받음) — 202 Accepted 시맨틱.
        return ResponseEntity.accepted()
                .body(ApiResponse.ok(approvedRedeidentService.requestRedeident(rawSn, actor)));
    }
}
