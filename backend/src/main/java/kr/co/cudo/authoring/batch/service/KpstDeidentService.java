package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.client.KpstDeidentifyClient;
import kr.co.cudo.authoring.common.client.dto.KpstProgressResponse;
import kr.co.cudo.authoring.common.client.dto.KpstProjectRequest;
import kr.co.cudo.authoring.common.client.dto.KpstProjectResponse;
import kr.co.cudo.authoring.common.client.dto.KpstUploadResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * KPST 비식별 솔루션 폴링 오케스트레이션 서비스 (Phase 3 / UC018).
 *
 * <p>위탁(upload→project)과 완료감지(retrieve_progress 폴링)→다운로드→완료전이를 담당한다.
 * {@code kpst.deid.enabled=true} 일 때만 빈으로 등록되며(콜백 경로와 병행 무변경),
 * {@link kr.co.cudo.authoring.batch.step.DeidentifyStep}(위탁)과
 * {@code KpstDeidentPollJob}(폴링)이 본 서비스를 사용한다.
 *
 * <h3>흐름</h3>
 * <ol>
 *   <li>{@link #submit(LsDataRaw)} — {@code upload(원본)} → {@code createProject} → procLog
 *       {@code markKpstSubmitted(prjId)} + POLL_STTS=WAITING 기록. DE_IDNTF_YN 미전이(완료대기).</li>
 *   <li>{@link #pollOne(LsDeidentProcLog)} — {@code retrieveProgress} → procState 판정.
 *       완료(state=2)면 {@code download}(트랜잭션 밖) 후 완료 전이. 진행중이면 시도 증가.
 *       타임아웃이면 'F' 마킹.</li>
 *   <li>{@link #completeDeidentification(Long, String)} — DE_IDNTF_YN='Y' → MARKING_READY →
 *       락해제 → OPEN 신고 RESOLVED → 알림 (콜백/폴링 공유, {@link KpstDeidentTxService} 위임).</li>
 * </ol>
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>SSRF/경로순회 (CWE-918/CWE-22): URL/CA 신뢰체인·다운로드 경로 검증은 {@link KpstDeidentifyClient}
 *       가 방어. 본 서비스는 base 디렉터리 상대 경로만 구성한다.</li>
 *   <li>무한 폴링 방지: 시도 횟수·경과 시간 타임아웃 → 'F' 마킹(자동 재비식별 큐 신설 없음, 외부 수동).</li>
 *   <li>정보 유출 (CWE-209): 로그에 rawSn/prjId/datasetId 만 출력. PII/원본경로/외부 본문 미출력.</li>
 *   <li>트랜잭션 경계: 다운로드(I/O)는 트랜잭션 밖, 상태 전이만 {@link KpstDeidentTxService} REQUIRES_NEW.</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "kpst.deid", name = "enabled", havingValue = "true")
public class KpstDeidentService {

    /** KPST 데이터셋 처리 완료 상태 코드 (§22.4). */
    public static final int PROC_STATE_COMPLETED = 2;
    /** 비식별 다운로드 결과 파일명. */
    private static final String DEID_FILE_NAME = "deidentified.mp4";

    private final KpstDeidentifyClient kpstClient;
    private final VideoRepository videoRepository;
    private final LsDeidentProcLogRepository procLogRepository;
    private final KpstDeidentTxService txService;

    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String deidPath;

    @Value("${kpst.deid.creator-id:authoring}")
    private String creatorId;

    @Value("${kpst.deid.req-user-id:authoring}")
    private String reqUserId;

    @Value("${kpst.deid.export-path-base:/share/Deid-data/export/}")
    private String exportPathBase;

    /** 폴링 타임아웃 — 최대 시도 횟수. 초과 시 'F' 마킹(무한 폴링 방지). */
    @Value("${kpst.deid.poll-max-attempts:240}")
    private int pollMaxAttempts;

    /** 폴링 타임아웃 — 위탁 후 경과 시간(분). 초과 시 'F' 마킹. */
    @Value("${kpst.deid.poll-timeout-minutes:180}")
    private long pollTimeoutMinutes;

    private Path baseDeidentifiedPath;

    public KpstDeidentService(KpstDeidentifyClient kpstClient,
                              VideoRepository videoRepository,
                              LsDeidentProcLogRepository procLogRepository,
                              KpstDeidentTxService txService) {
        this.kpstClient = kpstClient;
        this.videoRepository = videoRepository;
        this.procLogRepository = procLogRepository;
        this.txService = txService;
    }

    @PostConstruct
    void initBasePath() {
        this.baseDeidentifiedPath = Paths.get(deidPath).toAbsolutePath().normalize();
    }

    // ────────────────────────────── 위탁 ──────────────────────────────

    /**
     * KPST 위탁 — upload(원본) → createProject → procLog WAITING 기록.
     *
     * <p>영상 1건 = 프로젝트 1개. project_name 은 rawSn 기반 유니크. DE_IDNTF_YN 은 아직 미전이
     * (완료 대기). MARKING_READY 미전이. 위탁 실패 시 'F' 마킹 후 예외 전파.
     *
     * <p>본 메서드는 cross-bean 호출(DeidentifyStep)로 진입하므로 REQUIRES_NEW 프록시가 적용된다.
     *
     * @return 위탁 기록된 procLog
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public LsDeidentProcLog submit(LsDataRaw raw) {
        return submit(raw, false);
    }

    /**
     * KPST 위탁 — {@code redeident=true} 면 검수완료 재비식별(REDEIDENT) 경로로 procLog 를 표시한다.
     *
     * <p>표시값(REQ_KIND_CD=REDEIDENT)은 폴링 완료 시점({@link KpstDeidentTxService}) 의 분기에 사용되어,
     * 완료 처리가 비식별 프레임 attach + APPROVED 유지(상태 강등 금지) 경로를 타도록 한다. 기존 배치 경로
     * ({@code redeident=false})는 무영향(REQ_KIND_CD=null)이다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public LsDeidentProcLog submit(LsDataRaw raw, boolean redeident) {
        if (raw == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "raw 가 null 입니다.");
        }
        Long rawSn = raw.getRawSn();
        LsDeidentProcLog procLog = LsDeidentProcLog.request(rawSn, null, raw.getRawFilePathNm(), "batch");
        if (redeident) {
            procLog.markRedeident();
        }
        procLog = procLogRepository.save(procLog);
        try {
            String subdir = projectName(rawSn);
            List<Path> files = List.of(Paths.get(raw.getRawFilePathNm()));
            KpstUploadResponse upload = kpstClient.upload(files, subdir);
            if (upload == null || upload.data() == null
                    || upload.data().files() == null || upload.data().files().isEmpty()) {
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "업로드 응답이 비어있습니다.");
            }
            KpstProjectRequest projectReq = KpstProjectRequest.withDefaults(
                    projectName(rawSn), creatorId,
                    exportPathBase + subdir + "/", upload.data().inputPath(),
                    upload.data().files());
            KpstProjectResponse project = kpstClient.createProject(projectReq);
            if (project == null || project.prjId() == null) {
                throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "프로젝트 생성 응답이 비어있습니다.");
            }
            // datasetId 는 첫 폴링에서 보충 — 위탁 시점 미상(§22.3.3).
            procLog.markKpstSubmitted(project.prjId(), null);
            log.info("[KpstDeid] submitted rawSn={} prjId={}", rawSn, project.prjId());
            return procLog;
        } catch (RuntimeException e) {
            // CWE-209: 외부 본문/스택트레이스 미보존 — 코드/예외 클래스명만.
            procLog.fail("EXTERNAL_API_ERROR", e.getClass().getSimpleName());
            videoRepository.findById(rawSn).ifPresent(v -> v.markDeidentified("F"));
            log.error("[KpstDeid] submit failed rawSn={} errType={}", rawSn, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "비식별 위탁 실패", e);
        }
    }

    // ────────────────────────────── 폴링 ──────────────────────────────

    /**
     * 단일 위탁 건 폴링 — retrieveProgress → 완료/진행중/타임아웃 분기.
     *
     * <p>다운로드(I/O)는 트랜잭션 밖에서 수행하고, 상태 전이는 {@link KpstDeidentTxService}(REQUIRES_NEW)
     * 로 위임한다. 폴링 실패는 호출자(잡)가 건별로 격리한다.
     */
    public void pollOne(LsDeidentProcLog procLog) {
        Long rawSn = procLog.getDataRawSn();
        Long prjId = procLog.getKpstPrjId();
        Long procLogSn = procLog.getProcLogSn();
        if (prjId == null) {
            // 위탁 미완(데이터 정합 깨짐) — 타임아웃 검사로만 처리.
            txService.markTimeoutIfExpired(procLogSn, pollMaxAttempts, pollTimeoutMinutes);
            return;
        }
        KpstProgressResponse progress = kpstClient.retrieveProgress(reqUserId, prjId);
        KpstProgressResponse.DsStatus ds = firstDataset(progress);
        if (ds == null) {
            // 아직 데이터셋 미생성 — 시도 증가 후 타임아웃 검사.
            txService.recordPollingProgress(procLogSn, null);
            txService.markTimeoutIfExpired(procLogSn, pollMaxAttempts, pollTimeoutMinutes);
            return;
        }
        Long datasetId = procLog.getKpstDatasetId() != null ? procLog.getKpstDatasetId() : ds.dsId();
        // M-3: 다중 데이터셋이면 전체 완료(AND)여야 완료로 판정 — 부분완료 오판 방지.
        if (allDatasetsCompleted(progress)) {
            if (datasetId == null) {
                txService.markTimeoutIfExpired(procLogSn, pollMaxAttempts, pollTimeoutMinutes);
                return;
            }
            // 완료 — 다운로드(트랜잭션 밖) 후 무결성 검증 → 다운로드+Y 전이 원자화.
            String deidPathStr = downloadResult(rawSn, datasetId);
            if (!isUsableDeidFile(deidPathStr)) {
                // 불완전 산출물(0바이트/미존재) — 'F' 처리, Y 전이 금지.
                txService.failPolling(procLogSn, rawSn);
                log.warn("[KpstDeid] poll incomplete deid file rawSn={} prjId={}", rawSn, prjId);
                return;
            }
            try {
                txService.finishDownloadAndComplete(rawSn, procLogSn, datasetId, deidPathStr);
                log.info("[KpstDeid] poll completed rawSn={} prjId={} datasetId={}", rawSn, prjId, datasetId);
            } catch (RuntimeException e) {
                // DEV_FIX HIGH(결함1·결함2): 완료 감지 후 후처리(REDEIDENT 프레임 attach 등) 실패는 메인
                // 완료 트랜잭션이 롤백되어 procLog 가 WAITING/POLLING 으로 복귀 → 무한 재폴링 + 락 영구잠금.
                // REDEIDENT 경로는 즉시 terminal 종결(별도 REQUIRES_NEW 커밋)로 재폴링/잠금을 끊는다.
                // (KPST 비식별 자체는 성공, 우리측 후처리 실패 → 재시도 무의미.) 비-REDEIDENT 는 현행 유지.
                if (procLog.isRedeident()) {
                    txService.failRedeidentCompletion(procLogSn, rawSn, e.getClass().getSimpleName());
                    log.warn("[KpstDeid] poll redeident completion failed rawSn={} prjId={} errType={}",
                            rawSn, prjId, e.getClass().getSimpleName());
                    return;
                }
                throw e;
            }
        } else {
            // 진행중 — datasetId 보충 + 시도 증가 + 타임아웃 검사.
            txService.recordPollingProgress(procLogSn, ds.dsId());
            txService.markTimeoutIfExpired(procLogSn, pollMaxAttempts, pollTimeoutMinutes);
        }
    }

    /** 다운로드 결과가 사용 가능한 비식별 산출물인지(존재 + >0바이트) 확인 — 불완전 산출물 차단(M-2). */
    private boolean isUsableDeidFile(String deidFilePath) {
        if (deidFilePath == null || deidFilePath.isBlank()) {
            return false;
        }
        try {
            Path file = Paths.get(deidFilePath);
            return Files.isRegularFile(file) && Files.size(file) > 0;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /** KPST 다운로드(I/O) — 트랜잭션 밖. base 상대 경로 구성(클라이언트가 경로순회 방어). */
    private String downloadResult(Long rawSn, Long datasetId) {
        Path relative = Paths.get("videos", String.valueOf(rawSn), DEID_FILE_NAME);
        Path saved = kpstClient.download(datasetId, baseDeidentifiedPath, relative);
        return saved.toString();
    }

    // ────────────────────────────── 완료(공유) ──────────────────────────────

    /**
     * 비식별 완료 처리(공유) — {@link KpstDeidentTxService#completeDeidentification} 위임.
     * 콜백/폴링 양 경로가 호출한다.
     */
    public void completeDeidentification(Long rawSn, String deidFilePath) {
        try {
            txService.completeDeidentification(rawSn, deidFilePath);
        } catch (RuntimeException e) {
            // M-1: completeDeidentification(REQUIRES_NEW) 내부의 verifyDeidFile F-마킹은 예외 전파 시 같은
            // 트랜잭션이 롤백되어 취소된다(조용한 실패). 비-REDEIDENT(콜백/배치) 경로의 파일 무효 실패에서
            // F-마킹이 실제 커밋되도록 별도 REQUIRES_NEW 로 보정한다. REDEIDENT 는 폴링 경로(failRedeident
            // Completion)가 종결하므로 여기서 보정하지 않는다.
            if (!isRedeidentLog(rawSn)) {
                txService.markRawDeidentFailed(rawSn);
            }
            throw e;
        }
    }

    /** rawSn 기준 최신 procLog 가 REDEIDENT 경로인지 — 콜백 경로 F-마킹 보정 대상 판정용(M-1). */
    private boolean isRedeidentLog(Long rawSn) {
        return procLogRepository.findAllByDataRawSnOrderByReqDtDesc(rawSn).stream()
                .findFirst()
                .map(LsDeidentProcLog::isRedeident)
                .orElse(false);
    }

    // ────────────────────────────── helpers ──────────────────────────────

    /** project_name = rawSn 기반 유니크. 숫자만이라 KPST 허용 문자 규칙 충족. */
    private String projectName(Long rawSn) {
        return "raw" + rawSn;
    }

    private KpstProgressResponse.DsStatus firstDataset(KpstProgressResponse progress) {
        if (progress == null || progress.data() == null
                || progress.data().prjStatus() == null || progress.data().prjStatus().isEmpty()) {
            return null;
        }
        KpstProgressResponse.PrjStatus prj = progress.data().prjStatus().get(0);
        if (prj.dsStatus() == null || prj.dsStatus().isEmpty()) {
            return null;
        }
        return prj.dsStatus().get(0);
    }

    /**
     * 프로젝트의 모든 데이터셋이 완료(procState==2)인지 — M-3.
     *
     * <p>영상 1건 = 프로젝트 1개 = 데이터셋 1개가 정상이나, 다중 데이터셋이 올 경우 첫 데이터셋만 보고
     * 전체완료로 오판하지 않도록 전체 AND 로 검증한다(하나라도 미완료면 진행중). 데이터셋이 비어있으면
     * 미완료로 본다.
     */
    private boolean allDatasetsCompleted(KpstProgressResponse progress) {
        if (progress == null || progress.data() == null
                || progress.data().prjStatus() == null || progress.data().prjStatus().isEmpty()) {
            return false;
        }
        KpstProgressResponse.PrjStatus prj = progress.data().prjStatus().get(0);
        if (prj.dsStatus() == null || prj.dsStatus().isEmpty()) {
            return false;
        }
        // procState 가 null 이면 처리 미시작(실서버는 미시작 시 null 반환) — 미완료로 취급(NPE 방지).
        return prj.dsStatus().stream()
                .allMatch(d -> d.procState() != null && d.procState() == PROC_STATE_COMPLETED);
    }
}
