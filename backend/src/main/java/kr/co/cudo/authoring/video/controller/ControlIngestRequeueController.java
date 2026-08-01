package kr.co.cudo.authoring.video.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.video.dto.ControlIngestRequeueBulkRequest;
import kr.co.cudo.authoring.video.dto.ControlIngestRequeueBulkResponse;
import kr.co.cudo.authoring.video.dto.ControlIngestRequeueResponse;
import kr.co.cudo.authoring.video.service.ControlIngestRequeueService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관제 인입 재큐 API — <b>REVIEWER 전용</b> (설계 §6-0-1-b).
 *
 * <p>{@code LS_DATA_INGEST} 에서 종결({@code FAILED})된 인입 행을 미처리({@code PENDING})로 되돌려
 * 다음 스캔이 다시 집게 한다. 종결 사유가 <b>설정·환경 오류</b>(관제 NAS 마운트 루트 불일치, 파일 미도착
 * 대기 상한 초과)일 수 있는데, {@code UK_LS_DATA_INGEST_CLIP} 때문에 관제 재INSERT 가 불가하고 인입 행
 * 삭제도 금지라 <b>이 통로가 없으면 그 클립은 수동 SQL 없이 영원히 적재되지 않는다</b>.
 *
 * <p>대량 오설정 회수가 실사용 시나리오이므로 <b>단건 + 일괄</b>을 함께 제공한다. 일괄은 요청이
 * 정하는 것이 <b>건수 상한뿐</b>이며 대상 상태({@code FAILED})는 서버가 고정한다 — 상태를 요청으로
 * 받으면 {@code DONE} 을 되살려 중복 적재를 유발할 수 있다(fail-closed).
 *
 * <p>자동 재큐 배치는 두지 않는다 — 원인이 안 고쳐진 채 {@code FAILED} ↔ {@code PENDING} 무한 왕복이 된다.
 */
@Tag(name = "ControlIngestRequeue",
        description = "관제 인입 재큐 — REVIEWER 전용. 종결(FAILED) 인입 행을 미처리로 되돌린다.")
@RestController
@RequestMapping("/v1/control-ingests")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Validated
public class ControlIngestRequeueController {

    private final ControlIngestRequeueService requeueService;

    @Operation(
            summary = "인입 단건 재큐 (REVIEWER)",
            description = "종결(FAILED)된 인입 행 1건을 미처리(PENDING)로 되돌린다. 대기 예산(최초 미도착 "
                    + "관측 시각·재시도 예정 시각)도 함께 리셋되므로 되살린 행이 다음 tick 에 즉시 "
                    + "재종결되지 않는다. FAILED 가 아니면 409 로 거부된다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재큐 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 입력(rcptnSn)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "인입 행 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "종결(FAILED) 상태가 아님")
    })
    @PostMapping("/{rcptnSn}/requeue")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<ControlIngestRequeueResponse> requeue(
            @Parameter(description = "인입 행 식별자(수신일련번호)", required = true, example = "1024")
            @PathVariable @Min(value = 1, message = "rcptnSn 은 1 이상이어야 합니다.") Long rcptnSn,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(requeueService.requeue(rcptnSn, actorId(actor)));
    }

    @Operation(
            summary = "인입 일괄 재큐 (REVIEWER)",
            description = "종결(FAILED) 인입 행을 오래된 수신일시 순으로 최대 limit 건 되살린다. "
                    + "대상 상태는 FAILED 고정이며 요청은 건수만 정한다. 무제한 갱신을 막기 위해 "
                    + "limit 상한(500)을 강제하고, 남은 건수를 응답에 담아 재호출로 이어서 회수한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재큐 수행(0건도 정상)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "limit 범위 위반"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @PostMapping(value = "/requeue", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<ControlIngestRequeueBulkResponse> requeueBatch(
            @Valid @RequestBody ControlIngestRequeueBulkRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(requeueService.requeueBatch(request, actorId(actor)));
    }

    /**
     * 감사 로그용 행위자 식별자 — 토큰 subject 만 쓴다.
     *
     * <p>{@code @PreAuthorize} 를 통과했으므로 인증은 이미 보장된다. 여기 값은 <b>표시용</b>이며 인가
     * 판정에 쓰지 않는다(요청 값으로 권한을 정하지 않는다).
     */
    private static String actorId(TokenClaims actor) {
        return actor == null ? "unknown" : actor.sub();
    }
}
