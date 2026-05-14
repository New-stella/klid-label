package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.retry.BatchRetryQueue;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Phase 3 — 비식별 누락 신고 워크플로우 서비스.
 *
 * <p>주요 책임:
 * <ul>
 *   <li>{@link #report} — WORKER/REVIEWER 가 라벨링 중 신고 → 영상 잠금 + 재비식별 큐 적재.</li>
 *   <li>{@link #resolveOpenReports} — DeidentifyStep 가 재비식별 성공 시 호출해서 OPEN 신고 일괄 RESOLVED.</li>
 * </ul>
 *
 * <p>보안:
 * <ul>
 *   <li><b>IDOR (CWE-639)</b>: LabelAccessGuard 위임. WORKER 는 본인 배정 영상만 신고 가능.</li>
 *   <li><b>Race Condition (CWE-362)</b>: 이미 LOCK 상태면 409 — 중복 신고로 인한 재처리 폭주 방지.</li>
 *   <li><b>Log Injection (CWE-117)</b>: reason 로그/알림 출력 시 sanitize (NotificationService 위임).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager")
public class DeidentReportService {

    private final LabelAccessGuard accessGuard;
    private final VideoRepository videoRepository;
    private final LsDeidentReportRepository reportRepository;
    private final BatchRetryQueue retryQueue;
    private final NotificationService notificationService;
    private final WorkLockService workLockService;

    /**
     * 비식별 누락 신고 등록.
     *
     * @return 생성된 신고 RPRT_SN
     */
    public Long report(Long srcSn, String reason, TokenClaims actor) {
        if (reason == null || reason.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "신고 사유는 필수입니다.");
        }

        // 1) 권한 검사 + srcSn 의 rawSn 획득 (LabelAccessGuard: WORKER 는 본인 배정 영상만 통과)
        LsDataSrc src = accessGuard.verifyAndGet(srcSn, actor);
        Long reporterNo = accessGuard.parseUserNo(actor.sub());

        // 2) 영상 로드
        LsDataRaw raw = videoRepository.findById(src.getRawSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));

        // 3) 이미 잠금 상태면 409 — 중복 신고 차단 (재처리 폭주 방지)
        if (workLockService.isRawLocked(raw.getRawSn())) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 비식별 재처리 중인 영상입니다.");
        }

        // 4) 신고 row 저장 — 사용자 신고 흐름: REPORT_STTS_CD='OPEN' + REPORTER_NO/REASON/REPORT_DT
        LsDeidentReport report = reportRepository.save(
                LsDeidentReport.createReport(raw.getRawSn(), reporterNo, reason));

        // 5) 영상 잠금 (LS_AUTH_WORK_LOCK INSERT) + 비식별 상태 'F' 마킹 + 재시도 큐 적재
        //    잠금 INSERT 가 unique 제약 위반으로 실패하면 RuntimeException → 트랜잭션 롤백.
        //    (DATA_RAW_SN, LOCK_TARGET_CD='RAW', LOCK_STTS_CD='LOCKED') 동시성 충돌 차단.
        workLockService.lockRawForRedeident(raw.getRawSn(), actor.sub());
        raw.markDeidentified("F");
        retryQueue.enqueueIfRetryable(raw.getRawSn());

        // 6) REVIEWER 알림 (현재는 로그)
        notificationService.notifyReviewersOnDeidentReport(raw, reporterNo, reason);

        log.info("[DeidentReport] created rprtSn={} rawSn={} reporterNo={}",
                report.getRprtSn(), raw.getRawSn(), reporterNo);
        return report.getRprtSn();
    }

    /**
     * 재비식별 성공 시 — 해당 영상의 OPEN 신고 일괄 RESOLVED.
     *
     * <p>DeidentifyStep 가 호출. 별도 트랜잭션에서 raw.releaseLock() 이 호출된 뒤 실행.
     */
    public int resolveOpenReports(Long rawSn) {
        if (rawSn == null) {
            return 0;
        }
        // REPORT_STTS_CD='OPEN' 사용자 신고 row 를 RESOLVED 로 전이.
        List<LsDeidentReport> opens = reportRepository.findAllByDataRawSnAndReportSttsCd(
                rawSn, LsDeidentReport.REPORT_OPEN);
        for (LsDeidentReport r : opens) {
            r.resolve();
        }
        workLockService.releaseRaw(rawSn, "system", "DEIDENT_SUCCEEDED");
        if (!opens.isEmpty()) {
            log.info("[DeidentReport] resolved rawSn={} count={}", rawSn, opens.size());
        }
        return opens.size();
    }
}
