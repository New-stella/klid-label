package kr.co.cudo.authoring.transfer.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.transfer.dto.ImportScanRequest;
import kr.co.cudo.authoring.transfer.dto.ImportScanResponse;
import kr.co.cudo.authoring.transfer.service.ImportScanService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 외부 산출물 <b>폴더 검사</b> API — 외부 URL = {@code /api/v1/imports/scan}
 * (context-path={@code /api}).
 *
 * <h3>미리보기와 적재는 나뉜 두 단계다</h3>
 * <p>검수자가 폴더 경로를 넣으면 서버가 훑어 무엇이 몇 건인지, 처리할 수 없는 항목이 무엇인지 먼저
 * 보여 준다. 적재는 그 결과를 사람이 확인한 뒤의 별도 요청이다. 한 번에 처리하면 사람이 확인할
 * 자리가 사라진다.
 *
 * <h3>POST 이지만 아무것도 저장하지 않는다 (AC-041)</h3>
 * <p>경로에는 구분자와 공백이 섞여 주소줄에 실으면 접근 기록과 중간 경유지에 남는다. 그래서 본문으로
 * 받되, 이 요청은 상태를 만들지 않으므로 여러 번 보내도 결과가 같다.
 *
 * <h3>보안</h3>
 * <ul>
 *   <li><b>REVIEWER 전용</b> — {@code SecurityConfig} 의 {@code /v1/**} 매처(내부 채널 + 저작도구
 *       역할)와 이 클래스의 {@code @PreAuthorize} 이중 방어.</li>
 *   <li>★ <b>이 창구는 일부러 좁히지 않았다</b> — 같은 도메인의 적재 실행·분류 대응 확정은
 *       관리자로 좁혔지만, 탐색·검사와 이력 조회는 검수자 권한으로 응답한다(ROLE-004). 함께
 *       좁히면 화면이 열리자마자 빈 채로 죽는다. 관리자는 계층으로 여기에도 그대로 들어온다.</li>
 *   <li>인가는 <b>경로 판정보다 먼저</b> 평가된다 — 권한 없는 요청에는 그 위치가 있는지 없는지가
 *       응답으로 새지 않는다(CWE-209, AC-048).</li>
 *   <li>경로는 사람이 넣는 외부 문자열이라 허용 저장소 범위 판정을 반드시 거친다(CWE-22/59) —
 *       판정은 {@code ImportSourcePolicy} 한 곳이다.</li>
 * </ul>
 *
 * @design DOMAIN-017
 * @design ROLE-004
 * @design API-205
 * @design AC-041
 * @design AC-048
 */
@Tag(name = "ImportScan", description = "외부 산출물 폴더 검사(미리보기) — REVIEWER 전용.")
@RestController
@RequestMapping("/v1/imports")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('REVIEWER')")
public class ImportScanController {

    private final ImportScanService importScanService;

    @Operation(summary = "산출물 폴더 검사 (REVIEWER)",
            description = "폴더를 훑어 들어올 내용과 경고를 돌려준다. 아무것도 저장하지 않는다.")
    @PostMapping("/scan")
    public ApiResponse<ImportScanResponse> scan(@Valid @RequestBody ImportScanRequest request) {
        return ApiResponse.ok(importScanService.scan(request));
    }
}
