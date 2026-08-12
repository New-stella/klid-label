package kr.co.cudo.authoring.version.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.version.dto.DiffResponseDto;
import kr.co.cudo.authoring.version.dto.LabelDiffDto;
import kr.co.cudo.authoring.version.dto.RollbackRequest;
import kr.co.cudo.authoring.version.dto.VersionItem;
import kr.co.cudo.authoring.version.dto.VersionLabelsResponse;
import kr.co.cudo.authoring.version.dto.VersionResponse;
import kr.co.cudo.authoring.version.dto.VideoVersionItem;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.service.StartVersionService;
import kr.co.cudo.authoring.version.service.VersionService;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 버전관리(라벨 변경 이력) REST API (DB 스냅샷 기반).
 *
 * <ul>
 *   <li>GET  /v1/frames/{srcSn}/versions               : 프레임 단위 버전 목록(검수 승인 스냅샷)</li>
 *   <li>GET  /v1/versions/{version}/diff?compareWith=  : 두 버전(versionHash) 라벨 단위 비교</li>
 *   <li>GET  /v1/versions/{version}/diff-with-working : 버전 ↔ 현재 작업본(LS_DATA_LBL) 라벨 단위 비교</li>
 *   <li>POST /v1/versions/{version}/rollback           : REVIEWER 전체 / WORKER 본인 배정 — 롤백</li>
 *   <li>GET  /v1/videos/{rawSn}/versions               : 영상 단위 산출 버전 목록(시작 버전 선택지, R6)</li>
 *   <li>GET  /v1/videos/{rawSn}/versions/{version}/labels : 로드 버전 라벨 조회(API-195, 읽기 전용)</li>
 * </ul>
 *
 * <p>버전 스냅샷은 검수 승인(APPROVED) 시점에만 생성된다(SFR-08, {@code VersionService.commitApproved}).
 * 라벨 저장(임시저장)은 버전을 만들지 않으므로 별도 수동 커밋(POST /frames/{srcSn}/commit) 엔드포인트는 폐기됐다.
 *
 * <p>식별자는 라벨 스냅샷의 SHA-256(versionHash). FE 와이어 포맷 호환을 위해 응답 필드명은
 * {@code commitSha} 를 유지하나 의미는 versionHash 다.
 */
