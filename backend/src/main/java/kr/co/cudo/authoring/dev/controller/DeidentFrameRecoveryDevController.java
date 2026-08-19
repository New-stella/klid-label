package kr.co.cudo.authoring.dev.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.dev.dto.DeidentFrameRecoveryRequest;
import kr.co.cudo.authoring.dev.dto.DeidentFrameRecoveryResponse;
import kr.co.cudo.authoring.dev.service.DeidentFrameRecoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * [개발/검수 전용] <b>레거시 비식별 프레임 복구</b> 수동 트리거 — 운영(prd) 미노출.
 *
 * <p>프레임 추출기 결함 수정 <b>이전</b>에 적재돼 {@code LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM} 이
 * NULL 로 남은 영상은 프레임 이미지 API 가 404 를 돌려줘 라벨링·검수 화면이 빈 화면이 된다. 이 API 는
 * 그 행들을 되살린다(복구 절차·매핑 규칙은 {@link DeidentFrameRecoveryService} javadoc).
 *
 * <h3>노출 게이팅 (기존 dev 엔드포인트 관례 답습 — 3중 방어)</h3>
 * <ul>
 *   <li>{@code @Profile("!prd")} — 운영에서는 빈 자체가 등록되지 않아 엔드포인트가 <b>존재하지 않는다</b>.</li>
 *   <li>{@code SecurityConfig} 의 {@code /v1/dev/**} → {@code hasRole('REVIEWER')} URL 매처 +
 *       핸들러 {@code @PreAuthorize}(이중 방어). {@code /v1/dev/tokens} 만 permitAll 이고 그 외
 *       {@code /v1/dev/**} 는 인증·역할 필수다.</li>
 *   <li>{@code DevProfileGuard} — 배포 표식({@code ENV=stg|prd})에서 dev 프로파일 기동을 거부해,
 *       프로파일을 낮춰 dev 엔드포인트를 배포 환경에 여는 우회를 독립 축에서 막는다.</li>
 * </ul>
 *
 * <h3>API 형태 — 같은 URL 에 쿼리 파라미터로 행위를 분기하지 않는다</h3>
 * <p>{@code ?dryRun=true} 로 실행/미실행을 가르지 않고 <b>별도 sub-resource</b> 로 나눈다
 * ({@code DatasetVideoMetaBackfillDevController} 선례 · {@code api-design} 규칙).
 * <ul>
 *   <li>{@code GET  .../targets} — <b>dry-run</b>. 대상과 예상 결과만 반환하고 아무것도 바꾸지 않는다.</li>
 *   <li>{@code POST .../runs} — 실제 복구. 1회 {@code MAX_VIDEOS_PER_RUN} 상한을 타며 잔여 건수를
 *       응답에 담아 재호출을 유도한다(멱등이라 재호출이 안전).</li>
 * </ul>
 * <p>두 경로 모두 {@code rawSn} 을 주면 <b>단건</b>, 생략하면 <b>전체(상한만큼)</b> 다.
 *
 * <p>동기 실행이라 상한만큼 응답이 매달린다(프레임마다 ffmpeg 재추출). 오래 걸리면 단건 호출을
 * 반복한다.
 *
 * <h3>차단·거절</h3>
 * <ul>
 *   <li><b>비식별 누락 신고 구간</b>({@code DE_IDNTF_YN='F'}) 영상은 <b>두 경로 모두</b> 건너뛴다
 *       (200 + {@code SKIPPED/UNDER_DEIDENT_REPORT}). 마스킹 실패가 확인된 비식별본에서 프레임을 뽑아
 *       경로를 채우면 그 값이 관제 뷰로 나가 <b>없던 노출을 새로 만들기</b> 때문이다.
 *       전체 모드에서 예외로 끊지 않으므로 나머지 영상 처리는 계속된다.</li>
 *   <li><b>동시 실행</b>: 같은 영상에 복구가 진행 중이면 {@code POST .../runs} 는 <b>409</b>. dry-run 은
 *       읽기 전용이라 이 가드를 타지 않는다.</li>
 * </ul>
 *
 * <p>보안: 입력은 {@code rawSn}({@code @Min(1)}) 하나뿐이고, 응답은 식별자·건수·서버가 고른 사유
 * 코드만 담는다 — 경로·파일명·PII 를 싣지 않으며 요청값이 응답으로 반사되지 않는다(CWE-209/359/79).
 */
@Tag(name = "dev-deident-frame-recovery",
        description = "[개발/검수 전용] 레거시 비식별 프레임 복구. ⚠ 운영(prd) 미노출 · REVIEWER 전용.")
@RestController
@RequestMapping("/v1/dev/deident-frame-recovery")
@RequiredArgsConstructor
@Profile("!prd")
@Validated
@SecurityRequirement(name = "bearerAuth")
public class DeidentFrameRecoveryDevController {

    private final DeidentFrameRecoveryService recoveryService;

    @Operation(summary = "복구 대상 조회 (dry-run)",
            description = "비식별 프레임 경로가 비어 있는 영상과 <b>예상 결과</b>를 실행 없이 반환한다. "
                    + "VDO_FRM_NO 복원·프레임 재추출·DB 변경이 일어나지 않는다. "
                    + "실행과 <b>같은 판정</b>을 타므로 비식별 누락 신고 구간 영상은 여기서도 "
                    + "SKIPPED(UNDER_DEIDENT_REPORT) 로 걸러져 보인다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "대상 목록 + 예상 결과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "rawSn 형식 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음 (REVIEWER 아님)")
    })
    @GetMapping("/targets")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<DeidentFrameRecoveryResponse> targets(
            @Parameter(description = "단건 대상 영상 PK. 생략하면 전체 대상(상한만큼).")
            @RequestParam(required = false) @Min(value = 1, message = "rawSn 은 1 이상이어야 합니다.") Long rawSn) {
        return ApiResponse.ok(recoveryService.preview(rawSn));
    }

    @Operation(summary = "레거시 비식별 프레임 복구 실행",
            description = "VDO_FRM_NO 를 마킹에서 복원한 뒤(개수가 일치할 때만) 비식별 프레임을 재추출해 "
                    + "기존 LS_DATA_SRC 행에 붙인다. 라벨은 보존되며 도메인 이벤트(검수·통지·산출 재생성)는 "
                    + "발행하지 않는다. 멱등 — 재호출해도 결과가 같다. "
                    + "<b>비식별 누락 신고 구간 영상은 건너뛴다</b>(UNDER_DEIDENT_REPORT). "
                    + "같은 영상에 복구가 진행 중이면 409.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "영상별 복구 결과"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "rawSn 형식 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음 (REVIEWER 아님)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409",
                    description = "같은 영상에 복구가 이미 진행 중 (완료 후 재시도)")
    })
    @PostMapping("/runs")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<DeidentFrameRecoveryResponse> run(
            @RequestBody(required = false) @Valid DeidentFrameRecoveryRequest request) {
        Long rawSn = (request == null) ? null : request.rawSn();
        return ApiResponse.ok(recoveryService.recover(rawSn));
    }
}
