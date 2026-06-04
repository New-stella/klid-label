package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.version.service.VersionService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Phase 2 (R1 v1.14) — 비식별 누락 신고 워크플로우 서비스.
 *
 * <p>R1 v1.14 정합 변경:
 * <ul>
 *   <li>{@link #report} — 신고 시 <b>해당 영상(rawSn) 전체 프레임 라벨</b>(자동+수동 전부)을 복원 가능한
 *       이력(LS_LABEL_VERSION 스냅샷, SAVE_REASON='DEIDENT_REPORT', ACTIVE_YN='N')으로 기록 후 일괄 삭제한다.
 *       자동 재비식별 큐 적재는 제거되었고(외부 솔루션 수동 비식별화로 대체) 영상 잠금 + DE_IDNTF_YN='F' 는 유지.</li>
 *   <li>{@link #resolveManually} — 외부 솔루션 수동 비식별화 완료 후 OPEN→RESOLVED 전이 + 작업락 해제.</li>
 *   <li>{@link #resolveOpenReports} — DeidentifyStep(배치 자동 비식별) 성공 시 OPEN 신고 일괄 RESOLVED.
 *       (TODO: 수동 resolveManually 와 동시 호출 시 경쟁 가능 — 둘 다 멱등 처리되어 데이터 정합은 유지되나,
 *        장기적으로 자동 경로 일원화 검토.)</li>
 * </ul>
 *
 * <p>보안:
 * <ul>
 *   <li><b>IDOR (CWE-639)</b>: report 는 LabelAccessGuard(srcSn), resolveManually 는 verifyRawAccess(rawSn) 로
 *       WORKER 본인 배정 영상만 통과. REVIEWER 는 전체 허용.</li>
 *   <li><b>Race (CWE-362)</b>: 동시 신고 시 WorkLock UNIQUE 제약이 최후 방어 —
 *       {@link DataIntegrityViolationException} 을 409 CONFLICT 로 변환.</li>
 *   <li><b>Privacy (CWE-359)</b>: 신고 사유(reason) 본문은 로그에 출력하지 않는다.</li>
 *   <li><b>SQL Injection (CWE-89)</b>: 삭제/조회는 JPA 파라미터 바인딩 @Modifying 쿼리만 사용.</li>
 * </ul>
 *
 * <p>스냅샷 + 이력기록 + 삭제는 단일 트랜잭션 — 부분 실패 시 전체 롤백.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager")
public class DeidentReportService {

    private final LabelAccessGuard accessGuard;
    private final VideoRepository videoRepository;
    private final LsDeidentReportRepository reportRepository;
    private final NotificationService notificationService;
    private final WorkLockService workLockService;
    private final VersionService versionService;
    private final LsDataLblRepository labelRepository;
    private final LsDataLblAttrValRepository attrValRepository;
    private final LsDataLblAiInfoRepository aiInfoRepository;
    private final LsRawDataStatusRepository rawDataStatusRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 비식별 누락 신고 등록 (R1 v1.14).
     *
     * <p>흐름: 권한검사 → 영상로드 → 잠금 선점검 → 신고 OPEN 저장 → 영상 전체 라벨 스냅샷+삭제
     *        → 작업락 + DE_IDNTF_YN='F' → APPROVED 면 TASK_MODIFIED 통지 → REVIEWER 알림.
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
        Long rawSn = raw.getRawSn();

        // 3) 이미 잠금 상태면 409 — 중복 신고 차단
        if (workLockService.isRawLocked(rawSn)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 비식별 재처리 중인 영상입니다.");
        }

        // 4) 신고 row 저장 — REPORT_STTS_CD='OPEN'
        LsDeidentReport report = reportRepository.save(
                LsDeidentReport.createReport(rawSn, reporterNo, reason));

        // 5) 영상 전체 라벨 스냅샷(복원 가능 이력) 후 일괄 삭제. 라벨 0건이면 둘 다 스킵.
        boolean snapshotted = versionService.snapshotDeidentReport(rawSn, actor);
        if (snapshotted) {
            deleteAllVideoLabels(rawSn);
            // 검수 완료(APPROVED) 영상이면 라벨 삭제도 수정 통지 대상 — TASK_MODIFIED(LABEL_DELETED) 발행.
            if (isReviewApproved(rawSn)) {
                eventPublisher.publishEvent(new TaskModifiedEvent(
                        rawSn, src.getSrcSn(), ChangeType.LABEL_DELETED, reporterNo));
            }
        }

        // 6) 영상 잠금 + 비식별 상태 'F' 마킹. 동시 신고 unique 위반 → 409.
        //    (R1 v1.14: 자동 재비식별 큐 적재 제거 — 외부 솔루션 수동 비식별화로 대체)
        try {
            workLockService.lockRawForRedeident(rawSn, actor.sub());
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 비식별 재처리 중인 영상입니다.");
        }
        raw.markDeidentified("F");

        // 7) REVIEWER 알림
        notificationService.notifyReviewersOnDeidentReport(raw, reporterNo, reason);

        log.info("[DeidentReport] created rprtSn={} rawSn={} reporterNo={} labelsRemoved={}",
                report.getRprtSn(), rawSn, reporterNo, snapshotted);
        return report.getRprtSn();
    }

    /**
     * 외부 솔루션 수동 비식별화 완료 후 신고 해소 (R1 v1.14).
     *
     * <p>OPEN→RESOLVED 전이 + 작업락 해제를 동일 트랜잭션에서 처리(원자성).
     *
     * <ul>
     *   <li>미인증 → 401</li>
     *   <li>신고 없음 → 404</li>
     *   <li>WORKER 타인 영상 → 403 (verifyRawAccess), REVIEWER 전체 허용</li>
     *   <li>OPEN 아니면 → 409 (이미 RESOLVED/DISMISSED 재-resolve 차단)</li>
     * </ul>
     */
    public void resolveManually(Long rprtSn, TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        LsDeidentReport report = reportRepository.findById(rprtSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "신고를 찾을 수 없습니다."));

        // IDOR — WORKER 는 본인 배정 영상만, REVIEWER 는 전체 허용.
        accessGuard.verifyRawAccess(report.getRawSn(), actor);

        // OPEN 선제 검증 — 이미 RESOLVED/DISMISSED 면 409 (원자성: 전이+락해제는 동일 트랜잭션).
        if (!LsDeidentReport.REPORT_OPEN.equals(report.getReportSttsCd())) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 처리된 신고입니다.");
        }

        report.resolve();
        workLockService.releaseRaw(report.getRawSn(), actor.sub(), "MANUAL_DEIDENT_DONE");

        log.info("[DeidentReport] resolved-manually rprtSn={} rawSn={} actor={}",
                rprtSn, report.getRawSn(), actor.sub());
    }

    /**
     * 배치 자동 재비식별 성공 시 — 해당 영상의 OPEN 신고 일괄 RESOLVED (기존 DeidentifyStep 경로 유지).
     */
    public int resolveOpenReports(Long rawSn) {
        if (rawSn == null) {
            return 0;
        }
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

    // ---------- 내부 ----------

    /**
     * 영상 전체 라벨 삭제 — 고아 방지 순서: ATTR_VAL → AI_INFO → LBL (모두 bulk delete, 1건씩 금지).
     */
    private void deleteAllVideoLabels(Long rawSn) {
        List<LsDataLbl> labels = labelRepository.findAllByRawSn(rawSn);
        if (labels.isEmpty()) {
            return;
        }
        List<Long> lblSns = labels.stream().map(LsDataLbl::getLblSn).toList();
        attrValRepository.deleteByLblSnIn(lblSns);
        aiInfoRepository.deleteByDataLblSnIn(lblSns);
        labelRepository.deleteAllByRawSn(rawSn);
    }

    /**
     * 영상(rawSn) 의 검수 상태가 APPROVED(검수 완료) 인지 판정. 상태 row 가 없으면 미검수로 간주.
     */
    private boolean isReviewApproved(Long rawSn) {
        return rawDataStatusRepository.findByRawDataIdIn(List.of(rawSn)).stream()
                .findFirst()
                .map(s -> LsRawDataStatus.STTS_APPROVED.equals(s.getDataSttsCd()))
                .orElse(false);
    }
}
