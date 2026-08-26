package kr.co.cudo.authoring.transfer.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.transfer.dto.ImportBrowseResponse;
import kr.co.cudo.authoring.transfer.service.ImportBrowseService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 이관 대상 위치 <b>탐색</b> API — 외부 URL = {@code /api/v1/imports/folders},
 * {@code /api/v1/imports/files} (context-path={@code /api}).
 *
 * <h3>검사와 같은 계약면, 다른 자리</h3>
 * <p>검사({@code /scan})와 같은 {@code /v1/imports} 아래에 두고 같은 역할 게이트를 건다. 다만
 * 클래스를 나눈 이유는 두 창구의 성격이 다르기 때문이다 — 검사는 폴더를 <b>열어 파싱</b>하는
 * 무거운 요청이라 경로를 본문으로 받고, 탐색은 이름만 훑는 조회라 주소로 받는다. 한 클래스에 섞으면
 * 그 두 성격이 같은 문서 아래 묶여 어느 규약이 어디에 적용되는지 흐려진다.
 *
 * <h3>창구를 폴더와 파일로 나눈 이유</h3>
 * <p>같은 자리에 종류를 가르는 조건을 달아 동작을 바꾸지 않는다는 규약을 따른다. 한 창구에
 * {@code ?type=folder|file} 같은 갈래를 두면 그 자리가 무엇을 돌려주는지 요청을 봐야만 알 수 있다.
 *
 * <h3>목록을 잘라 버리지 않고 나눠서 이어 준다</h3>
 * <p>한 번의 요청은 정해진 수만큼만 담고 <b>이어받을 자리</b>를 함께 돌려준다. 화면은 그 값을 그대로
 * 다시 실어 이어 받으며, 비어서 돌아오면 그 자리를 끝까지 본 것이다. <b>담은 것이 없어도 이어받을
 * 자리가 올 수 있다</b> — 그때 끝난 것으로 보면 그 폴더의 나머지가 통째로 사라진다.
 *
 * <h3>보조 수단이지 대체 수단이 아니다</h3>
 * <p>이 창구가 응답하지 않아도 검수자는 경로를 손으로 적어 검사를 요청할 수 있어야 한다. 그래서
 * 검사·적재는 이 창구에 의존하지 않고, 여기서 고른 값도 그쪽에서 다시 판정받는다.
 *
 * <h3>보안</h3>
 * <ul>
 *   <li><b>REVIEWER 전용</b> — {@code SecurityConfig} 의 {@code /v1/**} 매처와 이 클래스의
 *       {@code @PreAuthorize} 이중 방어.</li>
 *   <li>인가는 <b>경로 판정보다 먼저</b> 평가된다 — 권한 없는 요청에는 그 위치가 있는지 없는지가
 *       응답으로 새지 않는다(CWE-209, AC-048).</li>
 *   <li>허용 저장소 범위 판정은 검사·적재와 <b>같은 판정기</b>({@code ImportSourcePolicy})가 한다 —
 *       탐색이 자기 허용 목록을 따로 갖지 않으므로 한 창구에서 막히는 자리가 다른 창구에서 열리지
 *       않는다(AC-048).</li>
 *   <li>⚠ <b>인지·수용된 위험</b> — 탐색을 열면 허용 루트 하위가 열거 가능해진다. 그 범위는 검사가
 *       이미 받아들이던 범위와 같아 새 표면이 열리는 것은 아니며, 사용자가 안내받고 선택했다.</li>
 * </ul>
 *
 * @design DOMAIN-017
 * @design API-221
 * @design API-222
 * @design AC-120
 * @design AC-048
 */
@Tag(name = "ImportBrowse", description = "이관 대상 위치 탐색(폴더·영상 파일) — REVIEWER 전용.")
@RestController
@RequestMapping("/v1/imports")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@PreAuthorize("hasRole('REVIEWER')")
public class ImportBrowseController {

    private final ImportBrowseService importBrowseService;

    @Operation(summary = "이관 대상 폴더 탐색 (REVIEWER)",
            description = "허용 저장소 범위 안의 하위 폴더를 한 단계씩 돌려준다."
                    + " 위치를 생략하면 허용 저장소 루트 목록을 돌려준다."
                    + " 한 번에 다 주지 않고 이어받을 자리(nextCursor)와 함께 나눠서 이어 준다.")
    @GetMapping("/folders")
    public ApiResponse<ImportBrowseResponse> folders(
            @Parameter(description = "탐색할 폴더 위치. 생략하면 허용 저장소 루트 목록.")
            @RequestParam(name = "path", required = false) String path,
            @Parameter(description = "이어받을 자리. 앞선 응답의 nextCursor 를 그대로 준다."
                    + " 주지 않으면 처음부터 본다. 이 이름 자체는 응답에 담기지 않는다.")
            @RequestParam(name = "cursor", required = false) String cursor) {
        return ApiResponse.ok(importBrowseService.listFolders(path, cursor));
    }

    @Operation(summary = "이관 대상 영상 파일 탐색 (REVIEWER)",
            description = "지정한 폴더 안의 영상 파일 목록을 돌려준다. 폴더와 영상이 아닌 파일은 담기지 않는다."
                    + " 한 번에 다 주지 않고 이어받을 자리(nextCursor)와 함께 나눠서 이어 준다.")
    @GetMapping("/files")
    public ApiResponse<ImportBrowseResponse> files(
            @Parameter(description = "영상 파일을 찾을 폴더 위치. 루트 목록 조회가 없어 생략할 수 없다.",
                    required = true)
            @RequestParam(name = "path") String path,
            @Parameter(description = "이어받을 자리. 앞선 응답의 nextCursor 를 그대로 준다."
                    + " 주지 않으면 처음부터 본다. 이 이름 자체는 응답에 담기지 않는다.")
            @RequestParam(name = "cursor", required = false) String cursor) {
        return ApiResponse.ok(importBrowseService.listVideoFiles(path, cursor));
    }
}
