package kr.co.cudo.authoring.dataset.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.co.cudo.authoring.common.response.ApiResponse;
import kr.co.cudo.authoring.dataset.dto.ShootingEnvCorrectionRunResponse;
import kr.co.cudo.authoring.dataset.dto.ShootingEnvCorrectionTargetResponse;
import kr.co.cudo.authoring.dataset.service.DatasetVideoMetaBackfillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 촬영환경 <b>레거시 파생 동결값 정정 백필</b> 수동 트리거 (개발/검수 전용 — 운영(prd) 미노출).
 *
 * <p>기동 1회 실행기({@code DatasetVideoMetaBackfillRunner})는 {@code @Profile("!local")} 이라
 * <b>로컬 스택에서는 백필이 돌지 않는다</b>({@code docker-compose.local.yml} 이 {@code local} 프로파일로
 * 기동). 로컬 기동마다 APPROVED 영상의 export 가 재생성되는 노이즈를 피하려는 기존 정책이므로 그대로
 * 두고, 대신 로컬 검증 경로를 이 수동 트리거로 연다. 백필 로직 자체는
 * {@link DatasetVideoMetaBackfillService#correctDerivedShootingEnvironment()} 를 <b>그대로 호출</b>할 뿐이며
 * 판별식·정정 트랜잭션·통지 배선은 재구현하지 않는다.
 *
 * <h3>노출 게이팅 (기존 관례 답습 — {@code BatchDevTriggerController} 와 동일 3중 방어)</h3>
 * <ul>
 *   <li>{@code @Profile("!prd")} — 운영에서는 빈 자체가 등록되지 않는다(엔드포인트 부재).</li>
 *   <li>{@code SecurityConfig} 의 {@code /v1/dev/**} → {@code hasRole('REVIEWER')} URL 매처 +
 *       핸들러 {@code @PreAuthorize}(이중 방어, {@code DevAutolabelTestController} 선례). dev 토큰
 *       발급({@code /v1/dev/tokens}) 만 permitAll 이고 그 외 {@code /v1/dev/**} 는 인증·역할 필수다.</li>
 *   <li>{@code DevProfileGuard} — 배포 표식({@code ENV=stg|prd})에서 dev 프로파일 기동을 거부한다.
 *       프로파일을 낮춰 dev 편의 엔드포인트를 배포 환경에 여는 우회를 독립 축에서 막는다.</li>
 * </ul>
 *
 * <h3>API 형태</h3>
 * <p>같은 URL 에 쿼리 파라미터로 행위를 분기하지 않는다({@code ?dryRun=true} 금지) — 조회 대상과 실행
 * 결과를 <b>별도 sub-resource</b> 로 나눈다.
 * <ul>
 *   <li>{@code GET  .../shooting-env-correction-targets} — 대상 건수만(정정 미수행).</li>
 *   <li>{@code POST .../shooting-env-corrections} — 실제 정정. 1회 실행당
 *       {@code authoring.dataset-video-meta.env-correction.max-per-run} 상한을 그대로 타며, 상한을
 *       우회하는 전량 실행 옵션은 없다. 잔여 건수를 응답에 담아 재호출을 유도한다(정정된 영상은 판별식에서
 *       빠지므로 재호출은 자연 멱등 — 두 번째는 0건).</li>
 * </ul>
 * <p>동기 실행이라 응답이 상한만큼 매달린다. 대상이 많아 오래 걸리면 API 에 옵션을 추가하는 대신
 * 기존 상한({@code DATASET_ENV_CORRECTION_MAX_PER_RUN})을 낮춰 여러 번 호출한다.
 *
 * <p>동시성: 정정 1건은 rawSn advisory 락 아래 재동결되고 재동결은 해시 멱등이라, 이 API 를 동시에
 * 두 번 호출해도 기동 러너가 2노드에서 동시에 도는 기존 상황과 같은 성질이다(중복 재동결 없음).
 * 따라서 별도 실행 락을 새로 두지 않는다.
 *
 * <p>보안: 요청 파라미터·바디가 전혀 없어 입력 검증/SQL Injection/Mass Assignment 표면이 없고, 응답은
 * 건수뿐이라 PII·경로·식별자를 노출하지 않는다(CWE-359/209). 로그도 건수만 남긴다.
 */
@Slf4j
@Tag(name = "dev-dataset-video-meta-backfill",
        description = "[개발/검수 전용] 촬영환경 파생값 정정 백필 수동 트리거. ⚠ 운영(prd) 미노출 · REVIEWER 전용.")
@RestController
@RequestMapping("/v1/dev/dataset-video-meta")
@RequiredArgsConstructor
@Profile("!prd")
@SecurityRequirement(name = "bearerAuth")
public class DatasetVideoMetaBackfillDevController {

    private final DatasetVideoMetaBackfillService backfillService;

    @Operation(summary = "촬영환경 정정 대상 건수 조회 (dry-run)",
            description = "레거시 파생값(NGT/SUMMER)으로 동결된 스냅샷 건수를 <b>실행 없이</b> 반환한다. "
                    + "재동결·export 재생성·관제 통지는 일어나지 않는다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "대상 건수"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음 (REVIEWER 아님)")
    })
    @GetMapping("/shooting-env-correction-targets")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<ShootingEnvCorrectionTargetResponse> countCorrectionTargets() {
        long targetCount = backfillService.countDerivedShootingEnvCorrectionTargets();
        return ApiResponse.ok(new ShootingEnvCorrectionTargetResponse(targetCount));
    }

    @Operation(summary = "촬영환경 정정 백필 실행",
            description = "레거시 파생 동결값을 라이브 수동값(미입력이면 null) 기준으로 재동결한다. "
                    + "1회 실행당 max-per-run 상한이 적용되며, 잔여가 남으면 같은 API 를 재호출한다(멱등).")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "정정 결과 요약"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 실패"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "권한 없음 (REVIEWER 아님)")
    })
    @PostMapping("/shooting-env-corrections")
    @PreAuthorize("hasRole('REVIEWER')")
    public ApiResponse<ShootingEnvCorrectionRunResponse> correct() {
        int corrected = backfillService.correctDerivedShootingEnvironment();
        long remaining = backfillService.countDerivedShootingEnvCorrectionTargets();
        log.info("[Dataset] manual shooting-env correction corrected={} remaining={}", corrected, remaining);
        return ApiResponse.ok(ShootingEnvCorrectionRunResponse.of(corrected, remaining));
    }
}
