package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.label.service.DeidentReportService;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * KPST 폴링 상태 전이 전용 트랜잭션 서비스 (Phase 3 / UC018).
 *
 * <p>{@link KpstDeidentService} 의 폴링 오케스트레이션은 비트랜잭션(다운로드 I/O 포함)이며 self-invocation
 * 으로는 {@code @Transactional} 프록시가 적용되지 않는다(BatchTransitionService 와 동일 제약). 따라서 DB
 * 상태 전이는 별도 빈의 {@code REQUIRES_NEW} public 메서드로 분리해 cross-bean 호출로 트랜잭션을 보장한다.
 * 각 메서드는 load → 비즈니스 메서드 → dirty checking 영속의 단순 위임만 수행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "kpst.deid", name = "enabled", havingValue = "true")
public class KpstDeidentTxService {

    private final VideoRepository videoRepository;
    private final LsDeidentProcLogRepository procLogRepository;
    private final DeidentReportService deidentReportService;
    private final NotificationService notificationService;
    private final WorkLockService workLockService;

    /** 다운로드 완료 — datasetId 보충 + DOWNLOADED/SUCCEEDED 전이. */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void finishDownload(Long procLogSn, Long datasetId, String deidFilePath) {
        procLogRepository.findById(procLogSn).ifPresent(p -> {
            p.recordDatasetId(datasetId);
            p.markDownloaded(deidFilePath);
        });
    }

    /**
     * 다운로드 완료 + 비식별 완료(Y 전이)를 단일 REQUIRES_NEW 트랜잭션으로 원자화 — DEV_FIX HIGH/MEDIUM(M-1).
     *
     * <p>기존 {@code finishDownload}(DOWNLOADED) → {@code completeDeidentification}(Y) 의 2분리 트랜잭션은
     * 사이 크래시 시 DOWNLOADED 이나 Y 미전이인 영구 stuck 행을 남겼다(재폴링 대상도 아님). 본 메서드는
     * procLog DOWNLOADED/SUCCEEDED 전이와 raw Y/MARKING_READY 전이를 한 트랜잭션에 묶어 stuck 창을 제거한다.
     * 비식별 파일 실재(존재 + >0바이트) 검증은 {@link #completeDeidentification} 가 수행하므로 불완전
     * 산출물은 Y 로 가지 않는다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void finishDownloadAndComplete(Long rawSn, Long procLogSn, Long datasetId, String deidFilePath) {
        verifyDeidFile(rawSn, deidFilePath);
        procLogRepository.findById(procLogSn).ifPresent(p -> {
            p.recordDatasetId(datasetId);
            p.markDownloaded(deidFilePath);
        });
        applyCompletion(rawSn, deidFilePath);
    }

    /**
     * 폴링 건 'F' 처리 — 불완전 산출물(0바이트 등) 다운로드 실패 시 POLL_STTS 종료값 전이 + raw 'F' 마킹.
     * 재폴링 대상에서 제외하며 Y 전이는 수행하지 않는다(DEV_FIX HIGH 방어).
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void failPolling(Long procLogSn, Long rawSn) {
        procLogRepository.findById(procLogSn)
                .ifPresent(p -> p.fail("DEIDENT_INCOMPLETE", "deid file invalid"));
        videoRepository.findById(rawSn).ifPresent(v -> v.markDeidentified("F"));
        log.warn("[KpstDeid] poll incomplete download rawSn={}", rawSn);
    }

    /** 진행중 — datasetId 보충 + 시도 증가. */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void recordPollingProgress(Long procLogSn, Long datasetId) {
        procLogRepository.findById(procLogSn).ifPresent(p -> {
            p.recordDatasetId(datasetId);
            p.markPolling();
        });
    }

