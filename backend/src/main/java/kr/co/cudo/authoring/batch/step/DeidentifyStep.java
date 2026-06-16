package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.pipeline.BatchStep;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.service.KpstDeidentService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.client.DeidentifyClient;
import kr.co.cudo.authoring.common.client.dto.DeidentifyRequest;
import kr.co.cudo.authoring.common.client.dto.DeidentifyResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.label.service.DeidentReportService;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;

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
 * <p>
 * Phase 1 — local 전용 mock 비식별 모드:
 *  - {@code authoring.integration.deidentify.mock-mode=true}(application-local.yml 만) 일 때,
 *    외부 비식별 서버(localhost:9200) 없이 원본을 비식별 경로로 복사하여 단계를 통과시킨다.
 *  - 보안(HIGH-1): mock-mode=true 인데 local 프로파일이 아니면 부트 거부 — 운영 노출 차단.
 *  - 정합성(HIGH-2): 원본 부재 시 'Y' 위장 금지 — 'F' 마킹 + 실패(MARKING_READY 미전이).
 *  - 무결성(HIGH-3): 임시파일 복사 후 atomic move — 부분 복사 손상 방지, 재실행 멱등.
 */
@Slf4j
@Component
public class DeidentifyStep implements BatchStep {

    /** mock 복사 임시파일 접미사 — 완료 시 atomic move 로 정식 경로 전환. */
    private static final String MOCK_TMP_PREFIX = ".tmp_";
    private static final String MOCK_ERROR_CODE = "MOCK_SOURCE_MISSING";

    private final DeidentifyClient deidentifyClient;
    private final VideoRepository videoRepository;
    private final LsDeidentProcLogRepository procLogRepository;
    /** Phase 3 — 재비식별 성공 시 OPEN 신고를 RESOLVED 로 일괄 전이. */
    private final DeidentReportService deidentReportService;
    /** Phase 3 — 잠금 해제 알림. */
    private final NotificationService notificationService;
    private final WorkLockService workLockService;
    /**
     * UC018 — KPST 폴링 위탁 서비스. {@code kpst.deid.enabled=true} 일 때만 빈으로 존재(아니면 null).
     * 활성 시 본 Step 은 KPST 위탁(upload→project)만 수행하고 완료(다운로드→Y전이)는 폴링 잡이 담당한다.
     */
    private final KpstDeidentService kpstDeidentService;
    /** Phase 1 — mock-mode 프로파일 게이팅(HIGH-1) 검증용. */
    private final Environment environment;
    /**
     * 실패 기록을 run() 의 REQUIRES_NEW 롤백과 독립 커밋하기 위한 별도 빈(라이브 검증 결함 수정).
     * 자기호출(self-invocation) 이 아닌 별도 빈이어야 REQUIRES_NEW 가 실제 신규 트랜잭션을 연다.
     */
    private final BatchTransitionService batchTransitionService;

    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String deidPath;

    /** UC018 — KPST 폴링 경로 토글. true 면 위탁만, false(기본) 면 기존 동기 경로 유지. */
    @Value("${kpst.deid.enabled:false}")
    private boolean kpstEnabled;

    /**
     * Phase 1 — local 전용 mock 비식별 토글. true 면 외부 호출 대신 원본을 비식별 경로로 복사.
     * 기본 false(운영 안전). application-local.yml 에서만 true.
     */
    @Value("${authoring.integration.deidentify.mock-mode:false}")
    private boolean mockMode;

    private Path baseDeidentifiedPath;

    public DeidentifyStep(DeidentifyClient deidentifyClient,
                          VideoRepository videoRepository,
                          LsDeidentProcLogRepository procLogRepository,
                          DeidentReportService deidentReportService,
                          NotificationService notificationService,
                          WorkLockService workLockService,
                          @Autowired(required = false) KpstDeidentService kpstDeidentService,
                          Environment environment,
                          BatchTransitionService batchTransitionService) {
        this.deidentifyClient = deidentifyClient;
        this.videoRepository = videoRepository;
        this.procLogRepository = procLogRepository;
        this.deidentReportService = deidentReportService;
        this.notificationService = notificationService;
        this.workLockService = workLockService;
        this.kpstDeidentService = kpstDeidentService;
        this.environment = environment;
        this.batchTransitionService = batchTransitionService;
    }

