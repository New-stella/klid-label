package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.dto.BatchReprocessResponse;
import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.batch.runner.AsyncBatchReprocessRunner;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskRejectedException;
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
 *       FAILED 일 때 허용.</li>
 *   <li>그 외이거나 <b>이미 다른 주체가 재처리를 클레임</b>한 경우 → {@link ErrorCode#CONFLICT} (409).</li>
 * </ul>
 *
 * <h3>★이 엔드포인트는 <b>실패 영상 전용</b>이다 — 완주 영상은 받지 않는다 [@design API-167]</h3>
 * <p>한때 "건너뛰기를 해제한 완주 영상"도 여기서 받았으나 <b>철회했다</b>. 완주 영상을 받으면
 * 파이프라인 <b>전체</b>가 다시 순회하는데, 트랙 보간({@code TrackInterpolationStep})은 건너뛰는 조건이
 * 없어 <b>항상</b> 돌고 {@code lblSrcCd='INTERPOLATE'} 라벨을 사람이 고쳤는지 보지 않고 전량 삭제한 뒤
 * 재생성한다. 완주 영상은 곧 작업자가 라벨링 중이거나 끝낸 영상이라, 삭제 이력도 승인 스냅샷도 없는
 * <b>복구 지점 0</b> 의 데이터 파괴가 된다.
 *
 * <p>건너뛰기를 해제한 단계를 다시 수행하는 것은 <b>그 단계를 지목하는 별도 요청</b>
 * ({@code BatchStageRerunService} — {@code POST /v1/videos/{rawSn}/batch/stages/{stage}/rerun})이
 * 담당한다. 「문제가 생긴 곳부터 재시도한다」는 원칙에 맞춘 분리이며, 그쪽은 범위를 요청이 골라
 * 보간을 돌릴지 말지를 사용자가 결정한다.
 *
 * <p>따라서 여기에는 <b>승인 이력 게이트를 두지 않는다</b> — 실패(FAILED) 영상은 검수가 승인된 적이
 * 없고(승인은 배치 완주 이후의 워크플로우다), 게이트를 남겨 두면 기존 계약(실패 영상은 승인 이력과
 * 무관하게 재기동)을 도달 불가능한 조건으로 좁히기만 한다.
 *
 * <h3>동시성 (CWE-362, HIGH #1 수정)</h3>
 * <p>기존 구현은 상태 read(findById) → {@code retryQueue.clear} → {@code orchestrator.process} 사이에
 * 원자성이 없어, 자동 재시도 폴러 또는 동시 수동 요청과 경합 시 동일 rawSn 파이프라인이 이중 실행될 수
 * 있었다. 이를 막기 위해 <b>상태 판정과 전이를 단일 원자 클레임</b>({@link
 * BatchTransitionService#tryClaimReprocessFromFailed})으로 통합한다 — FAILED→PROCESSING 조건부 UPDATE 가
 * 정확히 1건만 성공하므로, 실패한 쪽은 409 로 거부되고 오직 클레임에 성공한 호출만 재기동을 진행한다.
 * 재시도 큐 리셋도 {@link BatchRetryQueue#clearIfIdle}(RETRYING 부기 보존)로 클레임 성공 후에만 수행한다.
 *
 * <h3>★요청 안에서 하는 일은 "선점"까지다 — 파이프라인은 비동기다 [@design API-167]</h3>
 * <p>재기동 1건은 프레임 추출(NAS I/O)·ai 추론(호출당 상한 60s)·외부 위탁을 포함해 <b>분 단위</b>로 걸린다.
 * 이를 요청 스레드에서 동기 실행하면 FE 타임아웃(30s)이 먼저 끊겨 <b>정상 동선에서 거의 항상 "실패"</b>가
 * 뜨는데 서버는 뒤에서 계속 돌고, 사용자는 같은 버튼을 다시 누른다. 일괄 100건이면 요청 스레드 1개가
 * 수십 분 점유되고 ai 서킷이 열려 무관한 기능까지 막힌다.
 * <p>따라서 <b>원자 클레임까지만</b> 요청 안에서 수행하고(그래야 409·부분 성공 판정이 즉시 확정된다)
 * 실행은 {@link AsyncBatchReprocessRunner}(전용 풀)로 넘긴다. 응답은 <b>접수 사실 + 접수 시점 단계</b>이며,
 * 진행 상황은 영상 상세 조회의 단계 표시로 확인한다.
 *
 * <h3>SKIPPED 보상 (DEV_FIX H10 — 상태 고착 제거)</h3>
 * <p>클레임은 <b>작업 상태를 보지 않고</b> {@code LS_DATA_RAW} FAILED→PROCESSING 만 선점한다. 그런데
 * 이어지는 {@link BatchOrchestrator} 는 작업 상태가 검수 소유(PENDING/IN_REVIEW/APPROVED/REJECTED)면
 * {@code SKIPPED} 로 즉시 반환하고 실패/완료 전이를 타지 않는다. 따라서 클레임으로 바꾼 PROCESSING 을
 * 되돌릴 코드가 없으면 <b>배치 단계가 영구 PROCESSING 으로 고착</b>된다(재현: 배치 FAILED → 배정 →
 * 검수 제출 → 재처리 = 200 "SKIPPED" + stage 고착 → 이후 영구 409). 이 보상은 두 겹으로 유지한다:
 * <ul>
 *   <li><b>사전 차단</b> — 클레임 <b>전에</b> {@link BatchTransitionService#isReviewOwnedWorkStatus} 로
 *       한 번 읽어 검수 소유면 {@link ErrorCode#CONFLICT}(409)로 거부한다. 실행이 비동기가 되어 SKIPPED 를
 *       요청이 볼 수 없게 됐으므로, 이게 없으면 "못 돌리는 영상"이 200(접수됨)으로 가려진다.</li>
 *   <li><b>사후 보상</b> — 그 읽기와 클레임 사이 경합으로 검수 소유가 된 건은 비동기 실행이 SKIPPED 를
 *       관측해 {@link BatchTransitionService#releaseReprocessClaim} 으로 되돌린다(권위는 여전히 진입 가드).</li>
 * </ul>
 *
 * <p>보안: 호출 인가는 컨트롤러 {@code @PreAuthorize("hasRole('REVIEWER')")} 로 강제하며, 상태 판정은 JPA
 * 파라미터 바인딩 쿼리만 사용(SQL Injection 무관).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchReprocessService {

    /** 검수 소유 작업 상태 거부 문구 — 사전 차단·사후 보상 어느 쪽에서 걸려도 같은 문구를 쓴다. */
    static final String REVIEW_OWNED_REASON = "검수 진행/완료(또는 반려) 상태의 영상은 배치를 재처리할 수 없습니다.";

    /** 접수 용량 초과(디스패치 거부) 문구 — 내부 큐·풀 구성을 드러내지 않는다(CWE-209). */
    static final String DISPATCH_REJECTED_REASON = "재기동 요청이 밀려 접수하지 못했습니다. 잠시 후 다시 시도해 주세요.";

    /** 클레임 실패(409) 문구 — 상태를 되비추지 않는다(CWE-209). */
    static final String NOT_CLAIMABLE_REASON =
            "배치가 실패한 영상만 재기동할 수 있으며, 이미 재기동이 진행 중일 수 있습니다.";

    /** 접수 거부로 선점을 되돌렸을 때 표식을 닫는 사유 문구 — 고정 상수(CWE-209/532). */
    static final String CLAIM_CLOSED_DISPATCH_REJECTED = "접수 거부로 선점 해제";

    private final VideoRepository videoRepository;
    private final BatchTransitionService transitionService;
    private final BatchStatusService batchStatusService;
    private final AsyncBatchReprocessRunner reprocessRunner;
    private final BatchRetryQueue retryQueue;

    /**
     * FAILED 영상 배치 재처리 <b>접수</b>. 상태 판정·전이는 단일 원자 클레임으로 수행하고(트랜잭션 경계
     * 통합), 파이프라인 실행은 비동기로 넘긴다.
     *
     * @param rawSn 대상 영상 식별자
     * @return 접수 결과 — 접수 시점 배치 단계(PROCESSING)
     */
    public BatchReprocessResponse retry(Long rawSn) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 은 필수입니다.");
        }
        // 존재 검증(404) — 없는 영상은 재처리 대상이 아니다.
        if (!videoRepository.existsById(rawSn)) {
            throw new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다.");
        }

        // 검수 소유 작업 상태 사전 차단 — 실행이 비동기라 진입 가드의 SKIPPED 를 요청이 볼 수 없다.
        // 클레임 전에 걸러야 "못 돌리는 영상"이 200(접수됨)으로 가려지지 않고, 되돌릴 클레임도 안 생긴다.
        if (transitionService.isReviewOwnedWorkStatus(rawSn)) {
            throw new CustomException(ErrorCode.CONFLICT, REVIEW_OWNED_REASON);
        }

        // CWE-362 — 조건부 원자 클레임(판정+전이 통합). FAILED→PROCESSING 단일 조건부 UPDATE 라
        //   동시 요청 중 정확히 1건만 통과한다. 완주(COMPLETED) 영상은 이 경로의 대상이 아니다 —
        //   단계 지목 재수행(BatchStageRerunService)이 담당한다(위 Javadoc).
        String claimOrigin = LsDataRaw.DATA_STTS_FAILED;
        if (!transitionService.tryClaimReprocessFromFailed(rawSn)) {
            throw new CustomException(ErrorCode.CONFLICT, NOT_CLAIMABLE_REASON);
        }

        // ★ 선점 출발 상태를 DB 에 남긴다 — 노드가 죽으면 이 값이 유일한 복구 근거다.
        //   지금까지 이 값은 아래 runAsync 인자(호출 스레드)로만 존재해, 큐 대기 중 재기동·재배포가
        //   나면 선점 표시만 DB 에 남고 "어디로 되돌릴지" 는 함께 사라졌다. 회수 스윕
        //   (ProcessingStaleReclaimSweeper)이 이 표식을 읽는다. 디스패치 <b>전에</b> 남겨야 한다 —
        //   뒤에 남기면 이미 실행이 끝나 닫힘 행이 먼저 적재될 수 있어 열림/닫힘 순서가 뒤집힌다.
        batchStatusService.recordReprocessClaimOpened(rawSn, claimOrigin);

        // 클레임 성공 후에만 유휴 대기 행 리셋 — 자동 폴러의 RETRYING 부기는 보존한다.
        retryQueue.clearIfIdle(rawSn);

        log.info("[BatchReprocess] manual retry claimed rawSn={} origin={}", rawSn, claimOrigin);
        // 실행은 비동기로 넘긴다. 소유권 인계 진입(processWithHeldStageClaim)을 쓰는 것도, SKIPPED 보상
        //   롤백도 러너가 이어받는다(B-ISSUE-01 / DEV_FIX H10 계약은 그대로 — 실행 위치만 바뀌었다).
        // ★ 디스패치 거부는 접수 실패다 — 조용히 삼키면 사용자는 접수됐다고 믿는데 아무것도 돌지 않고,
        //   선점한 PROCESSING 이 되돌려지지 않아 그 영상은 이후 영구 409 가 된다(CWE-770 방어의 대가를
        //   상태 고착으로 치르는 셈). 클레임을 되돌리고 503 으로 알린다.
        try {
            reprocessRunner.runAsync(rawSn, claimOrigin);
        } catch (TaskRejectedException e) {
            // 상태(PROCESSING)만 <b>선점 직전 상태로</b> 되돌린다. 위 clearIfIdle 로 지워진 자동 재시도
            //   대기 행은 되살리지 않는다 — 되살리려면 지운 값을 기억해야 하고, 영상은 원래 상태로
            //   돌아가 있어 사용자가 다시 누를 수 있다(재기동이 자동 재시도보다 빠른 복구 경로다).
            transitionService.releaseReprocessClaim(rawSn, claimOrigin);
            // ★ 선점 표식도 닫는다 — 열어 둔 채로 두면 뒤에 <b>다른 경로</b>로 고착된 같은 영상을
            //   회수 스윕이 이 옛 표식의 출발 상태로 되돌린다(완주 영상이 FAILED 로 강등될 수 있다).
            closeClaimMarkerQuietly(rawSn);
            log.warn("[BatchReprocess] dispatch rejected — claim compensated rawSn={}", rawSn);
            throw new CustomException(ErrorCode.SERVICE_UNAVAILABLE, DISPATCH_REJECTED_REASON);
        }
        // 접수 시점 단계 — 위 클레임이 배치 단계를 PROCESSING 으로 선점했다. 파이프라인의 최종 결과가
        //   아니며(그건 영상 상세의 단계 표시로 확인한다) 여기서 결과를 기다리지 않는다.
        return new BatchReprocessResponse(rawSn, LsDataRaw.DATA_STTS_PROCESSING);
    }

    /**
     * 선점 표식 닫기 시도 — <b>실패해도 응답 코드를 뒤집지 않는다</b>.
     *
     * <p>이 호출은 디스패치 거부 보상 안에 있다. 여기서 예외가 올라가면 의도한 <b>503</b> 대신 그 예외가
     * 나가 사용자는 "접수 실패(재시도하면 된다)" 대신 알 수 없는 오류를 본다 — 상태는 이미 되돌아갔는데도.
     * 러너({@code AsyncBatchReprocessRunner#closeClaimMarkerQuietly})가 같은 관례를 쓰며, 그쪽만 감싸고
     * 여기를 비워 두면 같은 기록 실패가 경로에 따라 다르게 드러난다.
     *
     * <p>대신 <b>기록 실패 사실은 반드시 남긴다</b> — 표식이 열린 채 남으면 회수 스윕의 판정 입력이
     * 오염되므로 운영자가 알아야 한다(에피소드 결속 판정이 그 오염을 한 번 더 거르지만, 그것에 기대어
     * 침묵하지 않는다).
     */
    private void closeClaimMarkerQuietly(Long rawSn) {
        try {
            batchStatusService.recordReprocessClaimClosed(rawSn, CLAIM_CLOSED_DISPATCH_REJECTED);
        } catch (RuntimeException e) {
            log.error("[BatchReprocess] claim marker close failed rawSn={} reason={}",
                    rawSn, e.getClass().getSimpleName());
        }
    }
}