    /**
     * 타임아웃 판정 — 시도 횟수 초과 또는 위탁 후 경과 시간 초과 시 'F' 마킹(무한 폴링 방지).
     * @return 타임아웃으로 처리되어 'F' 마킹되면 true.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean markTimeoutIfExpired(Long procLogSn, int maxAttempts, long timeoutMinutes) {
        LsDeidentProcLog procLog = procLogRepository.findById(procLogSn).orElse(null);
        if (procLog == null || LsDeidentProcLog.POLL_DOWNLOADED.equals(procLog.getPollSttsCd())) {
            return false;
        }
        boolean attemptExceeded = procLog.getPollAttemptCnt() != null
                && procLog.getPollAttemptCnt() >= maxAttempts;
        boolean elapsedExceeded = procLog.getReqDt() != null
                && procLog.getReqDt().plusMinutes(timeoutMinutes).isBefore(LocalDateTime.now());
        if (!attemptExceeded && !elapsedExceeded) {
            return false;
        }
        procLog.fail("DEIDENT_TIMEOUT", "polling timeout");
        videoRepository.findById(procLog.getDataRawSn()).ifPresent(v -> v.markDeidentified("F"));
        log.warn("[KpstDeid] poll timeout rawSn={} attempts={}",
                procLog.getDataRawSn(), procLog.getPollAttemptCnt());
        return true;
    }

    /**
     * 비식별 완료 처리(공유) — 콜백/폴링 양 경로가 호출한다.
     *
     * <p>DE_IDNTF_YN='Y' → MARKING_READY → 작업락 해제 → OPEN 신고 RESOLVED → REVIEWER 알림.
     *
     * <p>Y 전이 전 비식별 파일 실재(존재 + >0바이트)를 재검증한다(DEV_FIX HIGH 방어/M-2 — defense-in-depth).
     * 미존재/0바이트 등 불완전 산출물이면 raw 를 'F' 마킹하고 예외를 던져 Y 전이를 차단한다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void completeDeidentification(Long rawSn, String deidFilePath) {
        verifyDeidFile(rawSn, deidFilePath);
        applyCompletion(rawSn, deidFilePath);
    }

    /** Y/MARKING_READY/락해제/신고해소/알림 적용(파일 검증 통과 후). */
    private void applyCompletion(Long rawSn, String deidFilePath) {
        LsDataRaw managed = videoRepository.findById(rawSn).orElse(null);
        if (managed == null) {
            log.warn("[KpstDeid] raw not found rawSn={} (complete) — skip", rawSn);
            return;
        }
        managed.markDeidentified("Y");
        managed.markMarkingReady();
        if (workLockService.isRawLocked(rawSn)) {
            workLockService.releaseRaw(rawSn, "batch", "DEIDENT_SUCCEEDED");
        }
        if (deidentReportService != null) {
            deidentReportService.resolveOpenReports(rawSn);
        }
        if (notificationService != null) {
            notificationService.notifyReviewersOnLockRelease(managed);
        }
        log.info("[KpstDeid] completed rawSn={}", rawSn);
    }

    /**
     * 비식별 산출물 실재 검증(CWE-459/404 방어) — 입력 유효성 + 파일 존재 + >0바이트.
     * 위반 시 raw 를 'F' 마킹하고 예외를 던져 Y 전이를 막는다(불완전 비식별 차단).
     */
    private void verifyDeidFile(Long rawSn, String deidFilePath) {
        if (rawSn == null || deidFilePath == null || deidFilePath.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn/deidFilePath 는 필수입니다.");
        }
        boolean valid;
        try {
            Path file = Paths.get(deidFilePath);
            valid = Files.isRegularFile(file) && Files.size(file) > 0;
        } catch (IOException e) {
            valid = false;
        }
        if (!valid) {
            videoRepository.findById(rawSn).ifPresent(v -> v.markDeidentified("F"));
            log.warn("[KpstDeid] deid file invalid rawSn={} — mark F", rawSn);
            throw new CustomException(ErrorCode.INVALID_INPUT, "비식별 산출물이 유효하지 않습니다.");
        }
    }

    @Transactional(value = "controlTransactionManager", readOnly = true,
            propagation = Propagation.REQUIRES_NEW)
    public Optional<LsDeidentProcLog> refresh(Long procLogSn) {
        return procLogRepository.findById(procLogSn);
    }
}
