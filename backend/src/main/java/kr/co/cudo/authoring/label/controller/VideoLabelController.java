package kr.co.cudo.authoring.label.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.VideoLabelSaveRequest;
import kr.co.cudo.authoring.label.dto.VideoLabelSaveResponse;
import kr.co.cudo.authoring.label.service.VideoLabelSaveService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API-196 — 영상 라벨 <b>일괄 확정 저장</b> REST API.
 *
 * <ul>
 *   <li>PUT /v1/videos/{rawSn}/labels : 영상 전체 라벨 + 프레임 폐기 상태를 한 트랜잭션으로 확정</li>
 * </ul>
 *
 * <p>프레임 단위 저장({@code PUT /v1/frames/{srcSn}/labels}, {@code LabelController})은 평상시 편집용으로
 * <b>그대로 유지</b>된다. 두 축을 합치지 않는다 — 이쪽은 산출 회차를 불러온 뒤 <b>영상 전체</b>를
 * 확정하는 축이고, 일부 프레임만 저장하면 한 영상 안에 서로 다른 시점의 프레임이 섞인다.
 *
 * <p>별도 컨트롤러인 이유는 {@code LabelController} 가 {@code /v1/frames} 에 매핑돼 있어 영상 경로를
 * 담을 수 없기 때문이다(같은 URL 에 쿼리 파라미터로 행위 분기 금지 원칙과 동일 취지).
 *
 * @design API-196
 * @req R6
 */
@Tag(name = "Label", description = "라벨 저장 — 영상 단위 확정 저장(불러온 회차를 저장으로 확정)")
@RestController
@RequestMapping("/v1/videos")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class VideoLabelController {

    private final VideoLabelSaveService videoLabelSaveService;

    /**
     * 영상 전체 라벨·폐기 상태를 확정한다.
     *
     * <p>불러오기(API-195 {@code GET /v1/videos/{rawSn}/versions/{version}/labels})는 서버에 아무것도
     * 쓰지 않으므로, 화면에서 한 일은 이 저장을 눌러야 남는다. 저장하지 않고 떠나면 전부 되돌아간다.
     *
     * @design API-196
     */
    @Operation(
            summary = "영상 라벨 일괄 확정 저장 (REVIEWER 전체 / WORKER 본인 배정)",
            description = "화면에서 편집한 영상 전체의 라벨과 프레임 폐기 여부를 한 트랜잭션으로 확정한다. "
                    + "폐기와 복원도 이 저장에 함께 묶이므로 따로 확정하는 경로를 두지 않는다. "
                    + "프레임별 lblVer(판번호)를 전수 검증해 하나라도 어긋나면 아무것도 저장하지 않는다 "
                    + "(일부만 저장하면 서로 다른 시점의 프레임이 섞이기 때문). "
                    + "frames[].items 는 그 프레임의 라벨 전체이며 빈 배열은 그 프레임 라벨 전량 삭제다. "
                    + "frames[].dscdYn 을 보내지 않으면 현재 폐기 값을 그대로 둔다. "
                    + "loadedVersion 은 어느 산출 회차에서 시작한 편집인지 기록용이며 저장 여부를 가르지 않는다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패 / 같은 프레임 중복 / 프레임 수 상한 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음 / 그 영상에 속하지 않는 프레임"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "판번호 불일치(다른 사용자가 먼저 저장) / 작업이 잠긴 영상"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "412", description = "비식별 재처리 대기 중인 영상 — 작업락 유무와 무관하게 이 코드가 우선한다")
    })
    @PutMapping("/{rawSn}/labels")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<VideoLabelSaveResponse> saveVideoLabels(
            @Parameter(description = "영상 PK (LS_DATA_RAW.RAW_SN)", required = true, example = "1")
            @PathVariable Long rawSn,
            @Valid @RequestBody VideoLabelSaveRequest req,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(videoLabelSaveService.save(rawSn, req, actor));
    }
}
