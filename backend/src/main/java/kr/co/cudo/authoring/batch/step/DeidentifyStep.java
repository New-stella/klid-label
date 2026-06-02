package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.client.DeidentifyClient;
import kr.co.cudo.authoring.common.client.dto.DeidentifyRequest;
import kr.co.cudo.authoring.common.client.dto.DeidentifyResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.label.service.DeidentReportService;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * 비식별화 단계.
 * <p>
 * Phase 2 정책 (V2):
 *  - 영상 단위로 비식별 호출.
 *  - 외부 비식별 API 입력은 원본 영상, 출력은 비식별 영상.
 *  - 결과 영상 경로는 LS_DEIDENT_REPORT.DE_IDNTF_FILE_PATH_NM 에만 저장.
 *  - 원본 filePath 는 절대 변경되지 않는다 — 원본 보존 원칙.
 * <p>
 * 실패 처리:
 *  - 비식별 API 실패 (5xx, 타임아웃) → DE_IDNTF_YN='F' 마킹 + 원본 보존.
 * <p>
 * 보안:
 *  - SSRF (CWE-918): DeidentifyClient 가 application.yml 의 base-url 사용. 사용자 입력으로 URL 구성 금지.
 *  - Path Manipulation (CWE-22): 출력 경로는 storage.deidentified-path 기반.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeidentifyStep {

    private final DeidentifyClient deidentifyClient;
    private final VideoRepository videoRepository;
    private final LsDeidentProcLogRepository procLogRepository;
    /** Phase 3 — 재비식별 성공 시 OPEN 신고를 RESOLVED 로 일괄 전이. */
    private final DeidentReportService deidentReportService;
    /** Phase 3 — 잠금 해제 알림. */
    private final NotificationService notificationService;
    private final WorkLockService workLockService;

    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String deidPath;

    private Path baseDeidentifiedPath;

    @PostConstruct
    void initBasePath() {
        this.baseDeidentifiedPath = Paths.get(deidPath).toAbsolutePath().normalize();
    }

    /**
     * 영상을 비식별 호출한다.
     * - REQUIRES_NEW 트랜잭션: 호출 실패 시에도 src 레코드는 유지.
     * - 외부 호출은 Resilience4j (DeidentifyClient 내부) + 60s timeout.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public String run(LsDataRaw raw) {
        if (raw == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "raw 가 null 입니다.");
        }
        LsDeidentProcLog procLog = procLogRepository.save(
                LsDeidentProcLog.request(raw.getRawSn(), null, raw.getRawFilePathNm(), "batch"));

        try {
            Path target = resolveSafeTargetPath(raw.getRawSn());
            DeidentifyResponse resp = deidentifyClient
                    .deidentify(new DeidentifyRequest(raw.getRawFilePathNm(), target.toString()))
                    .block(Duration.ofSeconds(70));
            if (resp == null || resp.resultPath() == null) {
                throw new IllegalStateException("비식별 응답이 비어있음 rawSn=" + raw.getRawSn());
            }
            Path returned = Paths.get(resp.resultPath()).toAbsolutePath().normalize();
            if (!returned.startsWith(baseDeidentifiedPath)) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "비식별 결과 경로가 허용된 저장 경로를 벗어납니다 rawSn=" + raw.getRawSn());
            }
            LsDataRaw managed = videoRepository.findById(raw.getRawSn())
                    .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "raw not found"));
            managed.markDeidentified("Y");
            procLog.succeed(resp.resultPath());

            if (workLockService.isRawLocked(managed.getRawSn())) {
                workLockService.releaseRaw(managed.getRawSn(), "batch", "DEIDENT_SUCCEEDED");
            }
            if (deidentReportService != null) {
                deidentReportService.resolveOpenReports(managed.getRawSn());
            }
            if (notificationService != null) {
                notificationService.notifyReviewersOnLockRelease(managed);
            }
            log.info("[Batch][Deid] succeeded rawSn={}", raw.getRawSn());
            return resp.resultPath();
        } catch (RuntimeException e) {
            procLog.fail(e.getClass().getSimpleName(), e.getMessage());
            videoRepository.findById(raw.getRawSn())
                    .ifPresent(v -> v.markDeidentified("F"));
            log.error("[Batch][Deid] failed rawSn={} err={}", raw.getRawSn(), e.getMessage());
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "비식별 호출 실패", e);
        }
    }

    /**
     * baseDeidentifiedPath 하위에 안전한 target 경로를 구성 (CWE-22 방어).
     * - normalize 후 base 밖으로 빠지면 거부.
     */
    private Path resolveSafeTargetPath(Long rawSn) {
        Path target = baseDeidentifiedPath
                .resolve("videos")
                .resolve(String.valueOf(rawSn))
                .resolve("deidentified.mp4")
                .normalize();
        if (!target.startsWith(baseDeidentifiedPath)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "비식별 출력 경로가 허용된 저장 경로를 벗어납니다 rawSn=" + rawSn);
        }
        return target;
    }
}
