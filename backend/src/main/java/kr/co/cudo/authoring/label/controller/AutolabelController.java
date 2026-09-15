package kr.co.cudo.authoring.label.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.AutolabelRequest;
import kr.co.cudo.authoring.label.dto.AutolabelResponse;
import kr.co.cudo.authoring.label.service.AutolabelOnlineService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 3 — YOLO 오토라벨 수동 트리거 API.
 *
 * <p>라벨링 화면 툴바에서 한 프레임에 대해 YOLO 자동 검출을 즉시 실행한다. <b>DB 에 저장하지 않고</b>
 * ai-server 검출 좌표만 반환하며(SAM2 분할과 동일 stateless 프록시), 클라이언트가 캔버스 작업본에 반영·
 * 중복제거한 뒤 기존 라벨 저장 API(PUT /labels)로 확정한다. 배치 파이프라인 오토라벨은 본 API 와 무관하게
 * 기존대로 저장한다.
 *
 * <p>권한: REVIEWER/WORKER (본인 배정 프레임 검증 IDOR). PORTAL 채널 토큰은 역할 + 채널 격리로 이 창구에
 * 닿지 않는다 — 포털은 <b>자기 전용 창구</b>({@code PortalAiAssistController}, API-255)로 같은 추론 본체를 쓴다
 * (ADR-013 v24, 2026-09-15). 이 창구를 포털에 열지 말 것.
 */
@Tag(name = "Autolabel", description = "YOLO 오토라벨 수동 트리거 — REVIEWER/WORKER. 본인 배정 프레임 검증(IDOR).")
@RestController
@RequestMapping("/v1/frames")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class AutolabelController {

    private final AutolabelOnlineService autolabelOnlineService;

    @Operation(
            summary = "YOLO 오토라벨 수동 실행",
            description = "프레임 원본 이미지를 ai-server YOLO 로 추론하여 BBOX 검출 좌표만 반환한다(DB 미저장). "
                    + "클라이언트가 캔버스 작업본에 반영 후 PUT /labels 로 저장한다. ai-server mock 응답이면 빈 결과 + 안내 메시지만 반환. "
                    + "선택적 body {classes:[...]} 로 검출 대상 클래스(COCO 영문명)를 제한할 수 있으며, "
                    + "미지정 시 전체 검출(하위호환)."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "ai 응답 좌표 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 / 포털 채널 (CWE-639)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "작업 잠금 / 진행 중 중복 요청"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "ai-server 연동 실패")
    })
    @PostMapping("/{srcSn}/autolabel")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<AutolabelResponse> autolabel(
            @Parameter(description = "프레임 PK", required = true, example = "1") @PathVariable Long srcSn,
            @RequestBody(required = false) @Valid AutolabelRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        // body/classes 없이 호출하면 전체 검출(하위호환). 지정 시 해당 클래스만 검출(R3 AC3).
        java.util.List<String> classes = request == null ? null : request.classesOrNull();
        // shape 미지정(null) → BBOX(하위호환). POLYGON 이면 검출 박스마다 SAM 분할 폴리곤 반환(R12).
        kr.co.cudo.authoring.label.dto.AutolabelShape shape = request == null ? null : request.shape();
        // 정밀도 override(FEAT-007) — 미지정(null)이면 서비스가 시스템설정→상수 폴백 경로를 그대로 사용(무회귀).
        Double confThreshold = request == null ? null : request.confThreshold();
        Double simplifyTolerance = request == null ? null : request.simplifyTolerance();
        AutolabelOnlineService.AutolabelOutcome outcome =
                autolabelOnlineService.autolabel(srcSn, actor, classes, shape, confThreshold, simplifyTolerance);
        // 안내 우선순위: 폴리곤 상한/부분실패 message > 내부 mock 안내 > 정상(message 없음).
        // FE 는 message 유무로 경고를 표시하고 정상 "0건 검출" 과 구분한다(SAM2 세그와 대칭).
        if (outcome.message() != null) {
            return ApiResponse.ok(outcome.response(), outcome.message());
        }
        return outcome.mock()
                ? ApiResponse.ok(outcome.response(), AutolabelResponse.MOCK_UNAVAILABLE_MESSAGE)
                : ApiResponse.ok(outcome.response());
    }
}
