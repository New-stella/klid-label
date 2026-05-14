package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDataSrcHstry;
import kr.co.cudo.authoring.batch.repository.LsDataSrcHstryRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.DeidentifyClient;
import kr.co.cudo.authoring.common.client.dto.DeidentifyRequest;
import kr.co.cudo.authoring.common.client.dto.DeidentifyResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.label.service.DeidentReportService;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

/**
 * 비식별화 단계 (Phase 5 → Phase 2 무조건화).
 * <p>
 * Phase 2 정책 (V2):
 *  - 모든 영상에 대해 무조건 비식별 호출 (PRVC/PSDO/ANONY 구분 없음).
 *  - 외부 비식별 API 가 ANONY 영상도 동일 인터페이스로 처리한다는 전제.
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
public class DeidentifyStep {

    private final DeidentifyClient deidentifyClient;
    private final LsDataSrcRepository srcRepository;
    private final LsDataSrcHstryRepository hstryRepository;
    private final VideoRepository videoRepository;
    /** Phase 3 — 재비식별 성공 시 OPEN 신고를 RESOLVED 로 일괄 전이. null 허용(단위 테스트 호환). */
    private final DeidentReportService deidentReportService;
    /** Phase 3 — 잠금 해제 알림. null 허용. */
    private final NotificationService notificationService;

    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String deidPath;

    /** 기존 단위 테스트 호환 생성자 (Phase 3 신규 의존성 null). */
    public DeidentifyStep(DeidentifyClient deidentifyClient,
                          LsDataSrcRepository srcRepository,
                          LsDataSrcHstryRepository hstryRepository,
                          VideoRepository videoRepository) {
        this(deidentifyClient, srcRepository, hstryRepository, videoRepository, null, null);
    }

    @Autowired
    public DeidentifyStep(DeidentifyClient deidentifyClient,
                          LsDataSrcRepository srcRepository,
                          LsDataSrcHstryRepository hstryRepository,
                          VideoRepository videoRepository,
                          DeidentReportService deidentReportService,
                          NotificationService notificationService) {
        this.deidentifyClient = deidentifyClient;
        this.srcRepository = srcRepository;
        this.hstryRepository = hstryRepository;
        this.videoRepository = videoRepository;
        this.deidentReportService = deidentReportService;
        this.notificationService = notificationService;
    }

    private Path baseDeidentifiedPath;

    @PostConstruct
    void initBasePath() {
        this.baseDeidentifiedPath = Paths.get(deidPath).toAbsolutePath().normalize();
    }

    /**
     * 영상의 모든 프레임을 비식별 호출.
     * - REQUIRES_NEW 트랜잭션: 호출 실패 시에도 src 레코드는 유지.
     * - 외부 호출은 Resilience4j (DeidentifyClient 내부) + 60s timeout.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void run(LsDataRaw raw) {
        if (raw == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "raw 가 null 입니다.");
        }
        // Phase 2: needsDeidentify 분기 제거 — 모든 영상에 대해 무조건 비식별 호출.
        // needsDeidentify() 메서드 자체는 호환을 위해 LsDataRaw 에 보존.

        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(raw.getRawSn());
        if (frames.isEmpty()) {
            log.warn("[Batch][Deid] no frames found rawSn={}", raw.getRawSn());
            return;
        }

        try {
            for (LsDataSrc src : frames) {
                Path target = resolveSafeTargetPath(raw.getRawSn(), src.getFrameNo());
                DeidentifyResponse resp = deidentifyClient
                        .deidentify(new DeidentifyRequest(src.getFilePath(), target.toString()))
                        .block(Duration.ofSeconds(70));
                if (resp == null || resp.resultPath() == null) {
                    throw new IllegalStateException("비식별 응답이 비어있음 srcSn=" + src.getSrcSn());
                }
                // LOW-1: 외부 비식별 API 응답 경로도 baseDeidentifiedPath 하위인지 검증 (CWE-22).
                // 응답이 base 밖이면 신뢰할 수 없는 경로 → 저장 거부 + 재시도 큐로 전이.
                Path returned = Paths.get(resp.resultPath()).toAbsolutePath().normalize();
                if (!returned.startsWith(baseDeidentifiedPath)) {
                    throw new CustomException(ErrorCode.INVALID_INPUT,
                            "비식별 결과 경로가 허용된 저장 경로를 벗어납니다 srcSn=" + src.getSrcSn());
                }
                src.attachDeidPath(resp.resultPath());
                hstryRepository.save(LsDataSrcHstry.recordDeidAttached(src.getSrcSn()));
            }
            // 모든 프레임 성공
            LsDataRaw managed = videoRepository.findById(raw.getRawSn())
                    .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "raw not found"));
            managed.markDeidentified("Y");

            // Phase 3 — 재비식별로 진입한 경우(LOCKED_FOR_REDEIDENT) 잠금 해제 + OPEN 신고 RESOLVED 전이 + 알림
            if (managed.isLockedForRedeident()) {
                managed.releaseLock();
                if (deidentReportService != null) {
                    deidentReportService.resolveOpenReports(managed.getRawSn());
                }
                if (notificationService != null) {
                    notificationService.notifyReviewersOnLockRelease(managed);
                }
                log.info("[Batch][Deid] redeident-lock released rawSn={}", managed.getRawSn());
            }
            log.info("[Batch][Deid] succeeded rawSn={} frames={}", raw.getRawSn(), frames.size());
        } catch (RuntimeException e) {
            // 원본은 절대 삭제/덮어쓰기하지 않는다. DE_IDNTF_YN='F' 마킹만 + 예외 재던짐.
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
    private Path resolveSafeTargetPath(Long rawSn, Integer frameNo) {
        Path target = baseDeidentifiedPath
                .resolve("frames")
                .resolve(String.valueOf(rawSn))
                .resolve("frame-" + frameNo + ".jpg")
                .normalize();
        if (!target.startsWith(baseDeidentifiedPath)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "비식별 출력 경로가 허용된 저장 경로를 벗어납니다 rawSn=" + rawSn);
        }
        return target;
    }
}
