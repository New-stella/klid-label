package kr.co.cudo.authoring.portal.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.portal.dto.PortalDatasetCleanupTriggerRequest;
import kr.co.cudo.authoring.portal.service.PortalDatasetCleanupTriggerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 포털 배포본 정리 삭제 트리거 수신 창구 — <b>서버간(시스템 주체) 축</b>.
 *
 * <p>포털이 학습데이터셋의 새 버전을 받으면 이 창구를 불러 「그보다 이전의 올드 해제본을 정리하라」는
 * 신호를 보낸다. 채널별 별도 배포(ADR-012)가 정한 자산 전달 3단계의 마지막 단계다.
 *
 * <h3>★ 인가는 여기가 아니라 매처가 강제한다 — 여기에 다시 구현하지 말 것</h3>
 * <p>{@code SecurityConfig} 의 {@code /v1/portal-system/**} 매처가 <b>채널 + 주체 축</b>을 함께
 * 요구한다({@code CHANNEL_PORTAL} + {@code SUBJECT_PORTAL_SYSTEM}). 주체 축 권한은
 * {@code JwtAuthenticationFilter} 만 부여하고 역할 계층에 얹히지 않는다.
 * <ul>
 *   <li>사용자 주체 토큰 → 이 창구: 매처가 <b>403</b>.</li>
 *   <li>시스템 주체 토큰 → {@code /v1/portal/**} 사용자 창구: 필터가 {@code ROLE_PORTAL_USER} 를
 *       아예 부여하지 않아 <b>구조적으로 도달하지 못한다.</b> 격리는 양방향이다.</li>
 *   <li>시스템 계정 주체 식별자 목록이 비면 아무도 시스템 주체가 아니라 <b>이 창구가 닫힌다</b>
 *       (fail-closed).</li>
 * </ul>
 * <p>메서드 {@code @PreAuthorize} 로 주체 축을 다시 판정하지 않는다 — 판정이 두 곳으로 갈리면
 * 한쪽만 갱신됐을 때 조용히 어긋난다. 인가의 1차 원천은 순서 있는 매처다.
 *
 * <h3>인증 헤더</h3>
 * <p>토큰은 포털 전용 인계 헤더로 실려 오며(Bearer 스킴 미사용), 그 이름과 검증은 인증 필터가
 * 소유한다. <b>이 컨트롤러는 헤더를 직접 읽지 않는다</b> — 이름 리터럴을 여기에 박으면 두 번째
 * 진실원이 된다. 연동의 인증 축·창구 구성·어휘의 정본은 INT-014 다.
 *
 * <h3>★ 응답은 언제나 같다 — 대상 유무를 드러내지 않는다</h3>
 * <p>첫 수신도, 재수신도, 이 배포본이 알지 못하는 데이터셋도 <b>모두 202</b> 다. 오류로 답하면
 * 포털이 지울 것도 없는 신호를 계속 재전송하고, 대상 유무를 드러내면 존재 여부를 탐색하는 수단이
 * 된다. 응답 본문에도 그 구분을 싣지 않는다.
 *
 * <h3>이번 범위에 없는 것 (빠뜨린 것이 아니다)</h3>
 * <ul>
 *   <li><b>실제 해제본 삭제 실행</b> — 지울 대상인 해제본 저장소·원장이 아직 서지 않았다.
 *       이 창구는 트리거를 접수해 기산점을 남기는 데까지다.</li>
 *   <li><b>신규 데이터마트 조달</b>(포털 소재 조회 pull·압축 해제·적재) — 그 창구는 포털이 소유하고
 *       응답 규격이 회신되지 않았다. 추정으로 만들지 않는다.</li>
 * </ul>
 *
 * @design API-244
 * @design INT-014
 * @design AC-1102
 * @design AC-1103
 */
@Tag(name = "Portal System",
        description = "포털 서버간 연동 창구 — 시스템 주체 토큰 전용. 포털 사용자 채널(/v1/portal/**)과 경로 접두가 갈려 있으며, 그 분리가 주체 축 인가를 강제하는 수단이다.")
@RestController
@RequestMapping("/v1/portal-system")
@RequiredArgsConstructor
public class PortalDatasetCleanupTriggerController {

    private final PortalDatasetCleanupTriggerService cleanupTriggerService;

    @Operation(summary = "포털 배포본 정리 삭제 트리거 수신",
            operationId = "receivePortalDatasetCleanupTrigger",
            description = "포털이 학습데이터셋의 새 버전을 받았을 때 부르는 창구다. 트리거를 접수해 정리 유예의 기산점을 남긴다. "
                    + "받은 즉시 지우지 않는다 — 사용자가 그 해제본을 물고 저작하는 중일 수 있어 받은 시점부터 유예를 세고 그 뒤에 지운다. "
                    + "같은 데이터셋 코드와 버전으로 다시 오면 성공 무처리로 답하고 처음 받은 시각을 갱신하지 않는다. "
                    + "이 배포본에 해제된 적 없는 데이터셋이 와도 오류가 아니라 같은 접수 응답으로 답하며, 대상 유무를 응답으로 드러내지 않는다. "
                    + "보존 기간·기산점 규칙·정리 절차의 정본은 이 창구가 아니라 포털 작업 데이터 보존기간 만료 자동 삭제 기능이다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202",
                    description = "트리거를 접수했다. 재수신도, 알지 못하는 데이터셋도 같은 응답이다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "데이터셋 코드나 버전이 없거나 형식을 어겼다. 응답에 내부 경로·예외 흔적·저장 구조를 싣지 않는다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401",
                    description = "인증 실패 — 토큰이 없거나 서명이 맞지 않거나 만료됐다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "주체 축 불일치 — 이 창구는 시스템 주체 토큰만 받는다.")
    })
    @PostMapping("/dataset-cleanups")
    public ResponseEntity<ApiResponse<Void>> receiveDatasetCleanupTrigger(
            @Valid @RequestBody PortalDatasetCleanupTriggerRequest request) {
        cleanupTriggerService.receive(request);
        // ★ 202 다 — 접수했을 뿐 아직 아무것도 지우지 않았다. 200 으로 바꾸면 「처리를 마쳤다」로
        //   읽혀 포털이 정리 완료를 전제하게 된다.
        return ResponseEntity.accepted().body(ApiResponse.ok(null));
    }
}
