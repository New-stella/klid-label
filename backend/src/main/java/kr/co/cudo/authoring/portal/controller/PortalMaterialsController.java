package kr.co.cudo.authoring.portal.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.portal.dto.PortalMaterialsStatusResponse;
import kr.co.cudo.authoring.portal.service.PortalMaterialsProvisionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 포털 <b>소재 조달</b> 창구 — 사용자가 고른 데이터셋의 배포 압축본을 우리 작업영역으로 가져와 푼다.
 *
 * <h3>이 창구가 있는 이유 — 규격이 비워 둔 사이를 잇는다</h3>
 * <p>포털이 저작도구 자리로 보낼 때 <b>주소의 경로 변수</b>({@code /portal/datasets/&#123;데이터셋 숫자 식별자&#125;})에 싣고,
 * 저작도구 프론트가 그 값을 <b>직접 읽어</b> 이 창구로 넘긴다. 그러면 우리 서버가 그 값으로
 * <b>포털 소재 조회 창구를 부른다</b>. ⚠ 포털이 우리에게 주입하는 것이 아니라 우리가 주소에서 읽는다.
 *
 * <h3>★ 인가 축을 혼동하지 말 것</h3>
 * <p>이 창구는 <b>포털 채널의 「사람」 축</b>이다. {@code /v1/portal/**} 매처가 포털 채널 토큰과
 * 포털 사용자 역할을 요구하므로 그 배선을 그대로 쓴다.
 * <p>⚠⚠ <b>서버간 축({@code /v1/portal-system/**})과 다르다.</b> 그쪽은 포털 <b>서버</b>가 사전
 * 공유 키로 부르는 자리이고 방향도 발급 주체도 반대다 — 그 필터·매처·키를 여기서 재사용하지 않는다.
 *
 * <h3>왜 비동기인가</h3>
 * <p>배포 압축본이 기가바이트급이라 요청 안에서 복사·해제를 끝내면 타임아웃이 난다. 착수는
 * <b>202(접수)</b> 로 답하고 진행은 상태 조회로 본다.
 *
 * <h3>★ 착수는 멱등이다</h3>
 * <p>같은 대상이 이미 준비 완료거나 진행 중이면 <b>새로 시작하지 않고</b> 그 상태를 답한다.
 * 그래서 착수 응답은 언제나 <b>202</b> 이고, 상태 구분은 본문의 상태 값이 싣는다.
 *
 * <h3>응답에 내부 경로를 싣지 않는다</h3>
 * <p>조달처 절대경로와 저장소 루트는 <b>내부 경로</b>라 응답에 담지 않는다(CWE-209).
 *
 * @design INT-014
 * @design INT-013
 * @design ADR-012
 */
@Tag(name = "Portal Materials",
        description = "포털 소재 조달 — PORTAL_USER 전용. 데이터셋 배포 압축본을 작업영역으로 복사·해제한다.")
@RestController
@RequestMapping("/v1/portal/datasets")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class PortalMaterialsController {

    private final PortalMaterialsProvisionService provisionService;

    @Operation(summary = "소재 조달 착수",
            description = "데이터셋의 배포 압축본을 포털 소재영역에서 우리 작업영역으로 복사해 해제한다. "
                    + "접수만 답하며(202) 진행은 상태 조회로 확인한다. 이미 준비 완료거나 진행 중이면 "
                    + "새로 시작하지 않고 그 상태를 그대로 답한다(멱등). 원본은 읽기 전용이라 건드리지 않는다.")
    @PostMapping("/{datasetId}/materials")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ResponseEntity<ApiResponse<PortalMaterialsStatusResponse>> provision(
            @PathVariable long datasetId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(provisionService.start(datasetId), "조달 요청을 접수했습니다."));
    }

    @Operation(summary = "소재 조달 상태 조회",
            description = "조달 진행 상태와 준비된 소재 요약(항목 수·총 바이트·영상 수)을 돌려준다. "
                    + "내부 절대경로는 싣지 않는다.")
    @GetMapping("/{datasetId}/materials")
    @PreAuthorize("hasRole('PORTAL_USER')")
    public ApiResponse<PortalMaterialsStatusResponse> status(@PathVariable long datasetId) {
        return ApiResponse.ok(provisionService.status(datasetId));
    }
}
