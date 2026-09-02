package kr.co.cudo.authoring.aiserver.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.aiserver.dto.AiSrvrCreateRequest;
import kr.co.cudo.authoring.aiserver.dto.AiSrvrResponse;
import kr.co.cudo.authoring.aiserver.dto.AiSrvrStatusUpdateRequest;
import kr.co.cudo.authoring.aiserver.dto.AiSrvrUpdateRequest;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.service.AiSrvrAdminService;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.security.adminsession.AdminSessionGate;
import kr.co.cudo.authoring.common.security.adminsession.RequiresAdminSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 장비 <b>노드 원장</b> 관리 API — 외부 URL = {@code /api/v1/manage/ai-servers}
 * (context-path={@code /api}).
 *
 * <h3>왜 이 창구가 생겼나</h3>
 * <p>장비 주소의 진실원이 설정값에서 원장으로 옮겨졌는데 <b>그 원장에 행을 넣을 통로가 없었다</b>.
 * 두 번째 장비를 넣으려면 DB 에 직접 INSERT 해야 했고, 그러면 식별자 형식·상태 전이 규칙이 통째로
 * 우회된다. 배포 설정값은 원장이 비어 있을 때 <b>최초 1회 씨앗</b>으로만 쓰이며 그 뒤로는 원장이
 * 이긴다 — 화면에서 바꾼 주소가 재기동으로 되돌아가지 않는다.
 *
 * <h3>보안 — 한 클래스 안에서 축이 갈린다</h3>
 * <ul>
 *   <li><b>조회는 검수자 이상, 쓰기는 관리자 + 관리자 단기 유효창</b>이다. 목록을 막으면 장비 상태를
 *       확인할 길이 없어지고, 쓰기는 운영·관리 성격이라 유효창을 함께 요구한다(AC-1090).</li>
 *   <li>★ <b>클래스에 건 게이트를 관리자로 올리지 말 것</b> — 그러면 조회까지 좁아져 관리 화면이
 *       열리자마자 빈 채로 죽는다. 좁히는 표기는 <b>쓰기 메서드에만</b> 얹는다. 관리자는 검수자
 *       권한을 계층으로 물려받으므로 조회에도 그대로 들어온다.</li>
 *   <li>★ <b>{@link RequiresAdminSession} 을 클래스에 붙이지 말 것</b> — 인터셉터가 클래스 축도
 *       보므로 조회까지 유효창을 요구하게 된다.</li>
 *   <li>유효창은 권한을 <b>대체하지 않고 가산</b>되며 역할을 승격시키지도 않는다. 검수자는 유효창을
 *       열어도 이 쓰기 창구에 들어오지 못한다 — 계층은 관리자가 검수자 자리를 통과하게 할 뿐 그
 *       반대는 성립하지 않는다.</li>
 *   <li>권한 부족과 유효창 부재는 <b>같은 403·같은 문구</b>로 떨어진다. 구분해 알리면 응답 자체가
 *       유효창 상태를 알려주는 신호가 된다(CWE-209).</li>
 *   <li>요청 본문은 DTO 로만 받는다 — 상태·점검 카운터·등록일시를 요청으로 받지 않는다(CWE-915).</li>
 * </ul>
 *
 * <h3>목록에 페이지를 두지 않은 이유</h3>
 * <p>원장 크기는 <b>운영자가 등록한 장비 수</b>다. 사용자 입력으로 늘어나는 표가 아니라 상한이
 * 사람 손에 있으므로, 페이지를 두면 화면이 유형별로 묶어 보여 주기만 어려워진다.
 *
 * @design DOMAIN-004
 * @design API-226
 * @design API-227
 * @design API-228
 * @design API-229
 * @design API-230
 * @design ADR-046
 * @design ADR-057
 * @design AC-1088
 * @design AC-1089
 * @design AC-1090
 * @design AC-1091
 */
@Tag(name = "AiServer", description = "AI 장비 노드 원장 — 조회는 검수자 이상, 등록·수정·상태 전이·삭제는 관리자 + 관리자 유효창.")
@RestController
@RequestMapping("/v1/manage/ai-servers")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('REVIEWER')")
public class AiSrvrController {

    private final AiSrvrAdminService aiSrvrAdminService;

    // ★경로의 장비 식별자에 형식 제약을 걸지 않는다. 형식을 어긴 값은 DB 체크 제약 때문에 원장에
    //   <존재할 수 없으므로> 그대로 404 로 떨어지며, 그것이 「없는 장비」라는 사실을 정확히 말한다.
    //   여기서 400 으로 가로채면 같은 「없음」이 값 모양에 따라 두 가지 코드로 갈린다.
    //   로그에도 안전하다 — 이 값은 원장 조회를 통과한 뒤에만 기록된다(CWE-117).

