package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.step.DeidentFrameAttacher;
import kr.co.cudo.authoring.common.cache.StreamMetaCacheEvictor;
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
    private final DeidentFrameAttacher deidentFrameAttacher;
    private final StreamMetaCacheEvictor streamMetaCacheEvictor;

    /**
     * 다운로드 완료 + 비식별 완료(Y 전이)를 단일 REQUIRES_NEW 트랜잭션으로 원자화 — DEV_FIX HIGH/MEDIUM(M-1).
     *
     * <p>기존 2분리 트랜잭션(DOWNLOADED → Y) 은
     * 사이 크래시 시 DOWNLOADED 이나 Y 미전이인 영구 stuck 행을 남겼다(재폴링 대상도 아님). 본 메서드는
     * procLog DOWNLOADED/SUCCEEDED 전이와 raw Y/MARKING_READY 전이를 한 트랜잭션에 묶어 stuck 창을 제거한다.
     * 비식별 파일 실재(존재 + >0바이트) 검증은 {@link #completeDeidentification} 가 수행하므로 불완전
     * 산출물은 Y 로 가지 않는다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void finishDownloadAndComplete(Long rawSn, Long procLogSn, Long datasetId, String deidFilePath) {
        verifyDeidFile(rawSn, deidFilePath);
        LsDeidentProcLog procLog = procLogRepository.findById(procLogSn).orElse(null);
        boolean redeident = procLog != null && procLog.isRedeident();
        if (procLog != null) {
            procLog.recordDatasetId(datasetId);
            procLog.markDownloaded(deidFilePath);
        }
        // REQ_KIND 분기 — REDEIDENT 는 검수완료(APPROVED) 유지 + 프레임 attach, 기존 BATCH 는 현행 유지.
        applyCompletion(rawSn, deidFilePath, redeident);
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

    /**
     * REDEIDENT 완료 후처리 실패 종결 — DEV_FIX HIGH(결함1·결함2·M-1).
     *
     * <p>완료 감지 후 프레임 attach/검증 실패(해상도 불일치 등)는 KPST 비식별 자체는 성공했고 우리측
     * 후처리만 실패한 경우다. 재다운로드·재attach 를 반복해도 같은 입력이라 무의미하므로 <b>즉시 terminal
     * 종결</b>한다. 본 메서드는 메인 완료 트랜잭션({@link #finishDownloadAndComplete})이 롤백된 뒤 별도
     * {@code REQUIRES_NEW} 로 cross-bean 호출되어, 아래를 실제 커밋한다(롤백 분리).
     *
     * <ul>
     *   <li>procLog → FAILED + POLL_FAILED(terminal) — {@code findByPollSttsCdIn([WAITING,POLLING])}
     *       에서 제외되어 무한 재폴링이 멈춘다(결함2).</li>
     *   <li>de_ident_yn → 'F' — Y 미전이(라벨/검수상태는 attach 가 롤백되어 불변, APPROVED 유지).</li>
     *   <li>작업락 해제(있을 때만) — 재요청이 가능해진다(결함1, fail-closed→재처리 허용).</li>
     * </ul>
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void failRedeidentCompletion(Long procLogSn, Long rawSn, String errType) {
        procLogRepository.findById(procLogSn)
                .ifPresent(p -> p.fail("REDEIDENT_ATTACH_FAILED", errType));
        videoRepository.findById(rawSn).ifPresent(v -> v.markDeidentified("F"));
        if (workLockService.isRawLocked(rawSn)) {
            workLockService.releaseRaw(rawSn, "batch", "REDEIDENT_FAILED");
        }
        log.warn("[KpstDeid] redeident completion failed rawSn={} errType={} — terminal FAILED, lock released",
                rawSn, errType);
    }

    /**
     * raw 'F' 마킹만 별도 커밋 — M-1 보정. {@link #completeDeidentification} 내부 {@code verifyDeidFile}
     * 의 F-마킹은 예외 전파 시 같은 REQUIRES_NEW 트랜잭션이 롤백되어 취소된다. 비-REDEIDENT 경로의 파일
     * 무효 실패에서 F-마킹이 실제 커밋되도록 본 메서드(별도 REQUIRES_NEW)로 보정한다(라벨/상태 불변).
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markRawDeidentFailed(Long rawSn) {
        videoRepository.findById(rawSn).ifPresent(v -> v.markDeidentified("F"));
        log.warn("[KpstDeid] mark raw deident F rawSn={}", rawSn);
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
        Long rawSn = procLog.getDataRawSn();
        procLog.fail("DEIDENT_TIMEOUT", "polling timeout");
        videoRepository.findById(rawSn).ifPresent(v -> v.markDeidentified("F"));
        // DEV_FIX HIGH-2: REDEIDENT 건이 끝내 procState=2 미도달(타임아웃)이면 위탁 시 잡은 작업락이 영구 잔존
        // 한다(완료/실패 분기를 안 타므로). 타임아웃 'F' 마킹 시 락 보유면 해제하여 재요청을 허용한다.
        // 비-REDEIDENT(배치)는 락 자체가 없어 무영향.
        if (procLog.isRedeident() && workLockService.isRawLocked(rawSn)) {
            workLockService.releaseRaw(rawSn, "batch", "REDEIDENT_TIMEOUT");
        }
        log.warn("[KpstDeid] poll timeout rawSn={} attempts={} redeident={}",
                rawSn, procLog.getPollAttemptCnt(), procLog.isRedeident());
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
        // 콜백 경로는 procLogSn 미상 — rawSn 기준 최신 procLog 의 REQ_KIND 로 분기.
        boolean redeident = procLogRepository.findAllByDataRawSnOrderByReqDtDesc(rawSn).stream()
                .findFirst()
                .map(LsDeidentProcLog::isRedeident)
                .orElse(false);
        applyCompletion(rawSn, deidFilePath, redeident);
    }

    /** REQ_KIND 분기 — REDEIDENT 면 검수완료 유지 경로, 아니면 기존 배치 완료 경로. */
    private void applyCompletion(Long rawSn, String deidFilePath, boolean redeident) {
        if (redeident) {
            applyRedeidentCompletion(rawSn, deidFilePath);
        } else {
            applyBatchCompletion(rawSn);
        }
    }

    /**
     * 기존 배치 비식별 완료 경로 — Y/MARKING_READY/락해제/신고해소/알림 적용(현행 무변경, 회귀 금지).
     */
    private void applyBatchCompletion(Long rawSn) {
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
     * 검수완료(APPROVED) 영상 재비식별 완료 경로 (Phase 3 / R1 강등 금지) — 비식별 프레임 attach +
     * DE_IDNTF_YN='Y' + PRVC 정정 + 락해제만 수행한다.
     *
     * <p><b>R1(APPROVED 강등 금지)</b>: {@code markMarkingReady()}/상태머신 전이/MarkingCompletedEvent/
     * BatchOrchestrator 를 절대 호출하지 않는다. LS_RAW_DATA_STATUS(검수 워크플로우 상태)는 건드리지 않아
     * APPROVED 가 유지된다. 마킹 단계로의 재진입을 만들지 않으므로 검수 완료 작업이 라벨링/마킹으로
     * 되돌아가지 않는다.
     *
     * <p>{@link DeidentFrameAttacher} 가 해상도 불일치/추출 실패 시 예외를 던지면 그대로 전파되어 본
     * REQUIRES_NEW 트랜잭션 전체가 롤백된다 → DE_IDNTF_YN 미변경, 라벨/검수상태(APPROVED) 불변.
     * 롤백된 예외는 폴링 오케스트레이터({@link KpstDeidentService#pollOne})가 받아
     * {@link #failRedeidentCompletion}(별도 REQUIRES_NEW)로 terminal 종결한다 — procLog FAILED + 락 해제로
     * 무한 재폴링/영구 잠금을 차단한다(DEV_FIX 결함1·결함2). 따라서 성공 시에만 여기서 락을 해제한다.
     */
    private void applyRedeidentCompletion(Long rawSn, String deidFilePath) {
        LsDataRaw managed = videoRepository.findById(rawSn).orElse(null);
        if (managed == null) {
            log.warn("[KpstDeid] raw not found rawSn={} (redeident) — skip", rawSn);
            return;
        }
        // 1) 비식별 프레임 attach — 재비식별(SC-009)은 refreshExisting=true 로 기존 비식별 프레임을 강제
        //    재추출해 새 비식별본으로 교체한다(개인정보 누락 프레임 교체). 초기 파이프라인이 이미 모든 프레임에
        //    deident 경로를 설정해두므로 멱등 skip 이면 갱신이 무력화된다. 해상도 불일치 시 예외 전파(전체 롤백).
        //    라벨 보존(같은 SRC 행 갱신).
        int attached = deidentFrameAttacher.attachDeidentFrames(managed, Paths.get(deidFilePath), true);
        // 1-1) 정합 신호 — 재비식별은 프레임이 있어야 정상이다. 0건이면 데이터 정합 확인이 필요한
        //      비정상 신호로 WARN(예외로 막지는 않는다 — frames 자체가 없는 영상도 있을 수 있음).
        if (attached == 0) {
            log.warn("[KpstDeid] redeident attached 0 frames rawSn={} — 재비식별 프레임 0건, 데이터 정합 확인 필요", rawSn);
        }
        // 2) 비식별 완료 마킹.
        managed.markDeidentified("Y");
        // 3) PRVC 정정 — UNKNOWN(미상)이면 PRVC 로 확정.
        managed.correctPrvcTypeIfUnknown();
        // 4) 작업락 해제(있을 때만). MARKING_READY/상태전이/알림은 호출하지 않는다(APPROVED 유지).
        if (workLockService.isRawLocked(rawSn)) {
            workLockService.releaseRaw(rawSn, "batch", "REDEIDENT_SUCCEEDED");
        }
        // 5) 스트림 메타 캐시 무효화 (HIGH — 무결성/privacy) — 재비식별로 비식별본이 교체(동일 경로
        //    in-place 교체 시 옛 contentLength 로 Range 경계 오류·재생 잘림 가능)되었으므로 커밋 후 무효화.
        streamMetaCacheEvictor.evictAfterCommit(rawSn);
        log.info("[KpstDeid] redeident completed rawSn={}", rawSn);
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
