package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.pipeline.BatchStep;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.service.KpstDeidentService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.label.service.DeidentReportService;
import kr.co.cudo.authoring.notification.NotificationService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
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
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * 비식별화 단계.
 * <p>
 * 경로 단일화 (UC018): 비식별 확정은 KPST 폴링으로 단일화되었다. 레거시 동기 SPI(DeidentifyClient)
 * 경로는 제거되었으며, 본 단계가 취할 수 있는 경로는 아래 두 가지뿐이다.
 *  - ① {@code mockMode}(local 전용): 외부 호출 없이 원본을 비식별 경로로 복사.
 *  - ② KPST 위탁({@code kpst.deid.enabled=true}): {@link KpstDeidentService#submit} 위탁만 수행하고
 *       완료(다운로드→Y전이)는 폴링 잡이 담당.
 * 둘 다 아닌 경우(mock 아님 + KPST 서비스 미주입)는 설정 오류로 간주하고 명확한 예외를 던진다
 * (레거시 폴백 없음, 내부 정보 미노출).
 * <p>
 * 공통 정책 (V2):
 *  - 영상 단위로 비식별. 출력은 비식별 영상이며 원본 filePath 는 절대 변경되지 않는다 — 원본 보존 원칙.
 *  - 결과 영상 경로는 LS_DEIDENT_REPORT.DE_IDNTF_FILE_PATH_NM 에만 저장.
 * <p>
 * 보안:
 *  - SSRF (CWE-918)/경로순회 (CWE-22): URL/CA 신뢰체인·다운로드 경로 검증은 KPST 클라이언트가 방어.
 *    mock 출력 경로는 storage.deidentified-path 기반으로 base 이탈을 차단한다.
 *  - <b>비식별 엔드포인트 신뢰 판정은 여기서 하지 않는다</b>: mock-mode 든 KPST 위탁이든
 *    "위조 비식별(원본이 비식별본으로 서빙됨)" 여부는
 *    {@link kr.co.cudo.authoring.common.security.DeidentifyEndpointTrustGuard} 단일 진입점이
 *    부팅 시점에 판정한다(비신뢰 → dev/stg WARN, prd 부팅 거부). 아래 mock-mode 프로파일
 *    allowlist 게이트는 mock 자체 복사 경로에 대한 별도(더 엄격한) 제약이다.
 * <p>
 * Phase 1 — mock 비식별 모드(local/dev/stg 허용, prd 차단):
 *  - {@code authoring.integration.deidentify.mock-mode=true} 일 때, 외부 비식별 서버(localhost:9200)
 *    없이 원본을 비식별 경로로 복사하여 단계를 통과시킨다. KPST 미준비 동안 dev/stg 파이프라인 가동용.
 *  - 보안(HIGH-1): mock-mode=true 인데 prd(운영) 프로파일/ENV 이면 부트 거부 — 운영 노출 차단(fail-closed).
 *    dev/stg 등 비-local 에서 활성 시 시작 WARN 1줄로 추적성 확보.
 *  - 정합성(HIGH-2): 원본 부재 시 'Y' 위장 금지 — 'F' 마킹 + 실패(MARKING_READY 미전이).
 *  - 무결성(HIGH-3): 임시파일 복사 후 atomic move — 부분 복사 손상 방지, 재실행 멱등.
 */
@Slf4j
@Component
public class DeidentifyStep implements BatchStep {

    /** mock 복사 임시파일 접미사 — 완료 시 atomic move 로 정식 경로 전환. */
    private static final String MOCK_TMP_PREFIX = ".tmp_";
    private static final String MOCK_ERROR_CODE = "MOCK_SOURCE_MISSING";
    /** mock-mode 허용 프로파일(소문자) — 이 집합으로 수렴할 때만 부팅(allowlist, fail-closed). */
    private static final Set<String> ALLOWED_MOCK_PROFILES = Set.of("local", "dev", "stg");

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
    /**
     * 자기참조 프록시 공급자 (DEV_FIX — self-invocation 트랜잭션 부재 수정).
     * <p>{@link #execute(BatchContext)} 가 {@link #run(LsDataRaw)} 을 <b>프록시 경유</b>로 호출해
     * {@code @Transactional(REQUIRES_NEW)} 가 실제 신규 트랜잭션을 열도록 한다. 직접 자기호출은
     * Spring AOP 프록시를 우회해 트랜잭션이 열리지 않아, 적재 경로({@code AsyncDeidentifyRunner.runAsync},
     * 무트랜잭션)에서 mock 영속(DE_IDNTF_YN='Y', procLog SUCCEEDED)이 커밋되지 않던 결함을 막는다.
     * <p>{@link ObjectProvider} 는 호출 시점에 지연 해석되므로 자기 빈 순환 의존이 생성 시점에 발생하지 않는다.
     * 단위 테스트에서 빈을 수동 생성(provider=null)하는 경우 {@code this} 로 폴백한다(프록시 없이 직접 호출 —
     * 리포지토리가 mock 이라 트랜잭션 불필요).
     */
    private final ObjectProvider<DeidentifyStep> selfProvider;

    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String deidPath;

    /**
     * UC018 — KPST 폴링 경로 토글(킬스위치). 기본 true(KPST 단일 경로).
     * false 로 내리면 KPST 위탁이 비활성화되고, mock 도 아니면 설정 오류로 거부된다(레거시 폴백 없음).
     */
    @Value("${kpst.deid.enabled:true}")
    private boolean kpstEnabled;

    /**
     * Phase 1 — mock 비식별 토글(local/dev/stg 허용, prd 차단). true 면 외부 호출 대신 원본을 비식별 경로로 복사.
     * 기본 false(운영 안전). {@code DEIDENTIFY_MOCK_MODE} 환경변수로 dev/stg 에서 토글 가능.
     */
    @Value("${authoring.integration.deidentify.mock-mode:false}")
    private boolean mockMode;

    private Path baseDeidentifiedPath;

    public DeidentifyStep(VideoRepository videoRepository,
                          LsDeidentProcLogRepository procLogRepository,
                          DeidentReportService deidentReportService,
                          NotificationService notificationService,
                          WorkLockService workLockService,
                          @Autowired(required = false) KpstDeidentService kpstDeidentService,
                          Environment environment,
                          BatchTransitionService batchTransitionService,
                          ObjectProvider<DeidentifyStep> selfProvider) {
        this.videoRepository = videoRepository;
        this.procLogRepository = procLogRepository;
        this.deidentReportService = deidentReportService;
        this.notificationService = notificationService;
        this.workLockService = workLockService;
        this.kpstDeidentService = kpstDeidentService;
        this.environment = environment;
        this.batchTransitionService = batchTransitionService;
        this.selfProvider = selfProvider;
    }

    @PostConstruct
    void initBasePath() {
        this.baseDeidentifiedPath = Paths.get(deidPath).toAbsolutePath().normalize();
        // HIGH-1 (보안): mock-mode 는 prd(운영) 만 차단하고 local/dev/stg 는 허용한다.
        //   KPST 미준비 동안 dev/stg 에서 mock 비식별로 파이프라인을 굴리기 위한 의도적 완화.
        //   prd 는 어떤 경로(프로파일/ENV/혼합)로도 mock 이 켜지지 않도록 fail-closed 로 차단한다.
        if (mockMode) {
            assertMockAllowedProfile();
        }
    }

    /**
     * mock-mode 허용 게이트 — <b>allowlist(fail-closed)</b>: 모든 신호가 허용 프로파일
     * {@link #ALLOWED_MOCK_PROFILES}(local/dev/stg)로 수렴할 때만 mock 부팅을 통과시킨다.
     * 그 외(미식별/비표준/prd)는 전부 거부한다 — allow-by-default(fail-open) 위험 차단.
     * <p>거부 조건(하나라도 해당 시 IllegalStateException):
     *  1. active 프로파일이 비어 있음(미설정 → 모호 → 거부).
     *  2. active 프로파일에 ALLOWED 가 아닌 값이 하나라도 섞임(prd/production/prod/임의 라벨, 혼합 포함 → 거부).
     *  3. ENV 환경변수가 설정돼 있고(trim, non-blank) ALLOWED 에 없음(prd/production/prod/임의 → 거부).
     *     ENV 미설정/blank 는 허용(프로파일만으로 판정).
     * <p>비-local(dev/stg) 에서 활성 시 추적용 WARN 1줄을 남긴다(원본 영상이 비식별본으로 서빙됨, 임시).
     *    ENV 는 {@link Environment#getProperty(String)} 로 읽어 Environment mock 으로 검증 가능하게 한다.
     *    메시지에는 프로파일/ENV 값만 노출(PII/원본경로 없음 — CWE-209).
     */
    private void assertMockAllowedProfile() {
        String[] active = environment.getActiveProfiles();
        String env = environment.getProperty("ENV");
        String envNormalized = (env == null) ? null : env.trim();

        boolean noActiveProfile = active.length == 0;
        boolean activeHasDisallowed = Arrays.stream(active)
                .anyMatch(p -> !ALLOWED_MOCK_PROFILES.contains(p.trim().toLowerCase(Locale.ROOT)));
        boolean envDisallowed = envNormalized != null && !envNormalized.isBlank()
                && !ALLOWED_MOCK_PROFILES.contains(envNormalized.toLowerCase(Locale.ROOT));

        if (noActiveProfile || activeHasDisallowed || envDisallowed) {
            throw new IllegalStateException(
                    "deidentify mock-mode 는 허용 프로파일(local/dev/stg)이 확인될 때만 부팅됩니다(fail-closed). activeProfiles="
                            + Arrays.toString(active)
                            + ", ENV=" + (env == null ? "<unset>" : env));
        }

        boolean localActive = Arrays.stream(active).anyMatch(p -> "local".equalsIgnoreCase(p));
        if (!localActive) {
            log.warn("[Batch][Deid] mock-mode active on non-local profile: 원본 영상이 비식별본으로 서빙됨(임시) activeProfiles={}",
                    Arrays.toString(active));
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
        // DEV_FIX — run() 을 프록시 경유로 호출해 @Transactional(REQUIRES_NEW) 가 실제 트랜잭션을 연다.
        // 적재 경로(AsyncDeidentifyRunner.runAsync, 무트랜잭션)에서 직접 자기호출은 프록시를 우회해
        // 트랜잭션이 열리지 않아 mock 영속(DE_IDNTF_YN='Y'/procLog SUCCEEDED)이 커밋되지 않던 결함 차단.
        // 단위 테스트(수동 생성, provider=null)는 this 로 폴백(리포지토리 mock — 트랜잭션 불필요).
        DeidentifyStep self = (selfProvider != null) ? selfProvider.getObject() : this;
        DeidentResult result = self.run(ctx.getRaw());
        // 동기 완료(mock) 여부를 컨텍스트에 실어 호출자(AsyncDeidentifyRunner)가 조건부 전이하게 한다.
        // void execute() 라 run() 반환을 직접 못 받으므로 컨텍스트로 브릿지한다(상태머신 단일화).
        ctx.markDeidentCompleted(result.completed());
    }

    /**
     * 영상 비식별을 트리거한다 — KPST 단일 경로(또는 local mock).
     * <p>
     * 경로 결정(우선순위):
     *  1. {@code mockMode}(local 전용): 외부 호출 없이 원본을 비식별 경로로 복사.
     *  2. KPST 위탁({@code kpstEnabled} + 서비스 주입): {@link KpstDeidentService#submit} 위탁만 수행.
     *     DE_IDNTF_YN 미전이(완료 대기), MARKING_READY 미전이 — 완료는 폴링 잡이 담당.
     *  3. 그 외(설정 오류): 레거시 동기 폴백 없음 → 명확한 설정 오류 예외(내부 정보 미노출).
     * <p>
     * REQUIRES_NEW 트랜잭션: 위탁 실패 시에도 src 레코드는 유지된다.
     *
     * @return 동기 완료(mock)면 {@link DeidentResult#completed}, 외부 위탁(KPST)이면 {@link DeidentResult#deferred}.
     *         호출자는 completed 일 때만 MARKING_READY 로 전이한다(지연이면 폴링이 단일 지점에서 전이).
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public DeidentResult run(LsDataRaw raw) {
        if (raw == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "raw 가 null 입니다.");
        }
        // Phase 1 — local 전용 mock 경로: 외부 비식별 서버 없이 원본을 비식별 경로로 복사한다.
        // KPST 위탁 분기보다 앞에 둔다(mock 활성 시 외부 미접촉).
        if (mockMode) {
            return DeidentResult.completed(runMock(raw));
        }
        // UC018 — KPST 폴링 경로(기본): 위탁(upload→project)만 수행하고 완료(다운로드→Y전이)는 폴링 잡이 담당.
        // 제출 직후에는 MARKING_READY 로 전이하지 않는다(DE_IDNTF_YN='N' 유지) — deferred 반환으로 호출자가 전이를 건너뛴다.
        if (kpstEnabled && kpstDeidentService != null) {
            kpstDeidentService.submit(raw);
            return DeidentResult.deferred();
        }
        // 설정 오류 — mock 도 아니고 KPST 서비스도 주입되지 않았다. 레거시 폴백은 제거되었으므로
        // 임의 동작 대신 명확히 거부한다(CWE-209: 내부 구현/경로 미노출, 고정 메시지만).
        log.error("[Batch][Deid] no deidentify path available rawSn={} kpstEnabled={} kpstService={}",
                raw.getRawSn(), kpstEnabled, kpstDeidentService != null);
        throw new CustomException(ErrorCode.INTERNAL_ERROR, "비식별 경로가 구성되지 않았습니다.");
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