    @PostConstruct
    void initBasePath() {
        this.baseDeidentifiedPath = Paths.get(deidPath).toAbsolutePath().normalize();
        // HIGH-1 (보안): mock-mode 는 순수 local 프로파일 전용. 양성 확인(positive assertion) 게이트 —
        //   "local 이 active 인가" 만 보는 음성 게이트는 active 에 비-local 이 섞이거나
        //   ENV=dev/stg/prd 인 운영 배포에서 우회될 수 있으므로, 아래 3개를 모두 만족해야만 통과한다.
        if (mockMode) {
            assertPureLocalForMock();
        }
    }

    /**
     * mock-mode 허용 조건(모두 충족 필수). 하나라도 위반 시 부트 거부(fail-closed).
     *  1. local 프로파일이 active.
     *  2. active 프로파일에 비-local 이 하나도 섞이지 않음.
     *  3. ENV 환경변수가 미설정이거나 "local" (dev/stg/prd 등 비-local 이면 거부).
     * <p>ENV 는 {@link Environment#getProperty(String)}(systemEnvironment PropertySource) 로 읽어
     *    Environment mock 으로 검증 가능하게 한다. 메시지에 PII/원본경로 없음.
     */
    private void assertPureLocalForMock() {
        boolean localActive = environment.acceptsProfiles(Profiles.of("local"));
        boolean hasNonLocalProfile = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(p -> !"local".equalsIgnoreCase(p));
        String env = environment.getProperty("ENV");
        boolean envIsNonLocal = env != null && !env.isBlank() && !"local".equalsIgnoreCase(env.trim());

        if (!localActive || hasNonLocalProfile || envIsNonLocal) {
            throw new IllegalStateException(
                    "deidentify mock-mode 는 순수 local 프로파일(ENV 미설정/local)에서만 허용됩니다. activeProfiles="
                            + Arrays.toString(environment.getActiveProfiles())
                            + ", ENV=" + (env == null ? "<unset>" : env));
        }
    }

    @Override
    public BatchStage stage() {
        return BatchStage.DEIDENTIFY;
    }

