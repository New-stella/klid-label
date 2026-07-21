package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.client.KpstDeidentifyClient;
import kr.co.cudo.authoring.common.client.dto.KpstProgressResponse;
import kr.co.cudo.authoring.common.client.dto.KpstProjectRequest;
import kr.co.cudo.authoring.common.client.dto.KpstProjectResponse;
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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

/**
 * KPST 비식별 솔루션 폴링 오케스트레이션 서비스 (Phase 3 / UC018).
 *
 * <p>위탁(shared-mount 경로 참조 → project 생성)과 완료감지(retrieve_progress 폴링)→회수→완료전이를 담당한다.
 * KPST 가 결과를 우리 비식별 저장소(export_path={base}/videos/{rawSn}/)에 직접 쓰며, 완료 시 진행조회 응답의
 * 파일명을 그대로 회수 경로로 쓴다(복사·GET /download 없음 — no-copy).
 * {@code kpst.deid.enabled=true} 일 때만 빈으로 등록되며(콜백 경로와 병행 무변경),
 * {@link kr.co.cudo.authoring.batch.step.DeidentifyStep}(위탁)과
 * {@code KpstDeidentPollJob}(폴링)이 본 서비스를 사용한다.
 *
 * <h3>흐름</h3>
 * <ol>
 *   <li>{@link #submit(LsDataRaw)} — 공유 마운트 원본 경로 참조로 {@code createProject} → procLog
 *       {@code markKpstSubmitted(prjId)} + POLL_STTS=WAITING 기록. DE_IDNTF_YN 미전이(완료대기).</li>
 *   <li>{@link #pollOne(LsDeidentProcLog)} — {@code retrieveProgress} → procState 판정.
 *       완료(state=2)면 응답 fileName 으로 회수 경로를 산출(복사 없음)하고 무결성 검증 후 완료 전이.
 *       진행중이면 시도 증가. 타임아웃이면 'F' 마킹.</li>
 *   <li>{@link #completeDeidentification(Long, String)} — DE_IDNTF_YN='Y' → MARKING_READY →
 *       락해제 → OPEN 신고 RESOLVED → 알림 (콜백/폴링 공유, {@link KpstDeidentTxService} 위임).</li>
 * </ol>
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>SSRF/경로순회 (CWE-918/CWE-22): URL/CA 신뢰체인은 {@link KpstDeidentifyClient} 가 방어.
 *       회수 경로의 외부 응답 fileName 은 원본 입력파일의 절대/경로형이 정상(실측 계약)이므로
 *       {@code sanitizeFileName} 으로 basename 만 추출(= 순회 제거)한 뒤 {@code {stem}-mask{ext}} 로
 *       변환하고, 최종 resolve 결과가 base 하위인지 단언한다. 폴백 스캔 회수 경로도 base 하위 단언.</li>
 *   <li>무한 폴링 방지: 시도 횟수·경과 시간 타임아웃 → 'F' 마킹(자동 재비식별 큐 신설 없음, 외부 수동).</li>
 *   <li>정보 유출 (CWE-209): 로그에 rawSn/prjId/datasetId 만 출력. PII/원본경로/외부 본문/fileName 원문 미출력.</li>
 *   <li>불완전 산출물 (CWE-459/404): 회수 경로가 미존재/0바이트면 {@code isUsableDeidFile} 가 'F' 처리(Y 전이 차단).</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "kpst.deid", name = "enabled", havingValue = "true")
public class KpstDeidentService {

    /**
     * KPST 데이터셋 처리 완료 상태 코드 — procState 도메인 {@code 2 완료} (§22.4 위키 "완료=2").
     * 진행조회 응답 {@code dsStatus.procState}(데이터셋 값) 판정 기준이다.
     */
    public static final int PROC_STATE_COMPLETED = 2;

    /**
     * KPST 데이터셋(procState) 터미널-실패 상태 코드 집합 — 이 상태면 타임아웃을 기다리지 않고 즉시 'F' 종결.
     *
     * <p><b>도메인 주의(K2)</b>: 본 집합은 진행조회 응답의 {@code dsStatus.procState}(데이터셋 코드)만
     * 판정한다. procState 규격 코드는 {@code 0 대기중 · 1 실행중 · 2 완료 · 3 중지 · 4 삭제중 · 99 오류}
     * 이며, 프로젝트 단위 {@code prjState}(0~6) 와는 별개 도메인이다(procState 에 5·6 은 존재하지 않음).
     *
     * <ul>
     *   <li>{@code 3} — 중지: 사용자/시스템에 의해 중단된 데이터셋(재폴링해도 진행되지 않음).</li>
     *   <li>{@code 4} — 삭제중: 데이터셋 삭제 진행(산출물 미기대).</li>
     *   <li>{@code 99} — 오류: 2026-06-25 실서버 KPST 라이브 테스트에서 마스킹 실패 잡이 모두
     *       {@code procState:99, progressRate:0.0} 으로 반환된 실측 확인 에러 sentinel.</li>
     * </ul>
     * <p>{@code null(미시작)/0(대기중)/1(실행중)} 및 그 외 미지 코드는 진행중(타임아웃 바운드)으로
     * 유지한다(완료={@link #PROC_STATE_COMPLETED} 만 완료).
     */
    static final int PROC_STATE_STOPPED = 3;   // 중지
    static final int PROC_STATE_DELETING = 4;  // 삭제중
    static final int PROC_STATE_ERROR = 99;    // 오류(실측 sentinel)
    static final Set<Integer> PROC_STATE_TERMINAL_FAILED =
            Set.of(PROC_STATE_STOPPED, PROC_STATE_DELETING, PROC_STATE_ERROR);
    /** 비식별 결과 저장 하위 디렉터리(우리 base 상대) — export_path/회수 경로 공통. */
    private static final String DIR_VIDEOS = "videos";
    /** KPST 산출물 파일명 접미사({@code {stem}-mask{ext}}) — 회수 경로 재구성 기준. */
    private static final String MASK_SUFFIX = "-mask";

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
     * KPST 위탁 — 공유 마운트 원본 경로 참조 → createProject → procLog WAITING 기록.
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
            // shared-mount 모델(규격 §22.3.3): 업로드 없이 원본 파일 경로를 직접 /project 에 전달한다.
            String rawFilePathNm = raw.getRawFilePathNm();
            if (rawFilePathNm == null || rawFilePathNm.isBlank()) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "원본 파일 경로가 비어있습니다.");
            }
            Path fullPath = Paths.get(rawFilePathNm);
            Path parent = fullPath.getParent();
            if (parent == null) {
                // 비정상 경로(부모 디렉터리 없음) — input_path/export_path 를 구성할 수 없으므로 거부(CWE-22).
                throw new CustomException(ErrorCode.INVALID_INPUT, "원본 파일 경로의 부모 디렉터리를 확인할 수 없습니다.");
            }
            String dir = parent.toString();
            List<String> files = List.of(fullPath.getFileName().toString());
            // export_path = 우리 비식별 저장소 base ({STORAGE_DEIDENTIFIED_PATH}/videos/{rawSn}/).
            // KPST 가 결과를 이 경로에 직접 WRITE 하므로(no-copy) 쓰기 대상 디렉터리를 사전 생성한다.
            Path exportDir = baseDeidentifiedPath.resolve(DIR_VIDEOS).resolve(String.valueOf(rawSn));
            try {
                Files.createDirectories(exportDir);
            } catch (IOException ioe) {
                // 위탁 실패 — 아래 catch(RuntimeException) 가 'F' 마킹 후 예외 전파.
                throw new CustomException(ErrorCode.INTERNAL_ERROR, "비식별 결과 저장 디렉터리 생성 실패");
            }
            // HIGH: REDEIDENT 재위탁 시 이전 회차 산출물(mock/규칙 변경 시 타임스탬프명 누적)이 남아
            // 폴백 스캔이 stale 을 오회수하거나 다중 파일 모호 실패로 정상 완료를 막을 수 있다.
            // 이번 회차 산출물만 남도록 export 디렉터리 바로 아래 정규 파일을 정리한다(최초 위탁 시 no-op).
            cleanExportDir(exportDir, rawSn);
            KpstProjectRequest projectReq = KpstProjectRequest.withDefaults(
                    projectName(rawSn), creatorId,
                    exportDir + "/",    // export_path = 우리 base/videos/{rawSn}/ (KPST 결과 WRITE 대상)
                    dir + "/",          // input_path  = 원본 부모디렉터리, 끝 슬래시 필수(규격 §22.3.3)
                    files);
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

    /**
     * KPST 위탁 직전 export 디렉터리({@code {base}/videos/{rawSn}/}) 정리 — 이번 회차 산출물만 남도록
     * 기존 산출물(정규 파일)을 제거한다(HIGH). no-copy 비식별 결과 영상 전용 디렉터리이므로 안전하다
     * (프레임 추출물은 {@code {base}/frames/...} 별도 경로).
     *
     * <p><b>삭제 안전 가드(CWE-22 경로탈출·심링크 추종 방어)</b>:
     * <ol>
     *   <li>① {@code exportDir.normalize()} 가 {@code baseDeidentifiedPath} 하위인지 단언 후에만 진행
     *       (base 탈출 시 정리하지 않음).</li>
     *   <li>② 디렉터리 <b>바로 아래 정규 파일만</b> 삭제(재귀 금지, 하위 디렉터리 미삭제) —
     *       {@code Files.list}(비재귀) + {@code isRegularFile(NOFOLLOW_LINKS)} 필터.</li>
     *   <li>③ 심링크는 따라가지 않음 — {@code NOFOLLOW_LINKS} 로 링크/디렉터리는 정규파일 판정에서
     *       제외되어 건너뛴다(링크 타깃 삭제·추종 없음).</li>
     *   <li>④ 디렉터리 미존재/비디렉터리면 no-op(최초 위탁 시 빈 디렉터리 → 삭제 대상 0건).</li>
     *   <li>⑤ 삭제/순회 실패(IOException)는 원문/경로 미노출(CWE-209) 로그 후 위탁 진행 —
     *       정리 실패가 위탁을 막지 않는다(정리 못 하면 이후 폴백이 모호 실패로 안전 종결).</li>
     * </ol>
     */
    private void cleanExportDir(Path exportDir, Long rawSn) {
        Path normalized = exportDir.normalize();
        // ① base 하위 단언(CWE-22) — 벗어나면 정리하지 않음(방어심도).
        if (!normalized.startsWith(baseDeidentifiedPath)) {
            log.warn("[KpstDeid] skip export dir cleanup — outside base rawSn={}", rawSn);
            return;
        }
        // ④ 미존재/비디렉터리(심링크 디렉터리도 NOFOLLOW 로 제외) → no-op.
        if (!Files.isDirectory(normalized, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        // ② 비재귀 리스트 + try-with-resources 로 스트림 닫기.
        try (java.util.stream.Stream<Path> entries = Files.list(normalized)) {
            entries.forEach(entry -> {
                // ②③ 바로 아래 정규 파일만 — 심링크/하위 디렉터리는 NOFOLLOW 판정에서 제외되어 건너뜀.
                if (!Files.isRegularFile(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    return;
                }
                try {
                    Files.delete(entry);
                } catch (IOException de) {
                    // ⑤ 개별 삭제 실패 — 원문/경로 미노출(CWE-209), 위탁은 계속.
                    log.warn("[KpstDeid] export dir cleanup delete failed rawSn={} errType={}",
                            rawSn, de.getClass().getSimpleName());
                }
            });
        } catch (IOException le) {
            // ⑤ 디렉터리 순회 실패 — 위탁을 막지 않고 진행(이후 폴백이 모호 실패로 안전 종결됨).
            log.warn("[KpstDeid] export dir cleanup list failed rawSn={} errType={}",
                    rawSn, le.getClass().getSimpleName());
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
        KpstProgressResponse progress;
        try {
            progress = kpstClient.retrieveProgress(reqUserId, prjId);
        } catch (RuntimeException e) {
            // K1: KPST 지속 예외(5xx/커넥션거부/서킷오픈 CallNotPermittedException 등)가 나면
            // 시도 카운터(recordPollingProgress)와 완료판정이 실행되지 않아, 잡이 예외를 삼키고 다음 틱에
            // 동일 건을 재폴링하면 poll-timeout-minutes 경과 후에도 'F' 전이가 일어나지 않아 무기한 stuck 된다
            // (raw PENDING·REDEIDENT 작업락 잔존). 예외 경로에서도 경과시간 기준 타임아웃을 반드시 평가해
            // 초과 시 'F' 마킹(REDEIDENT 락 해제 포함 — markTimeoutIfExpired 내부 처리)하고, 아직 경과 전이면
            // 이번 틱만 skip 하고 다음 폴링을 대기한다. CWE-209: 외부 원문/스택트레이스 미노출(예외 클래스명만).
            boolean timedOut = txService.markTimeoutIfExpired(procLogSn, pollMaxAttempts, pollTimeoutMinutes);
            log.warn("[KpstDeid] poll retrieveProgress failed rawSn={} prjId={} errType={} timedOut={}",
                    rawSn, prjId, e.getClass().getSimpleName(), timedOut);
            return;
        }
        KpstProgressResponse.DsStatus ds = firstDataset(progress);
        if (ds == null) {
            // 아직 데이터셋 미생성 — 시도 증가 후 타임아웃 검사.
            txService.recordPollingProgress(procLogSn, null);
            txService.markTimeoutIfExpired(procLogSn, pollMaxAttempts, pollTimeoutMinutes);
            return;
        }
        Long datasetId = procLog.getKpstDatasetId() != null ? procLog.getKpstDatasetId() : ds.dsId();
        // 터미널-실패 우선 판정(완료보다 앞) — 다중 데이터셋이면 하나라도 실패면 즉시 'F' 종결.
        // 오류 sentinel(99 실측)·중지(3)·삭제중(4)를 진행중으로 보지 않아 타임아웃(180분) 대기를 끊는다.
        if (anyDatasetFailed(progress)) {
            // REDEIDENT 는 락 해제 포함 종결(영구잠금 방지). 기존 배치는 위탁 시 작업락을 잡지 않으므로
            // failPolling('F' 마킹 + terminal, 락 해제 없음)으로 종결해도 무해하다(잠글 락 자체가 없음).
            if (procLog.isRedeident()) {
                txService.failRedeidentCompletion(procLogSn, rawSn, "PROC_STATE_FAILED");
                log.warn("[KpstDeid] poll terminal-failed (redeident) rawSn={} prjId={}", rawSn, prjId);
            } else {
                txService.failPolling(procLogSn, rawSn);
                log.warn("[KpstDeid] poll terminal-failed rawSn={} prjId={}", rawSn, prjId);
            }
            return;
        }
        // M-3: 다중 데이터셋이면 전체 완료(AND)여야 완료로 판정 — 부분완료 오판 방지.
        if (allDatasetsCompleted(progress)) {
            if (datasetId == null) {
                txService.markTimeoutIfExpired(procLogSn, pollMaxAttempts, pollTimeoutMinutes);
                return;
            }
            // 완료 — no-copy: KPST 가 export_path 에 직접 쓴 결과를 응답 fileName 으로 회수(복사 없음).
            // 회수 경로 무결성 검증(존재+>0바이트) 통과 시에만 Y 전이 원자화.
            // DEV_FIX HIGH-1: 산출 경로 도출(downloadResult=sanitize 포함)이 외부 fileName(null/빈값/
            // 구분자/'..')으로 던지는 예외를 finishDownloadAndComplete 실패와 동일하게 terminal 처리한다.
            // (보호 영역 밖이면 예외가 pollOne 을 탈출 → 잡이 swallow → 시도증가 없이 무한 재폴링 + raw
            // PENDING stuck, REDEIDENT 작업락 영구 미해제.)
            String deidPathStr;
            try {
                deidPathStr = downloadResult(rawSn, ds.fileName());
            } catch (RuntimeException e) {
                if (procLog.isRedeident()) {
                    txService.failRedeidentCompletion(procLogSn, rawSn, e.getClass().getSimpleName());
                    log.warn("[KpstDeid] poll bad result fileName terminal (redeident) rawSn={} prjId={} errType={}",
                            rawSn, prjId, e.getClass().getSimpleName());
                } else {
                    txService.failPolling(procLogSn, rawSn);
                    log.warn("[KpstDeid] poll bad result fileName terminal rawSn={} prjId={} errType={}",
                            rawSn, prjId, e.getClass().getSimpleName());
                }
                return;
            }
            if (!isUsableDeidFile(deidPathStr)) {
                // DEV_FIX HIGH-1: 불완전 산출물(0바이트/미존재) — 'F' 처리, Y 전이 금지. no-copy 모델에서 KPST 가
                // export_path 에 0바이트/미기록한 채 procState=2 를 주는 건 현실적 실패 모드다. REDEIDENT 건은
                // failPolling(락 미해제)로 끝내면 작업락이 영구 잔존(재요청 409 영구 차단)하므로 bad-fileName
                // 분기와 동일하게 failRedeidentCompletion(F + 락 해제 + terminal)으로 종결한다.
                if (procLog.isRedeident()) {
                    txService.failRedeidentCompletion(procLogSn, rawSn, "DEIDENT_INCOMPLETE");
                    log.warn("[KpstDeid] poll incomplete deid file terminal (redeident) rawSn={} prjId={}",
                            rawSn, prjId);
                } else {
                    txService.failPolling(procLogSn, rawSn);
                    log.warn("[KpstDeid] poll incomplete deid file rawSn={} prjId={}", rawSn, prjId);
                }
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
            // LOW-1: 심볼릭 링크가 정규파일로 통과하지 않도록 링크 미추적(공급망 방어심도).
            return Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.size(file) > 0;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /**
     * 비식별 결과 회수 경로 산출(no-copy) — KPST 가 export_path({base}/videos/{rawSn}/) 에 직접 쓴
     * 결과를 회수한다. 복사·GET /download 없음.
     *
     * <p><b>계약(2026-07-21 실서버 curl/ll 확정)</b>: 진행조회 응답 {@code dsStatus.fileName} 은
     * <b>결과 파일명이 아니라 원본 입력파일의 절대경로</b>(input_path + 원본 basename)다
     * (예: {@code /nas-.../raw/001.mp4}). 실제 산출물은 export_path 에 {@code {stem}-mask{ext}}
     * 접미사로 생성된다(예: {@code 001.mp4} → {@code 001-mask.mp4}). 따라서:
     * <ol>
     *   <li>응답 fileName 에서 basename 만 추출({@link #sanitizeFileName}) — 이 추출이 곧 디렉터리
     *       순회 제거(CWE-22)다. 절대/경로형 fileName 은 정상 응답이므로 거부하지 않는다.</li>
     *   <li>basename 을 {@code {stem}-mask{ext}} 로 변환해 1차 회수 경로를 만든다.</li>
     *   <li>1차 경로가 사용 불가면(접미사/확장자 규칙 변화 대비) export 디렉터리를 스캔해
     *       단일 산출 영상 파일을 폴백 회수한다. 0개면 1차 경로(→ 미사용 판정)로 반환, 2개 이상이면
     *       모호로 실패 처리한다(no-copy 모델은 rawSn당 산출물 1개).</li>
     * </ol>
     *
     * <p>경로 주입 방어(CWE-22): basename 추출로 순회를 제거하고, 최종 resolve 결과가 base 하위인지
     * 방어심도 단언한다. 산출물 존재/0바이트 검증은 호출측 {@link #isUsableDeidFile} 가 수행한다.
     */
    private String downloadResult(Long rawSn, String fileNameFromResponse) {
        String base = sanitizeFileName(fileNameFromResponse);
        Path dir = baseDeidentifiedPath.resolve(DIR_VIDEOS).resolve(String.valueOf(rawSn));
        // 1차: {stem}-mask{ext} 산출명 재구성(실측 계약).
        Path maskPath = resolveUnderBase(dir, toMaskName(base));
        if (isUsableDeidFile(maskPath.toString())) {
            return maskPath.toString();
        }
        // 폴백: 접미사/확장자 규칙 변화 대비 — 디렉터리 내 단일 산출 영상을 회수(no-copy=1개 기대).
        Path fallback = scanSingleUsable(dir);
        if (fallback != null) {
            return fallback.toString();
        }
        // 0개 — 1차 경로 반환(호출측 isUsableDeidFile 가 false → failPolling 로 깨끗이 종결).
        return maskPath.toString();
    }

    /**
     * basename 을 실제 산출물명 {@code {stem}-mask{ext}} 로 변환한다(경로 분리 아님 — basename 내
     * 마지막 {@code .} 기준 stem/ext 분리). 확장자가 없으면 {@code {name}-mask}.
     *
     * <p>LOW-1(방어적 가드): stem 이 이미 {@link #MASK_SUFFIX} 로 끝나면(즉 입력이 이미
     * {@code {stem}-mask{ext}} 형태면) 접미사를 재부여하지 않고 그대로 사용한다
     * (중복 {@code 001-mask-mask.mp4} 방지).
     */
    private String toMaskName(String base) {
        int dot = base.lastIndexOf('.');
        if (dot <= 0) {
            // 확장자 없음(dot<0) 또는 선두 점(dot==0, 예 ".mp4") — 접미사만 붙인다.
            return base.endsWith(MASK_SUFFIX) ? base : base + MASK_SUFFIX;
        }
        String stem = base.substring(0, dot);
        String ext = base.substring(dot);
        // 이미 {stem}-mask 이면 재부여하지 않음(중복 접미사 방지).
        return stem.endsWith(MASK_SUFFIX) ? base : stem + MASK_SUFFIX + ext;
    }

    /**
     * export 디렉터리를 스캔해 단일 사용가능 산출 영상 경로를 폴백 회수한다.
     * <ul>
     *   <li>1개 — 그 파일(base 하위 단언 통과)을 반환.</li>
     *   <li>0개 — {@code null}(호출측이 1차 경로로 failPolling 종결).</li>
     *   <li>2개 이상 — 모호하므로 {@link ErrorCode#INVALID_INPUT} 로 안전 실패(terminal).</li>
     * </ul>
     * 디렉터리 미존재/입출력 오류는 {@code null}(폴백 없음)로 처리한다.
     */
    private Path scanSingleUsable(Path dir) {
        if (!Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        List<Path> usable = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> isUsableDeidFile(p.toString())).forEach(usable::add);
        } catch (IOException e) {
            return null;
        }
        if (usable.size() == 1) {
            Path only = usable.get(0).normalize();
            if (!only.startsWith(baseDeidentifiedPath)) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "비식별 결과 경로가 기준 디렉터리를 벗어났습니다.");
            }
            return only;
        }
        if (usable.size() >= 2) {
            // no-copy 모델은 산출물 1개가 정상 — 다중이면 어느 것이 결과인지 모호하므로 안전 실패.
            throw new CustomException(ErrorCode.INVALID_INPUT, "비식별 결과 산출물이 모호합니다(다중 파일).");
        }
        return null;
    }

    /** {@code dir/name} 을 resolve 후 base 하위인지 단언(CWE-22 방어심도). */
    private Path resolveUnderBase(Path dir, String name) {
        Path resolved = dir.resolve(name).normalize();
        if (!resolved.startsWith(baseDeidentifiedPath)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "비식별 결과 경로가 기준 디렉터리를 벗어났습니다.");
        }
        return resolved;
    }

    /**
     * 외부 응답 fileName 에서 basename 만 추출(CWE-22) — 응답 fileName 은 원본 입력파일의
     * <b>절대/경로형</b>이 정상(실측 계약)이므로 경로형이라는 이유로 거부하지 않는다. {@code Paths.get}
     * 으로 파싱한 뒤 {@code getFileName()} 으로 basename 을 취하며, 이 추출 자체가 디렉터리 순회를 제거한다.
     * 추출된 basename 에 대해서만 빈값·경로 구분자({@code /}, {@code \})·상위 참조({@code ..}) 잔존을
     * 거부한다(정상 basename 은 이들을 포함하지 않음). null/blank·NUL바이트 등 위반 시
     * {@link ErrorCode#INVALID_INPUT}.
     *
     * <p>CWE-209: 거부 메시지에 외부 fileName 원문을 노출하지 않는다.
     */
    private String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "비식별 결과 파일명이 비어있습니다.");
        }
        // HIGH-2: NUL바이트/잘못된 경로문자는 Paths.get 이 InvalidPathException(메시지에 입력 원문 포함)을
        // 던진다 → CustomException(INVALID_INPUT)으로 정규화하고 원문 미노출(CWE-209).
        Path p;
        try {
            p = Paths.get(name);
        } catch (java.nio.file.InvalidPathException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "비식별 결과 파일명이 유효하지 않습니다.");
        }
        // 경로형 fileName 수용: basename 만 취한다(= 순회 제거, CWE-22). 절대경로/'/' 포함이어도 정상.
        Path fileNamePart = p.getFileName();
        String base = fileNamePart != null ? fileNamePart.toString() : "";
        // 추출한 basename 에 대해서만 잔존 위험 검증(정상 basename 은 아래를 포함하지 않음).
        if (base.isBlank()
                || base.contains("/") || base.contains("\\") || base.contains("..")) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "비식별 결과 파일명이 유효하지 않습니다.");
        }
        return base;
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
     * 프로젝트의 데이터셋 중 하나라도 터미널-실패({@link #PROC_STATE_TERMINAL_FAILED})인지.
     *
     * <p>완료(AND) 판정보다 먼저 호출되어 "하나라도 실패 → 실패" 가 우선한다(다중 데이터셋 안전).
     * procState 가 null(미시작)이면 실패가 아니며, 데이터셋이 비어있으면 실패 아님(진행중 흐름 유지).
     */
    private boolean anyDatasetFailed(KpstProgressResponse progress) {
        if (progress == null || progress.data() == null
                || progress.data().prjStatus() == null || progress.data().prjStatus().isEmpty()) {
            return false;
        }
        KpstProgressResponse.PrjStatus prj = progress.data().prjStatus().get(0);
        if (prj.dsStatus() == null || prj.dsStatus().isEmpty()) {
            return false;
        }
        return prj.dsStatus().stream()
                .anyMatch(d -> d.procState() != null && PROC_STATE_TERMINAL_FAILED.contains(d.procState()));
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