    @Operation(summary = "AI 장비 목록 조회 (REVIEWER 이상)",
            description = "유형으로 거를 수 있다. 지정하지 않으면 전부. 행마다 식별자·이름·주소·유형·상태·"
                    + "최근 점검 시각·연속 실패 수·연속 성공 수와 용도별 부하를 담는다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "알 수 없는 유형값"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REVIEWER 권한 없음")
    })
    @GetMapping
    public ApiResponse<List<AiSrvrResponse>> list(
            @Parameter(description = "장비 유형 (INFERENCE/TIMESERIES). 비우면 전부")
            @RequestParam(required = false) LsAiSrvr.SrvrType srvrTypeCd) {
        return ApiResponse.ok(aiSrvrAdminService.list(srvrTypeCd));
    }

    /**
     * 장비 등록 — <b>관리자 전용 + 유효창</b>. 클래스에 걸린 검수자 게이트보다 좁은 표기를 이 자리에만
     * 얹는다(API-227 · AC-1090).
     */
    @Operation(summary = "AI 장비 등록 (ADMIN + 관리자 유효창)",
            description = "상태는 가용으로 시작한다. 식별자는 소문자·숫자 20자 이내이며 이미 있으면 409 다. "
                    + "주소는 스킴(http/https)·형식·예약 대역 검증을 받는다 — 평문 http 와 사설 대역은 통과가 정상이다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "등록됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "식별자 형식 위반, 주소가 비었거나 형식·스킴 위반·예약 대역, 유형 미지정"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "ADMIN 권한이 없거나 유효한 관리자 유효창이 없다. 두 사유를 응답으로 구분하지 않는다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "같은 식별자가 이미 있다")
    })
    @Parameter(in = ParameterIn.HEADER, name = AdminSessionGate.HEADER, required = true,
            description = "관리자 유효창이 발급한 단기 토큰. 없거나 만료됐으면 403 이다.")
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresAdminSession
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AiSrvrResponse> create(@Valid @RequestBody AiSrvrCreateRequest request,
                                              @AuthenticationPrincipal TokenClaims actor) {
        // 감사 주체는 인증에서만 — 바디로 받지 않는다(위조 방지).
        return ApiResponse.ok(aiSrvrAdminService.register(request, actor == null ? null : actor.sub()));
    }

    /** 이름·주소 수정 — <b>관리자 전용 + 유효창</b>(API-228 · AC-1090). */
    @Operation(summary = "AI 장비 이름·주소 수정 (ADMIN + 관리자 유효창)",
            description = "보내지 않은 항목은 그대로 둔다. 유형과 상태는 이 창구로 바꾸지 않는다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "주소 형식·스킴 위반 또는 예약 대역"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "ADMIN 권한이 없거나 유효한 관리자 유효창이 없다. 두 사유를 응답으로 구분하지 않는다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "그 식별자의 장비가 없다")
    })
    @Parameter(in = ParameterIn.HEADER, name = AdminSessionGate.HEADER, required = true,
            description = "관리자 유효창이 발급한 단기 토큰. 없거나 만료됐으면 403 이다.")
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresAdminSession
    @PatchMapping("/{srvrId}")
    public ApiResponse<AiSrvrResponse> update(
            @Parameter(description = "장비 식별자", required = true, example = "gpu02")
            @PathVariable String srvrId,
            @Valid @RequestBody AiSrvrUpdateRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(aiSrvrAdminService.updateProfile(srvrId, request,
                actor == null ? null : actor.sub()));
    }

    /**
     * 상태 전이 — <b>관리자 전용 + 유효창</b>(API-229 · AC-1091).
     *
     * <p>거부 셋(허용되지 않는 전이 · 같은 상태 · 그 유형의 마지막 가용 장비)이 모두 409 이며 사유는
     * 메시지로 갈린다. ⚠ 상태점검 배치의 자동 이용불가 전이는 이 창구를 타지 않으므로 마지막 가용
     * 장비 보호에 걸리지 않는다 — 그건 사람의 결정이 아니라 관측이다.
     */
    @Operation(summary = "AI 장비 상태 전이 (ADMIN + 관리자 유효창)",
            description = "가용·이용불가·정비중·비활성 넷이며 아무 상태로나 갈 수 있는 것이 아니다. "
                    + "같은 상태로의 전이와 그 유형의 마지막 가용 장비를 내리는 요청은 409 다.")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "전이됨"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "알 수 없는 상태값"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "ADMIN 권한이 없거나 유효한 관리자 유효창이 없다. 두 사유를 응답으로 구분하지 않는다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "그 식별자의 장비가 없다"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "허용되지 않는 전이이거나 같은 상태이거나, 그 유형의 마지막 가용 장비다. 사유는 메시지로 구분한다.")
    })
    @Parameter(in = ParameterIn.HEADER, name = AdminSessionGate.HEADER, required = true,
            description = "관리자 유효창이 발급한 단기 토큰. 없거나 만료됐으면 403 이다.")
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresAdminSession
    @PatchMapping("/{srvrId}/status")
    public ApiResponse<AiSrvrResponse> changeStatus(
            @Parameter(description = "장비 식별자", required = true, example = "gpu02")
            @PathVariable String srvrId,
            @Valid @RequestBody AiSrvrStatusUpdateRequest request,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(aiSrvrAdminService.changeStatus(srvrId, request.srvrSttsCd(),
                actor == null ? null : actor.sub()));
    }

    /** 삭제 — <b>관리자 전용 + 유효창</b>(API-230 · AC-1091). */
    @Operation(summary = "AI 장비 삭제 (ADMIN + 관리자 유효창)",
            description = "그 유형의 마지막 가용 장비는 지울 수 없다. 배정 이력이 남아 있는 장비도 지울 수 없다 "
                    + "— 어느 장비가 그 영상을 처리했는지는 남아야 하기 때문이다(비활성으로 내려 쓴다).")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "삭제됨. 본문 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
                    description = "ADMIN 권한이 없거나 유효한 관리자 유효창이 없다. 두 사유를 응답으로 구분하지 않는다."),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "그 식별자의 장비가 없다"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "그 유형의 마지막 가용 장비이거나, 배정 이력이 남아 있다")
    })
    @Parameter(in = ParameterIn.HEADER, name = AdminSessionGate.HEADER, required = true,
            description = "관리자 유효창이 발급한 단기 토큰. 없거나 만료됐으면 403 이다.")
    @PreAuthorize("hasRole('ADMIN')")
    @RequiresAdminSession
    @DeleteMapping("/{srvrId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(description = "장비 식별자", required = true, example = "gpu02")
            @PathVariable String srvrId) {
        aiSrvrAdminService.delete(srvrId);
    }
}