@Tag(name = "Version", description = "DB 스냅샷 기반 버전관리 — 변경 이력 / diff / 롤백. 롤백은 REVIEWER 전체 또는 WORKER 본인 배정 프레임.")
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class VersionController {

    private final VersionService versionService;
    /** R6 — 영상 단위 「시작 버전 선택」 오케스트레이션(프레임 단위 계약은 무변경). */
    private final StartVersionService startVersionService;

    @Operation(
            summary = "프레임 버전 이력 조회",
            description = "프레임(srcSn = LS_DATA_SRC.SRC_SN) 단위로 라벨 변경 버전 이력을 시간 역순으로 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "프레임 없음")
    })
    @GetMapping("/frames/{srcSn}/versions")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<List<VersionItem>> listVersions(@Parameter(description = "프레임 PK (LS_DATA_SRC.SRC_SN)", required = true, example = "1") @PathVariable Long srcSn,
                                                       @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(versionService.listVersions(srcSn, actor));
    }

    @Operation(
            summary = "버전 간 diff 조회 (라벨 단위)",
            description = "두 버전(from / to) 간 라벨 단위 변경 차이를 반환. compareWith는 from 버전 해시(versionHash). "
                    + "ADDED/MODIFIED/REMOVED 로 분류된 라벨별 변경 사항을 배열로 반환한다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "버전 없음")
    })
    @GetMapping("/versions/{version}/diff")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<List<LabelDiffDto>> diff(@Parameter(description = "비교 대상(to) 버전 해시", required = true) @PathVariable("version") String toHash,
                                                @Parameter(description = "기준(from) 버전 해시", required = true) @RequestParam("compareWith") String fromHash,
                                                @AuthenticationPrincipal TokenClaims actor) {
        DiffResponseDto dto = versionService.diff(fromHash, toHash, actor);
        return ApiResponse.ok(dto.labels());
    }

    /**
     * 버전 스냅샷 ↔ 현재 작업본 diff.
     *
     * <p>승인 버전이 1건뿐인 프레임은 {@code /diff?compareWith=} 로 비교할 대상이 없어 변경 내역을 볼 수
     * 없다. 이 sub-resource 는 버전 1건만 지정해 "승인 이후 지금까지" 를 비교한다.
     * 기존 {@code /diff} 계약은 그대로 유지된다(신규 경로 추가만).
     *
     * @req R1 버전 1건 선택 시 현재 작업본과 diff
     * @req R3 별도 sub-resource 신설 — 기존 diff 계약 무변경
     * @req R5 기존 diff 와 동일한 보안 게이트(400/403/404/412)
     */
    @Operation(
            summary = "버전 ↔ 현재 작업본 diff 조회 (라벨 단위)",
            description = "지정 버전(versionHash) 스냅샷을 from, 현재 작업본(LS_DATA_LBL)을 to 로 하여 "
                    + "라벨 단위 변경 차이를 반환한다. 승인 버전이 1건뿐이라 두 버전 비교가 불가능한 "
                    + "프레임에서도 '승인 이후 지금까지'의 변경을 확인할 수 있다. "
                    + "조회 전용이며 새 버전을 저장하지 않는다. "
                    + "REVIEWER는 모든 프레임, WORKER는 본인에게 배정된 프레임만 가능."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "해시 형식 오류 / 프레임 단위 비교 대상 아님 / 손상된 스냅샷"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "버전/프레임 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "412", description = "비식별 재처리 대기 중인 영상")
    })
    @GetMapping("/versions/{version}/diff-with-working")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<List<LabelDiffDto>> diffWithWorking(@Parameter(description = "기준(from) 버전 해시", required = true) @PathVariable("version") String versionHash,
                                                           @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(versionService.diffWithWorking(versionHash, actor).labels());
    }

    @Operation(
            summary = "특정 버전으로 롤백 (REVIEWER 전체 / WORKER 본인 배정)",
            description = "지정 버전의 라벨 스냅샷으로 되돌린다. 대상 버전 행이 다시 active 로 전환되며"
                    + "(새 버전 적층 없음 — 롤백 결과 해시는 대상 스냅샷과 항상 동일), 되돌리기 행위"
                    + "(누가·언제·어느 버전으로)는 LS_DATA_LBL_HSTRY 롤백 이벤트로 기록된다. "
                    + "이미 해당 버전이 active 면 아무것도 바꾸지 않는다(no-op). "
                    + "REVIEWER는 모든 프레임, WORKER는 본인에게 배정된 프레임만 가능."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님 / 권한 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "버전/영상 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "작업이 잠긴 영상(신고와 무관한 락)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "412", description = "비식별 재처리 대기 중인 영상 — 작업락 유무와 무관하게 이 코드가 우선한다")
    })
    @PostMapping("/versions/{version}/rollback")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<VersionResponse.Item> rollback(@Parameter(description = "롤백 대상 버전 해시", required = true) @PathVariable("version") String versionHash,
                                                       @Valid @RequestBody RollbackRequest req,
                                                       @AuthenticationPrincipal TokenClaims actor) {
        LsLabelVersion version = versionService.rollback(versionHash, req.srcSn(), actor);
        // 사번(registeredUserNo)은 그대로 두고 표시명만 덧붙인다 — 해석 실패 시 null(화면이 사번 폴백).
        return ApiResponse.ok(
                VersionResponse.Item.from(version, versionService.resolveActorName(version.getRegId())));
    }

    /**
     * R6 — 영상 단위 산출 버전 목록(「시작 버전 선택」 선택지).
     *
     * <p>프레임 단위 버전 목록({@code GET /v1/frames/{srcSn}/versions})과 <b>별개 리소스</b>다 —
     * 기존 경로·응답 스키마는 그대로이고 여기에 영상 축을 추가한다.
     *
     * @design D4
     * @req R6
     */
    @Operation(
            summary = "영상 단위 산출 버전 목록 (시작 버전 선택지)",
            description = "영상(rawSn)의 산출 버전 번호 목록을 내림차순으로 반환한다. 번호는 "
                    + "LS_DATASET_EXPORT.OUTPUT_VER_NO(=관제가 픽업하는 산출 폴더 v1·v2)와 같다. "
                    + "어느 회차에 모든 프레임 내용이 그대로였다면 스냅샷이 생기지 않아 그 번호는 목록에 "
                    + "나오지 않는다(직전 회차와 완전히 같은 상태라 선택지로서 의미가 없다). "
                    + "REVIEWER는 모든 영상, WORKER는 본인에게 배정된 영상만 가능."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음")
    })
    @GetMapping("/videos/{rawSn}/versions")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<List<VideoVersionItem>> listVideoVersions(
            @Parameter(description = "영상 PK (LS_DATA_RAW.RAW_SN)", required = true, example = "1")
            @PathVariable Long rawSn,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(startVersionService.listVideoVersions(rawSn, actor));
    }

    /**
     * API-195 — 「시작 버전 선택」에서 고른 산출 회차를 <b>영상 전체 범위로 불러온다</b>(읽기 전용).
     *
     * <p><b>서버에는 아무것도 쓰지 않는다.</b> 편집을 어느 상태에서 시작할지 정하기 위한 조회이며,
     * 불러오는 것만으로는 아무것도 확정되지 않는다. 저장하지 않은 채 화면을 떠나면 작업본이 그대로
     * 남는다. 확정은 영상 라벨 일괄 저장({@code PUT /v1/videos/{rawSn}/labels})이 맡는다.
     *
     * <p>구 {@code PUT /v1/videos/{rawSn}/start-version}(호출 즉시 서버 작업본 교체)은 <b>폐기</b>됐다 —
     * 즉시 적용 경로가 남으면 확정 게이트를 우회하는 두 번째 쓰기 경로가 되고, 사용자가 확인하기 전에
     * 라벨이 통째로 과거화되어 되돌릴 창이 없다.
     *
     * <p>범위가 프레임 하나가 아니라 영상 전체인 이유는, 일부 프레임만 다른 회차에서 가져오면 한 영상
     * 안에 서로 다른 시점의 프레임이 섞이기 때문이다.
     *
     * @design API-195
     * @design D4
     * @design D5
     * @req R6
     */
    @Operation(
            summary = "로드 버전 라벨 조회 (REVIEWER 전체 / WORKER 본인 배정)",
            description = "산출 버전 하나를 골라 그 시점의 라벨과 프레임 폐기 상태를 영상 전체 범위로 "
                    + "반환한다. 경로의 버전은 관제가 픽업하는 산출 폴더 번호와 같다. "
                    + "**서버에는 아무것도 쓰지 않는다** — 확정은 PUT /v1/videos/{rawSn}/labels 가 맡는다. "
                    + "그 회차에 내용이 바뀌지 않은 프레임은 스냅샷이 따로 없으므로, 프레임마다 그 회차 "
                    + "번호 이하에서 가장 나중 스냅샷을 골라 채운다. 요청 회차 이하 대응이 아예 없는 "
                    + "프레임은 없는 과거를 추측하지 않고 현재 작업본을 그대로 실어 보내며 resolved=false "
                    + "로 알린다. 응답의 frames[].lblVer 는 확정 저장에 되돌려 보낼 낙관적 동시성 토큰이다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "입력값 검증 실패 / 손상된 스냅샷 / 프레임 수 상한 초과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "본인 배정 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "영상 없음 / 해당 산출 버전의 스냅샷 없음"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "412", description = "비식별 재처리 대기 중인 영상")
    })
    @GetMapping("/videos/{rawSn}/versions/{version}/labels")
    @PreAuthorize("hasAnyRole('REVIEWER', 'WORKER')")
    public ApiResponse<VersionLabelsResponse> loadVersionLabels(
            @Parameter(description = "영상 PK (LS_DATA_RAW.RAW_SN)", required = true, example = "1")
            @PathVariable Long rawSn,
            @Parameter(description = "불러올 승인 버전 번호", required = true, example = "2")
            @PathVariable("version") Integer version,
            @AuthenticationPrincipal TokenClaims actor) {
        return ApiResponse.ok(startVersionService.loadVersionLabels(rawSn, version, actor));
    }
}
