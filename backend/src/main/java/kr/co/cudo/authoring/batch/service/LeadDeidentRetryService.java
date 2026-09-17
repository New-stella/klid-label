package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 선두 비식별 실패 영상의 배치 재시작 — <b>판정과 작업 잠금 선점</b>. [@design API-167] [@design API-199]
 *
 * <p>비식별 실패도 배치 실패로 본다. 기존 건별 재시작({@link BatchReprocessService#retry})이 이 빈에 먼저
 * 묻고, 대상 영상이 선두 비식별 실패 형상이면 여기서 판정·선점을 끝낸 뒤 적재 직후와 같은 선두 비식별
 * 실행기({@code AsyncDeidentifyRunner})로 넘긴다. 형상이 아니면 {@code false} 를 돌려 기존 재기동 경로가
 * 그대로 돈다. 일괄 재시작은 건별 재시작을 그대로 부르므로 이 판정을 따로 두지 않는다.
 *
 * <h3>형상 — 이 판정의 단일 지점</h3>
 * <p>영상 비식별 여부 {@code 'F'} <b>그리고</b> 배치 단계 {@code PENDING}(마킹 준비 미도달). 선두 비식별의
 * 모든 실패 기록은 배치 단계를 건드리지 않으므로 실패 후 값은 적재 기본값 {@code PENDING} 그대로다.
 *
 * <h3>형상 안의 거부 (이 순서)</h3>
 * <ol>
 *   <li>파생영상 → 400</li>
 *   <li>승인 <b>이력</b> → 409 (현재 상태가 아니라 이력으로 판정)</li>
 *   <li>열린 비식별 누락 신고 → 409 ({@code 'F'} 가 신고 표식인 경우 — 신고 해소 경로가 따로 있다)</li>
 *   <li>진행 중 외부 위탁(폴링 상태 WAITING·POLLING) → 409</li>
 *   <li>작업 잠금 선점 실패(이미 잠김 · 활성 락 유일 인덱스 위반) → 409</li>
 * </ol>
 * <p>판정은 읽고-확인이라 경합 창이 있다 — <b>최종 방어는 락 INSERT 의 유일 인덱스</b>다(여러 노드 동시
 * 요청 중 1건만 수락). 거부된 요청은 트랜잭션이 롤백되어 잠금이 남지 않는다.
 *
 * <h3>하지 않는 것</h3>
 * <ul>
 *   <li>배치 단계를 {@code PROCESSING} 으로 선점하지 않는다 — 선두 비식별은 원래 그 축을 건드리지 않는다.</li>
 *   <li>영상 비식별 여부를 {@code 'N'} 으로 되돌리지 않는다 — 진행 여부는 잠금·위탁 원장으로 본다.</li>
 *   <li>자동 재시도·스윕·스케줄을 두지 않는다 — 사람이 누를 때만 돈다.</li>
 * </ul>
 *
 * <p>잡은 잠금은 재수행의 성공과 모든 실패 종결에서 풀린다({@code AsyncDeidentifyRunner} ·
 * {@code KpstDeidentTxService}). 해제는 {@link WorkLockService#releaseDeidentRetryLock} 로 이 기능이 잡은
 * 잠금에만 한정된다.
 *
 * @design AC-1133
 * @design AC-1134
 * @design AC-1135
 * @design DFEAT-041
 * @design UC-011
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeadDeidentRetryService {

    static final String DERIVATIVE_REASON = "다른 영상에서 파생된 영상은 비식별을 다시 수행할 수 없습니다.";
    static final String APPROVED_HISTORY_REASON = "검수 승인 이력이 있는 영상은 비식별을 다시 수행할 수 없습니다.";
    static final String OPEN_REPORT_REASON =
            "비식별 누락 신고가 열려 있는 영상입니다. 외부 재비식별 후 신고 해소로 처리해 주세요.";
    static final String IN_FLIGHT_REASON = "비식별 처리가 이미 진행 중인 영상입니다.";
    static final String LOCKED_REASON = "이미 다시 시작했거나 다른 작업이 진행 중인 영상입니다. 잠시 후 다시 시도해 주세요.";

    /** 잠금 소유자·해제 주체 — 컬럼 폭(30자) 이내 고정값. */
    static final String LOCK_OWNER = "batch-deident-retry";
    static final String RELEASE_DISPATCH_REJECTED = "DEIDENT_RETRY_DISPATCH_REJECTED";

    /** 재폴링 대상 두 값 — 종결값(DOWNLOADED·FAILED)은 넣지 않는다. */
    private static final List<String> IN_FLIGHT_POLL_STATES =
            List.of(LsDeidentProcLog.POLL_WAITING, LsDeidentProcLog.POLL_POLLING);

    private final VideoRepository videoRepository;
    private final ReviewApprovalGate reviewApprovalGate;
    private final LsDeidentReportRepository deidentReportRepository;
    private final LsDeidentProcLogRepository procLogRepository;
    private final WorkLockService workLockService;

    /** 선두 비식별 실패 형상인가 — 영상 비식별 여부 'F' 이고 배치 단계가 마킹 준비 전(PENDING). */
    public static boolean isLeadDeidentFailure(LsDataRaw raw) {
        return raw != null
                && "F".equals(raw.getDeIdntfYn())
                && LsDataRaw.STATUS_PENDING.equals(raw.getDataSttsCd());
    }

    /**
     * 형상이면 거부 판정 후 작업 잠금을 선점한다(한 트랜잭션).
     *
     * @return 선점했으면 {@code true}(호출자가 커밋 뒤 실행기로 넘긴다), 형상이 아니면 {@code false}
     *         (호출자가 기존 재기동 경로로 진행)
     * @throws CustomException 형상 안의 거부 — 400(파생) / 409(그 밖의 사유별 문구)
     */
    @Transactional(value = "controlTransactionManager")
    public boolean tryClaim(Long rawSn) {
        LsDataRaw raw = videoRepository.findById(rawSn).orElse(null);
        if (!isLeadDeidentFailure(raw)) {
            return false;
        }
        if (raw.isDerivative()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, DERIVATIVE_REASON);
        }
        if (reviewApprovalGate.hasEverApproved(rawSn)) {
            throw new CustomException(ErrorCode.CONFLICT, APPROVED_HISTORY_REASON);
        }
        if (!deidentReportRepository
                .findAllByDataRawSnAndReportSttsCd(rawSn, LsDeidentReport.REPORT_OPEN).isEmpty()) {
            throw new CustomException(ErrorCode.CONFLICT, OPEN_REPORT_REASON);
        }
        if (procLogRepository.existsByDataRawSnAndPollSttsCdIn(rawSn, IN_FLIGHT_POLL_STATES)) {
            throw new CustomException(ErrorCode.CONFLICT, IN_FLIGHT_REASON);
        }
        try {
            workLockService.lockRawForDeidentRetry(rawSn, LOCK_OWNER, LOCKED_REASON);
        } catch (DataIntegrityViolationException e) {
            // 선제 검사를 동시에 통과한 다른 요청이 먼저 잡았다 — 유일 인덱스가 거부(CWE-362).
            throw new CustomException(ErrorCode.CONFLICT, LOCKED_REASON);
        }
        log.info("[BatchReprocess] deident retry claimed rawSn={}", rawSn);
        return true;
    }

    /** 접수 거부(디스패치 실패) 보상 — 이 기능이 잡은 잠금만 독립 커밋으로 푼다. */
    public void releaseClaim(Long rawSn) {
        workLockService.releaseDeidentRetryLockInNewTx(rawSn, LOCK_OWNER, RELEASE_DISPATCH_REJECTED);
    }
}