    /**
     * 균일 파이프라인 인터페이스 — 기존 typed {@link #run(LsDataRaw)} 에 위임 (Phase 2).
     * 선두 비식별 파이프라인({@code preMarkingPipeline}) 이 본 메서드로 단계를 실행한다.
     */
    @Override
    public void execute(BatchContext ctx) {
        run(ctx.getRaw());
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
        // Phase 1 — local 전용 mock 경로: 외부 비식별 서버 없이 원본을 비식별 경로로 복사한다.
        // kpst/외부 호출 분기보다 앞에 둔다(mock 활성 시 외부 미접촉).
        if (mockMode) {
            return runMock(raw);
        }
        // UC018 — KPST 폴링 경로: 위탁(upload→project)만 수행하고 완료(다운로드→Y전이)는 폴링 잡이 담당.
        // DE_IDNTF_YN 미전이(완료 대기), MARKING_READY 미전이. 토글 false(기본)면 기존 동기 경로 유지.
        if (kpstEnabled && kpstDeidentService != null) {
            kpstDeidentService.submit(raw);
            return null;
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
                // TODO(R1 v1.14): 자동(배치) 재비식별 경로의 일괄 RESOLVED 와 수동 resolve(resolveManually) 가
                //   동일 영상에 대해 경쟁할 수 있다. 둘 다 OPEN→RESOLVED 멱등 전이라 데이터 정합은 유지되나,
                //   장기적으로 재처리 경로를 수동 단일화(외부 솔루션) 로 정리할지 검토 필요.
                deidentReportService.resolveOpenReports(managed.getRawSn());
            }
            if (notificationService != null) {
                notificationService.notifyReviewersOnLockRelease(managed);
            }
            log.info("[Batch][Deid] succeeded rawSn={}", raw.getRawSn());
            return resp.resultPath();
        } catch (RuntimeException e) {
            // CWE-209: 외부 비식별 API 의 원문 메시지(e.getMessage())는 내부 구현/경로/스키마를 노출할 수
            //          있으므로 DB/로그에 저장하지 않는다. 고정 에러코드 + 예외 클래스명만 보존한다.
            // 라이브 검증 결함 수정: 실패 기록('F' + procLog FAIL)을 별도 빈의 REQUIRES_NEW 트랜잭션으로
            //   커밋한다. 본 run() 의 REQUIRES_NEW 는 throw 로 롤백되지만, 이 커밋은 살아남아 'F' 가 영속된다.
            batchTransitionService.recordDeidentFailure(
                    raw.getRawSn(), "EXTERNAL_API_ERROR", e.getClass().getSimpleName());
            log.error("[Batch][Deid] failed rawSn={} errType={}", raw.getRawSn(), e.getClass().getSimpleName());
            // 원문 메시지는 진단용으로 DEBUG 에서만(운영 비노출). 예외 객체 자체는 상위 핸들러가 처리.
            log.debug("[Batch][Deid] failure detail rawSn={}", raw.getRawSn(), e);
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "비식별 호출 실패", e);
        }
    }

    /**
     * Phase 1 — local 전용 mock 비식별. 외부 호출 없이 원본을 비식별 경로로 복사한다.
     * <p>
     * 동작:
     *  1. procLog REQUESTED 기록(기존과 동일).
     *  2. 원본(raw.getRawFilePathNm()) 존재 검증 — 부재 시 'F' 마킹 + 실패(HIGH-2, 성공 위장 금지).
     *  3. createDirectories → 임시파일 복사 → atomic move(HIGH-3, 부분 복사 손상 방지·멱등).
     *  4. procLog.succeed(target) + raw.markDeidentified("Y") — 외부 미접촉.
     *  5. 잠금 해제/신고 RESOLVED/알림은 기존 성공 경로와 동일.
     * MARKING_READY 전이는 호출자(AsyncDeidentifyRunner)가 run() 정상 반환 후 수행.
     */
    private String runMock(LsDataRaw raw) {
        LsDeidentProcLog procLog = procLogRepository.save(
                LsDeidentProcLog.request(raw.getRawSn(), null, raw.getRawFilePathNm(), "batch-mock"));

        Path source = sourcePathOf(raw);
        // HIGH-2: 원본 부재 시 빈 플레이스홀더로 'Y' 위장 절대 금지 — 'F' 마킹 + 실패 처리.
        // 라이브 검증 결함 수정: 실패 기록을 별도 빈의 REQUIRES_NEW 로 커밋(runMock 의 롤백과 독립).
        if (source == null || !Files.isRegularFile(source)) {
            batchTransitionService.recordDeidentFailure(
                    raw.getRawSn(), MOCK_ERROR_CODE, "source not found");
            log.warn("[Batch][Deid][mock] source missing rawSn={}", raw.getRawSn());
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "mock 비식별 원본이 존재하지 않습니다.");
        }

        Path target = resolveSafeTargetPath(raw.getRawSn());
        try {
            copyAtomically(source, target, raw.getRawSn());
        } catch (IOException e) {
            batchTransitionService.recordDeidentFailure(
                    raw.getRawSn(), MOCK_ERROR_CODE, e.getClass().getSimpleName());
            log.error("[Batch][Deid][mock] copy failed rawSn={} errType={}",
                    raw.getRawSn(), e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "mock 비식별 복사 실패", e);
        }

        LsDataRaw managed = videoRepository.findById(raw.getRawSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "raw not found"));
        managed.markDeidentified("Y");
        procLog.succeed(target.toString());

        if (workLockService.isRawLocked(managed.getRawSn())) {
            workLockService.releaseRaw(managed.getRawSn(), "batch", "DEIDENT_SUCCEEDED");
        }
        if (deidentReportService != null) {
            deidentReportService.resolveOpenReports(managed.getRawSn());
        }
        if (notificationService != null) {
            notificationService.notifyReviewersOnLockRelease(managed);
        }
        log.info("[Batch][Deid][mock] succeeded rawSn={}", raw.getRawSn());
        return target.toString();
    }

    /** raw 의 원본 영상 경로를 Path 로 변환(blank 면 null). */
    private Path sourcePathOf(LsDataRaw raw) {
        String src = raw.getRawFilePathNm();
        if (src == null || src.isBlank()) {
            return null;
        }
        return Paths.get(src);
    }

    /**
     * 원본 → target 복사. HIGH-3 — 임시파일에 먼저 복사 후 atomic move 로 정식 경로 전환.
     * 부분 복사 손상 방지 + 재실행 멱등(REPLACE_EXISTING).
     */
    private void copyAtomically(Path source, Path target, Long rawSn) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + MOCK_TMP_PREFIX + rawSn);
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            moveAtomically(tmp, target);
        } finally {
            // 실패 시 임시파일 잔존 정리(불완전 산출물 유입 차단).
            Files.deleteIfExists(tmp);
        }
    }

    /** 임시파일을 정식 경로로 원자적 이동(불가 환경은 replace 폴백). */
    private void moveAtomically(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
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
