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
 * <p>라벨링 화면 툴바에서 한 프레임에 대해 YOLO 자동 검출을 즉시 실행한다. 결과는 LS_DATA_LBL 에
 * BBOX 로 저장(AI_INFO 출처 MANUAL_TRIGGER)되며 재실행은 기존 자동 라벨만 교체(수동 라벨 보존)한다.
 *
 * <p>권한: REVIEWER/WORKER (본인 배정 프레임 검증 IDOR). PORTAL 채널은 역할 + 채널 격리로 물리 차단
 * (ADR-013 — 포털은 오토라벨 미제공).
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
            description = "프레임 원본 이미지를 ai-server YOLO 로 추론하여 BBOX 자동 라벨을 저장한다. "
                    + "재실행 시 기존 자동 라벨만 교체(수동 라벨 보존). ai-server mock 응답이면 저장하지 않고 플래그만 반환. "
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
        // 내부 mock(모델 미로드) 시 안내 message 세팅 → FE 가 mock 경고와 정상 "0건 검출" 을 구분(SAM2 세그와 대칭).
        AutolabelOnlineService.AutolabelOutcome outcome = autolabelOnlineService.autolabel(srcSn, actor, classes);
        return outcome.mock()
                ? ApiResponse.ok(outcome.response(), AutolabelResponse.MOCK_UNAVAILABLE_MESSAGE)
                : ApiResponse.ok(outcome.response());
    }
}
