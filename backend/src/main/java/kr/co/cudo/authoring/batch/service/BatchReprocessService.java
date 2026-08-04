package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.dto.BatchReprocessResponse;
import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 배치 재처리(FAILED 복구) 서비스 (B3).
 *
 * <p>배치 파이프라인이 최대 재시도 초과 등으로 FAILED 로 고착된 영상을 REVIEWER 가 수동으로 재기동한다.
 * 자동 재시도 큐({@link BatchRetryQueue})와 동일한 재실행 경로({@link BatchOrchestrator#process(Long)})를
 * 재사용한다.
 *
 * <h3>허용 조건 (fail-secure)</h3>
 * <ul>
 *   <li>영상 미존재 → {@link ErrorCode#NOT_FOUND} (404).</li>
 *   <li>배치 단계 상태(LS_DATA_RAW.DATA_STTS_CD) 또는 작업 상태(LS_RAW_DATA_STATUS.DATA_STTS_CD)가
 *       FAILED 일 때만 허용. 그 외(PENDING/PROCESSING/COMPLETED 등)이거나 <b>이미 다른 주체가 재처리를
 *       클레임</b>한 경우 → {@link ErrorCode#CONFLICT} (409)로 거부한다.</li>
 * </ul>
 *
 * <h3>동시성 (CWE-362, HIGH #1 수정)</h3>
 * <p>기존 구현은 상태 read(findById) → {@code retryQueue.clear} → {@code orchestrator.process} 사이에
 * 원자성이 없어, 자동 재시도 폴러 또는 동시 수동 요청과 경합 시 동일 rawSn 파이프라인이 이중 실행될 수
 * 있었다. 이를 막기 위해 <b>상태 판정과 전이를 단일 원자 클레임</b>({@link
 * BatchTransitionService#tryClaimReprocessFromFailed})으로 통합한다 — FAILED→PROCESSING 조건부 UPDATE 가
 * 정확히 1건만 성공하므로, 실패한 쪽은 409 로 거부되고 오직 클레임에 성공한 호출만 재기동을 진행한다.
 * 재시도 큐 리셋도 {@link BatchRetryQueue#clearIfIdle}(RETRYING 부기 보존)로 클레임 성공 후에만 수행한다.
 *
 * <h3>SKIPPED 보상 (DEV_FIX H10 — 상태 고착 제거)</h3>
 * <p>클레임은 <b>작업 상태를 보지 않고</b> {@code LS_DATA_RAW} FAILED→PROCESSING 만 선점한다. 그런데
 * 이어지는 {@link BatchOrchestrator#process(Long)} 는 작업 상태가 검수 소유(PENDING/IN_REVIEW/APPROVED/
 * REJECTED)면 {@link BatchStage#SKIPPED} 로 즉시 반환하고 실패/완료 전이를 타지 않는다. 따라서 클레임으로
 * 바꾼 PROCESSING 을 되돌릴 코드가 없어 <b>배치 단계가 영구 PROCESSING 으로 고착</b>됐다(재현: 배치
 * FAILED → 배정 → 검수 제출 → 재처리 = 200 "SKIPPED" + stage 고착 → 이후 영구 409).
 * 이제 SKIPPED 를 관측하면 {@link BatchTransitionService#releaseReprocessClaim} 으로 보상 롤백한 뒤
 * {@link ErrorCode#CONFLICT}(409) 로 거부하므로, 상태는 원래 FAILED 로 남고 재시도가 계속 가능하다.
 *
 * <p>보안: 호출 인가는 컨트롤러 {@code @PreAuthorize("hasRole('REVIEWER')")} 로 강제하며, 상태 판정은 JPA
 * 파라미터 바인딩 쿼리만 사용(SQL Injection 무관).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchReprocessService {

    private final VideoRepository videoRepository;
    private final BatchTransitionService transitionService;
    private final BatchOrchestrator orchestrator;
    private final BatchRetryQueue retryQueue;

    /**
     * FAILED 영상 배치 재처리 재기동. 상태 판정·전이는 단일 원자 클레임으로 수행한다(트랜잭션 경계 통합).
     *
     * @param rawSn 대상 영상 식별자
     * @return 재기동 결과 단계
     */
    public BatchReprocessResponse retry(Long rawSn) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 은 필수입니다.");
        }
        // 존재 검증(404) — 없는 영상은 재처리 대상이 아니다.
        if (!videoRepository.existsById(rawSn)) {
            throw new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다.");
        }

        // CWE-362 — FAILED→PROCESSING 조건부 원자 클레임(판정+전이 통합). 실패 시 이미 진행 중이거나
        // FAILED 가 아니므로 409 로 거부한다(이중 파이프라인 실행 차단).
        boolean claimed = transitionService.tryClaimReprocessFromFailed(rawSn);
        if (!claimed) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "배치가 실패(FAILED)한 영상만 재처리할 수 있으며, 이미 재처리가 진행 중일 수 있습니다.");
        }

        // 클레임 성공 후에만 유휴 대기 행 리셋 — 자동 폴러의 RETRYING 부기는 보존한다.
        retryQueue.clearIfIdle(rawSn);

        log.info("[BatchReprocess] manual retry claimed rawSn={}", rawSn);
        // B-ISSUE-01 — 위 클레임이 이미 LS_DATA_RAW 를 FAILED→PROCESSING 으로 선점했다. 일반 진입
        //   (process)을 쓰면 진입 가드의 원자 클레임이 <b>자기가 찍은 PROCESSING</b> 때문에 0행이 되어
        //   수동 재처리가 전부 SKIPPED→409 가 된다. 소유권을 인계하는 전용 진입을 쓴다.
        BatchStage stage = orchestrator.processWithHeldStageClaim(rawSn);

        // DEV_FIX H10/H2 — 진입 가드가 SKIPPED 를 반환하면 파이프라인은 한 건도 돌지 않았고
        //   markRawDataFailed/markRawDataCompleted 도 호출되지 않는다. 위 클레임으로 바꿔 놓은
        //   LS_DATA_RAW PROCESSING 을 여기서 <b>보상 롤백</b>하지 않으면 stage 가 영구 PROCESSING 으로
        //   고착되어 이후 모든 재처리가 409 가 된다(stage/work 어느 쪽도 FAILED 가 아님).
        // 또한 "요청은 200 인데 아무 일도 일어나지 않음"을 없애기 위해 409 로 명시 거부한다 — 운영자가
        //   응답만으로 원인을 알 수 있어야 한다(로그 없이는 관측 불가하던 결함).
        if (stage == BatchStage.SKIPPED) {
            transitionService.releaseReprocessClaim(rawSn);
            log.warn("[BatchReprocess] skipped — review-owned work status, claim compensated rawSn={}", rawSn);
            throw new CustomException(ErrorCode.CONFLICT,
                    "검수 진행/완료(또는 반려) 상태의 영상은 배치를 재처리할 수 없습니다.");
        }
        return new BatchReprocessResponse(rawSn, stage.name());
    }
}
