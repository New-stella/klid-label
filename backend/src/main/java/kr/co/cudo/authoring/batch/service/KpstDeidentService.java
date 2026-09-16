package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.batch.dto.KpstDeidentReportSummary;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.async.SubmitSignalDispatch;
import kr.co.cudo.authoring.common.client.KpstDeidentifyClient;
import kr.co.cudo.authoring.common.client.dto.KpstProgressResponse;
import kr.co.cudo.authoring.common.client.dto.KpstProjectRequest;
import kr.co.cudo.authoring.common.client.dto.KpstReportResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.storage.DeidentArtifactIntegrity;
import kr.co.cudo.authoring.common.storage.VideoArtifactRootResolver;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
 *   <li>{@link #submit(LsDataRaw)} — 원장 선커밋(POLL_STTS=WAITING, prjId 미정) 후 공유 마운트 원본
 *       경로 참조로 {@code createProject} <b>논블로킹 제출</b>(Phase C-2). ACK 수신 시 완료 핸들러가
 *       prjId 를 기록한다. DE_IDNTF_YN 미전이(완료대기).</li>
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
 *   <li>불완전/위장 산출물 (CWE-459/CWE-345): 회수 경로가 미존재이거나 크기 하한·컨테이너 시그니처를
 *       만족하지 않으면 {@code isUsableDeidFile}({@link kr.co.cudo.authoring.common.storage.DeidentArtifactIntegrity})
 *       가 'F' 처리(Y 전이 차단). 위탁 시점에는 원본 실재를 검증해 애초에 거짓 완료가 생기지 않게 한다(B-ISSUE-01).</li>
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

    /** B-ISSUE-01 — 위탁 거부 사유 코드(procLog). 원본 경로/PII 는 남기지 않는다(CWE-209). */
    private static final String SOURCE_MISSING_CODE = "KPST_SOURCE_MISSING";

    /**
     * Phase C-2 — 비동기 제출이 <b>확정 실패</b>(onError·구독 거부·빈 응답)했을 때의 원장 실패 코드.
     * ERR_CD(50) 도메인에 적재된다.
     */
    public static final String SUBMIT_FAILED_CODE = "KPST_SUBMIT_FAILED";

    /**
     * Phase C-2 — 제출 ACK 가 <b>끝내 오지 않아</b> 폴링 잡이 회수한 건의 원장 실패 코드.
     *
     * <p>{@link #SUBMIT_FAILED_CODE}(확정 실패, 신호를 받았음)와 구분한다 — 이 코드가 남았다는 것은
     * "우리 프로세스가 ACK 를 관측하지 못했다"는 뜻이라, KPST 쪽에는 프로젝트가 실제로 생성돼 있을 수
     * 있다(노드 사망 등). 운영이 외부 상태를 확인해야 하는 건을 코드로 식별하기 위해 분리한다.
     */
    public static final String ACK_MISSING_CODE = "KPST_ACK_MISSING";

    /**
     * M3 — 호출자 트랜잭션이 <b>커밋되지 않아</b> 제출이 아예 개시되지 않은 건의 원장 종결 코드.
     *
     * <p>{@link #SUBMIT_FAILED_CODE}(외부 호출이 실패)와 구분한다 — 이 코드는 "외부로 나간 것이 없다"는
     * 뜻이라 <b>영상 비식별 상태를 'F' 로 내리지 않는다</b>. ACK 유예 회수({@link #ACK_MISSING_CODE})에
     * 맡기면 그 종착이 'F' 라, 실패한 요청이 3분 뒤 영상을 차단 상태로 만든다.
     */
    public static final String SUBMIT_CANCELED_CODE = "KPST_SUBMIT_CANCELED";

    /**
     * 산출물 무결성 재확인 유예의 <b>상한</b>(ms) — 폴링 워커 점유 보호 (DEV_FIX LOW).
     *
     * <p>유예는 폴링 워커 스레드를 그대로 잡는 {@code Thread.sleep} 이라 설정값이 크면 다른 영상의
     * 폴링이 그만큼 밀린다. "쓰기 중/NFS 가시성 지연" 은 초 단위 현상이므로 5초를 넘겨 기다릴 이유가
     * 없고, 오설정(예: 300000)이 폴링 사이클을 정지시키는 것을 이 상한이 막는다.
     */
    private static final long MAX_RESULT_RECHECK_DELAY_MS = 5_000L;

    private final KpstDeidentifyClient kpstClient;
    private final VideoRepository videoRepository;
    private final LsDeidentProcLogRepository procLogRepository;
    private final KpstDeidentTxService txService;
    /**
     * B-ISSUE-01 — 위탁 거부 시 'F' 마킹을 <b>별도 REQUIRES_NEW 로 커밋</b>하기 위한 빈.
     * {@link #submit(LsDataRaw, boolean)} 자체가 REQUIRES_NEW 라 예외를 던지면 그 트랜잭션 내부의
     * 상태 변경은 롤백된다 — 실패 흔적이 사라지지 않도록 별도 빈으로 커밋한다({@code DeidentifyStep} 동일 패턴).
     */
    private final BatchTransitionService batchTransitionService;
    /**
     * A-2 — KPST {@code export_path}(결과 WRITE 대상 디렉터리)를 결정하는 단일 지점.
     * co-locate: {@code dirname(원본)/{rawSn}/deid/} · 롤백: {@code {deid_base}/videos/{rawSn}/}.
     * <b>파일명은 KPST 가 정한다</b>({@code {stem}-mask{ext}} 실측) — 우리가 지정하는 것은 디렉터리까지다.
     */
    private final VideoArtifactRootResolver artifactRootResolver;
    /** Phase C-2 — 비동기 제출의 완료 신호(ACK/실패) 기록 전용 빈(자체 트랜잭션 없음, 프록시 경유 위임). */
    private final KpstSubmitOutcomeRecorder outcomeRecorder;
    /** Phase C-2 — 완료 신호 전용 스케줄러. 완료 핸들러의 JPA 쓰기가 이벤트 루프에서 돌지 않게 고정한다. */
    private final reactor.core.scheduler.Scheduler kpstSubmitScheduler;
    /**
     * R9 — 운영자가 조정한 마스킹 옵션(마스킹 방식·범위·프레임 저장 여부) 조달원.
     *
     * <p>⚠ 조회 실패는 <b>위탁을 막지 않는다</b> — {@link #resolveMaskingOptions} 가 예외를 삼키고
     * {@code KpstProjectRequest.DEFAULT_*} 로 폴백한다(fail-safe). 여기서 예외가 나가면 호출측이
     * 선커밋된 원장을 'F' 로 종결해 <b>설정 조회 하나로 비식별 파이프라인이 멈춘다</b>.
     *
     * <p>⚠ <b>반영 지연(인지·수용)</b>: 설정 캐시 TTL 이 60초라 값을 바꾼 노드는 즉시 반영되지만
     * 2노드 Active-Active 의 <b>다른 노드는 최대 60초 지연</b>된다.
     */
    private final SystemConfigService systemConfigService;

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

    /**
     * B-ISSUE-01 — 위탁 전 원본 실재 검증 토글. <b>기본 켬(fail-closed)</b>.
     *
     * <p>공유 마운트가 앱에서 보이지 않는 배포(원본을 KPST 만 볼 수 있는 구성)를 위한 이스케이프 해치다.
     * 끄면 원본 부재가 두 단계 뒤(FRAME_EXTRACT)에야 드러나므로, 끈 상태의 위탁은 매 건 WARN 을 남긴다.
     */
    @Value("${kpst.deid.verify-source-exists:true}")
    private boolean verifySourceExists;

    /**
     * B-ISSUE-01 — 산출물 무결성 1회 재확인 유예(ms). 0 이면 즉시 판정.
     *
     * <p>완료(procState=2) 응답과 파일 가시성 사이에는 "쓰기 중"/NFS 가시성 지연 창이 있다. 즉시 terminal
     * 'F' 로 끊으면 자동 재시도가 없어 정상 건이 사고가 되므로, <b>후보 파일이 존재하는데 무결성만
     * 실패</b>한 경우에 한해 짧게 기다렸다가 1회만 재확인한다(미존재는 유예 대상이 아니다 — 진짜 미기록).
     *
     * <p><b>상한 clamp</b>: 이 유예는 폴링 워커 스레드를 그대로 점유하는 {@code Thread.sleep} 이므로
     * 설정값이 크면 그만큼 다른 영상의 폴링이 밀린다. 그래서 실제 대기는
     * {@link #MAX_RESULT_RECHECK_DELAY_MS} 로 상한을 둔다 — 가시성 지연은 초 단위 현상이라 상한을
     * 넘겨 기다릴 이유가 없고, 오설정(예: 300000)이 폴링을 정지시키는 것을 막는다.
     */
    @Value("${kpst.deid.result-recheck-delay-ms:2000}")
    private long resultRecheckDelayMs;

    /**
     * Phase C-2 — 제출 ACK 대기 유예(초). 이 시간 안에는 폴링 잡이 해당 건을 <b>건너뛴다</b>
     * (외부 호출 0건, 시도 카운터 미소모). 유예를 넘기면 폴러가 {@link #ACK_MISSING_CODE} 로 회수한다.
     *
     * <p>기본 180초 — 클라이언트 타임아웃 45s × 재시도 3회 + 백오프(1s·2s) 최악값(≈138s)을 덮는다.
     * 이보다 짧으면 정상 재시도 중인 건을 회수해버리고, 지나치게 길면 죽은 건이 그만큼 오래 남는다.
     */
    @Value("${kpst.deid.submit-ack-grace-sec:180}")
    private long submitAckGraceSec;

    private Path baseDeidentifiedPath;

    public KpstDeidentService(KpstDeidentifyClient kpstClient,
                              VideoRepository videoRepository,
                              LsDeidentProcLogRepository procLogRepository,
                              KpstDeidentTxService txService,
                              VideoArtifactRootResolver artifactRootResolver,
                              BatchTransitionService batchTransitionService,
                              KpstSubmitOutcomeRecorder outcomeRecorder,
                              @Qualifier("kpstSubmitScheduler") reactor.core.scheduler.Scheduler kpstSubmitScheduler,
                              SystemConfigService systemConfigService) {
        this.kpstClient = kpstClient;
        this.videoRepository = videoRepository;
        this.procLogRepository = procLogRepository;
        this.txService = txService;
        this.artifactRootResolver = artifactRootResolver;
        this.batchTransitionService = batchTransitionService;
        this.outcomeRecorder = outcomeRecorder;
        this.kpstSubmitScheduler = kpstSubmitScheduler;
        this.systemConfigService = systemConfigService;
    }

    @PostConstruct
    void initBasePath() {
        this.baseDeidentifiedPath = Paths.get(deidPath).toAbsolutePath().normalize();
    }

    // ────────────────────────────── 위탁 ──────────────────────────────

    /**
     * KPST 위탁 — 공유 마운트 원본 경로 참조 → 원장 선커밋 → {@code createProject} <b>논블로킹 제출</b>.
     *
     * <p>위탁 1회차 = 프로젝트 1개. project_name 은 회차마다 겹치지 않는다(첫 위탁 {@code raw{rawSn}},
     * 다시 위탁은 {@code raw{rawSn}r{이번 회차 원장 번호}} — {@link #projectName}). DE_IDNTF_YN 은 아직 미전이
     * (완료 대기). MARKING_READY 미전이.
     *
     * @return 위탁 원장(선커밋). 반환 시점에는 {@code prjId} 가 아직 없다(ACK 미도착).
     */
    public LsDeidentProcLog submit(LsDataRaw raw) {
        return submit(raw, false);
    }

    /**
     * KPST 위탁 — {@code redeident=true} 면 검수완료 재비식별(REDEIDENT) 경로로 procLog 를 표시한다.
     *
     * <p>표시값(REQ_KIND_CD=REDEIDENT)은 폴링 완료 시점({@link KpstDeidentTxService}) 의 분기에 사용되어,
     * 완료 처리가 비식별 프레임 attach + APPROVED 유지(상태 강등 금지) 경로를 타도록 한다. 기존 배치 경로
     * ({@code redeident=false})는 무영향(REQ_KIND_CD=null)이다.
     *
     * <h3>★ 논블로킹 제출 (Phase C-2) — "외부연동은 모두 비동기" 의 스레드 축</h3>
     * <p>프로토콜은 원래 비동기였으나(결과는 {@code retrieve_progress} 폴링) <b>ACK 왕복 동안 스레드를
     * 점유</b>했다({@code .block(45s)}). 그 스레드는 적재 경로의 {@code batch-async-}(core 2) 또는
     * 재비식별 요청의 Tomcat 요청 스레드였다. 이제 ACK 도 기다리지 않는다:
     * <ol>
     *   <li><b>선커밋</b> — 원장 발급({@link KpstDeidentTxService#issueSubmitLedger}, REQUIRES_NEW 독립
     *       커밋)을 <b>제출 전에</b> 수행한다. ACK/실패 신호가 호출자 트랜잭션 커밋보다 먼저 도착해도
     *       기록 대상이 존재한다.</li>
     *   <li><b>제출</b> — 구독만 하고 즉시 반환한다. 활성 트랜잭션이 있으면 <b>커밋 후</b>에 구독한다
     *       ({@link #dispatchSubmit}).</li>
     *   <li><b>완료 핸들러</b> — 전용 풀({@code kpstSubmitScheduler})에서
     *       {@link KpstSubmitOutcomeRecorder} 가 ACK(prjId 기록)/실패('F' 종결)를 기록한다.</li>
     *   <li><b>회수</b> — 아무 신호도 오지 않으면(노드 사망 등) 폴링 잡이 ACK 대기 유예 만료로
     *       회수한다({@link #ACK_MISSING_CODE}). 별도 스위퍼를 신설하지 않는다 — 폴러가 이미 클레임·
     *       타임아웃·'F' 종결을 갖춘 회수기다(이중 진실원 금지).</li>
     * </ol>
     *
     * <p><b>동기 실패 전파가 남는 것은 제출 이전의 사전 조건뿐</b>이다(raw null · 원본 부재 ·
     * 경로 손상 · export 디렉터리 생성/검증 실패). 외부에 아무것도 나가지 않은 실패이므로 기존과 동일하게
     * 예외를 던지고, 그와 별개로 실패 흔적은 별도 트랜잭션으로 커밋한다.
     */
    public LsDeidentProcLog submit(LsDataRaw raw, boolean redeident) {
        if (raw == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "raw 가 null 입니다.");
        }
        Long rawSn = raw.getRawSn();
        // B-ISSUE-01 — 위탁 전 원본 실재 가드(fail-closed). 원본이 없는데 위탁하면 KPST 가 결과를 만들지
        // 못한 채(또는 스텁만 남긴 채) 완료로 응답해 거짓 'Y'/MARKING_READY 가 된다. 실패는 별도 커밋.
        verifySourceOrFail(rawSn, raw.getRawFilePathNm());
        // 선커밋 — 원장(WAITING + prjId null = ACK 대기)을 외부 호출 전에 독립 커밋한다.
        LsDeidentProcLog procLog = txService.issueSubmitLedger(rawSn, raw.getRawFilePathNm(), redeident);
        Long procLogSn = procLog.getProcLogSn();

        KpstProjectRequest projectReq;
        try {
            // 원장 번호(procLogSn)가 이미 확정된 뒤에 조립한다 — 재위탁 이름의 접미가 그 번호다(INT-004).
            projectReq = buildProjectRequest(raw, rawSn, procLogSn);
        } catch (RuntimeException e) {
            // 제출 이전 사전 조건 실패 — 외부에 아무것도 나가지 않았다. 원장을 별도 트랜잭션으로 종결
            // ('F' 커밋)한 뒤 기존 계약대로 동기 예외를 전파한다.
            // (구 코드는 같은 REQUIRES_NEW 안에서 'F' 를 찍고 예외를 던져 그 마킹이 함께 롤백됐다 —
            //  실패 흔적이 사라져 영상이 PENDING 에 고착되던 결함.)
            txService.failSubmit(procLogSn, rawSn, SUBMIT_FAILED_CODE, e.getClass().getSimpleName());
            log.error("[KpstDeid] submit prepare failed rawSn={} errType={}",
                    rawSn, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "비식별 위탁 실패", e);
        }
        dispatchSubmit(rawSn, procLogSn, projectReq);
        return procLog;
    }

    /**
     * {@code POST /project} 요청 바디 구성 — shared-mount 모델(규격 §22.3.3)의 경로 도출·검증·정리.
     * 외부 호출 <b>전</b> 단계이므로 실패는 동기 예외로 전파된다(호출측이 원장을 종결한다).
     */
    private KpstProjectRequest buildProjectRequest(LsDataRaw raw, Long rawSn, Long procLogSn) {
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
        // export_path = 비식별 영상 디렉터리(A-2). co-locate 전략에서는 원본 영상과 같은 디렉터리 하위
        // ({dirname(원본)}/{rawSn}/deid/) 라 관제가 산출물 트리 한 경로로 전부 픽업할 수 있다.
        // KPST 가 결과를 이 경로에 직접 WRITE 하므로(no-copy) 쓰기 대상 디렉터리를 사전 생성한다.
        // 파일명은 KPST 소관이라 여기서 정하지 않는다.
        Path exportDir = artifactRootResolver.deidVideoDir(rawSn, rawFilePathNm);
        try {
            Files.createDirectories(exportDir);
        } catch (IOException ioe) {
            // 위탁 실패 — 호출측이 원장을 'F' 로 종결한 뒤 예외를 전파한다.
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "비식별 결과 저장 디렉터리 생성 실패");
        }
        // B-3(TOCTOU, CWE-367/59) — 검증~생성 사이의 심링크 바꿔치기 창을 닫는다. base 를 다시 계산
        // (allowlist·실경로 재검증)하고 방금 만든 디렉터리의 실경로가 여전히 그 하위인지 재확인한다.
        // 이 경로는 KPST 가 결과를 직접 WRITE 하는 대상이라 위탁 전에 확정되어야 한다.
        VideoArtifactRootResolver.verifyRealPathUnder(
                exportDir, artifactRootResolver.deidVideoDir(rawSn, rawFilePathNm));
        // HIGH: REDEIDENT 재위탁 시 이전 회차 산출물(mock/규칙 변경 시 타임스탬프명 누적)이 남아
        // 폴백 스캔이 stale 을 오회수하거나 다중 파일 모호 실패로 정상 완료를 막을 수 있다.
        // 이번 회차 산출물만 남도록 export 디렉터리 바로 아래 정규 파일을 정리한다(최초 위탁 시 no-op).
        cleanExportDir(exportDir, rawSn, rawFilePathNm);
        // R9 — 마스킹 옵션 3종은 운영자 설정에서 읽는다(조회 실패·비정상값이면 규격 기본값 폴백).
        // exp_quality / exp_format 은 벤더 미지원이라 설정으로 열지 않고 규격 기본값 그대로 싣는다.
        MaskingOptions opts = resolveMaskingOptions();
        return new KpstProjectRequest(
                projectName(rawSn, procLogSn), creatorId,
                exportDir + "/",    // export_path = 우리 base/videos/{rawSn}/ (KPST 결과 WRITE 대상)
                dir + "/",          // input_path  = 원본 부모디렉터리, 끝 슬래시 필수(규격 §22.3.3)
                files,
                opts.maskingType(), opts.dbSave(), opts.maskingRange(),
                KpstProjectRequest.DEFAULT_EXP_QUALITY, KpstProjectRequest.DEFAULT_EXP_FORMAT);
    }

    /** R9 — 위탁 요청에 실을 마스킹 옵션 묶음(전부 폴백 가능). */
    private record MaskingOptions(int maskingType, int dbSave, double maskingRange) {}

    /**
     * R9 — 운영자 설정에서 마스킹 옵션 3종을 읽는다. <b>어떤 실패도 위탁을 막지 않는다.</b>
     *
     * <p>이 메서드는 <b>선커밋된 원장 뒤·외부 호출 직전</b>에서 호출되므로, 여기서 예외가 나가면
     * 호출측이 원장을 'F' 로 종결해 위탁 자체가 실패한다. 설정 조회 하나로 비식별 파이프라인이
     * 멈추면 안 되므로 예외를 잡아 규격 기본값으로 폴백하고 WARN 만 남긴다.
     *
     * <p><b>2중 방어(fail-closed)</b>: 입구 검증({@code SystemConfigService.update})과 별개로,
     * DB 에 수기로 허용목록 밖 값이 들어가 있을 수 있으므로 읽어온 값도 허용값·범위로 재확인한다.
     * 벗어나면 기본값으로 폴백한다 — 잘못된 코드값을 외부로 그대로 보내지 않는다.
     *
     * <p>로그에 설정값 원문을 싣지 않는다(CWE-117 — 값은 DB 수기 수정으로 임의 문자열일 수 있다).
     */
    private MaskingOptions resolveMaskingOptions() {
        return new MaskingOptions(
                readAllowedInt(ConfigKeys.KPST_DEID_MASKING_TYPE, KpstProjectRequest.DEFAULT_MASKING_TYPE),
                readAllowedInt(ConfigKeys.KPST_DEID_DB_SAVE, KpstProjectRequest.DEFAULT_DB_SAVE),
                readRangedDouble(ConfigKeys.KPST_DEID_MASKING_RANGE, KpstProjectRequest.DEFAULT_MASKING_RANGE));
    }

    /** NUMBER 설정 조회 + 허용값 집합 재확인. 실패·이탈 시 폴백. */
    private int readAllowedInt(String key, int fallback) {
        try {
            Integer v = systemConfigService.getInt(key);
            Set<Integer> allowed = ConfigKeys.NUMBER_ALLOWED_VALUES.get(key);
            if (v == null || (allowed != null && !allowed.contains(v))) {
                log.warn("[KpstDeid] 마스킹 옵션 값이 허용 목록 밖이라 기본값 사용 key={} fallback={}", key, fallback);
                return fallback;
            }
            return v;
        } catch (Exception e) {
            log.warn("[KpstDeid] 마스킹 옵션 조회 실패 — 기본값 사용 key={} fallback={} errType={}",
                    key, fallback, e.getClass().getSimpleName());
            return fallback;
        }
    }

    /** DECIMAL 설정 조회 + 허용 범위 재확인. 실패·이탈 시 폴백. */
    private double readRangedDouble(String key, double fallback) {
        try {
            Double v = systemConfigService.getDouble(key);
            double[] range = ConfigKeys.DECIMAL_RANGE.get(key);
            if (v == null || v.isNaN() || v.isInfinite()
                    || (range != null && (v < range[0] || v > range[1]))) {
                log.warn("[KpstDeid] 마스킹 옵션 값이 허용 범위 밖이라 기본값 사용 key={} fallback={}", key, fallback);
                return fallback;
            }
            return v;
        } catch (Exception e) {
            log.warn("[KpstDeid] 마스킹 옵션 조회 실패 — 기본값 사용 key={} fallback={} errType={}",
                    key, fallback, e.getClass().getSimpleName());
            return fallback;
        }
    }

    /**
     * 논블로킹 제출 개시 — 활성 트랜잭션이 있으면 <b>커밋 후</b>에 구독한다.
     *
     * <p><b>왜 커밋 후인가</b>: 호출자({@code ApprovedRedeidentService})는 자기 트랜잭션 안에서
     * <b>작업락을 INSERT</b> 한 뒤 위탁한다. 제출이 논블로킹이면 실패 신호가 그 커밋보다 먼저 도착할 수
     * 있고, 그때 실패 핸들러(REQUIRES_NEW)는 <b>아직 커밋되지 않은 락을 볼 수 없어</b> 해제하지 못한다 →
     * 재요청이 409 로 영구 차단된다. 커밋 후 구독이 이 창을 구조적으로 닫는다.
     *
     * <p>호출자 트랜잭션이 <b>롤백</b>되면 제출은 아예 일어나지 않는다(외부 고아 작업 방지).
     *
     * <h3>★ 롤백 시 원장을 <b>취소 종결</b>한다 (M3)</h3>
     * <p>원장은 {@code REQUIRES_NEW} 로 이미 독립 커밋돼 있어 호출자 롤백으로 사라지지 않는다. 그대로
     * 두면 폴러가 ACK 대기 유예(기본 180초) 만료로 회수하는데, <b>그 회수의 종착이
     * {@code DE_IDNTF_YN='F'}</b> 다({@link KpstDeidentTxService#failSubmit}). 즉 요청이 실패(롤백)해
     * 외부로 아무것도 나가지 않았는데 3분 뒤 그 영상이 신고 게이트에 걸려 라벨 조회 412 · 스트리밍 404 ·
     * export 보류 상태가 된다 — APPROVED 영상 재비식별 요청이 실패했을 때 특히 해롭다.
     *
     * <p>그래서 {@code afterCompletion} 에서 커밋되지 않은 경우 원장을 <b>취소</b>로 종결한다
     * ({@link KpstDeidentTxService#cancelSubmit}): 원장은 terminal 이라 폴링 대상에서 빠지고,
     * 영상 상태는 <b>건드리지 않는다</b>(위탁이 없었으므로 비식별 실패가 아니다). Spring 규약대로
     * 그 안의 DB 작업은 {@code REQUIRES_NEW} 로 수행한다.
     */
    private void dispatchSubmit(Long rawSn, Long procLogSn, KpstProjectRequest projectReq) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    subscribeSubmit(rawSn, procLogSn, projectReq);
                }

                @Override
                public void afterCompletion(int status) {
                    if (status == TransactionSynchronization.STATUS_COMMITTED) {
                        return;
                    }
                    // 외부로 아무것도 나가지 않았다 — 선커밋된 원장만 취소 종결한다(영상 상태 불변).
                    try {
                        boolean canceled = txService.cancelSubmit(procLogSn, rawSn);
                        log.warn("[KpstDeid] submit canceled — caller tx not committed rawSn={} applied={}",
                                rawSn, canceled);
                    } catch (RuntimeException e) {
                        // 취소 커밋까지 실패하면 폴러의 ACK 유예 회수가 뒤를 받는다(그 종착은 'F').
                        log.error("[KpstDeid] submit cancel failed rawSn={} errType={}",
                                rawSn, e.getClass().getSimpleName());
                    }
                }
            });
            return;
        }
        subscribeSubmit(rawSn, procLogSn, projectReq);
    }

    /**
     * 실제 구독 — 완료 신호의 <b>기록</b>을 전용 풀({@code kpstSubmitScheduler})에서만 실행한다.
     *
     * <h3>왜 {@code publishOn} 이 아니라 명시적 디스패치인가 (M2)</h3>
     * <p>{@code publishOn(전용풀)} 은 풀이 포화(AbortPolicy)되면 스케줄 제출이 거부되고, 그 거부가
     * <b>시그널을 나른 스레드(reactor-netty 이벤트 루프)</b>에서 onError 로 흘러 실패 핸들러의 JPA 쓰기를
     * 이벤트 루프에서 실행시킨다 — 같은 루프를 쓰는 모든 외부 호출이 동반 지연된다. 아래 try/catch 는
     * 동기 {@code subscribe()} 구간만 덮으므로 그 거부를 잡지 못한다. 그래서 핸들러 호출 자체를
     * {@link SubmitSignalDispatch} 로 감싸 "전용 풀 안에서만 실행"을 구조적으로 강제한다.
     *
     * <p>풀 포화로 기록이 <b>포기</b>되면 원장은 {@code WAITING + prjId null} 로 남고, 폴러가 ACK 대기
     * 유예 만료로 회수한다({@link #ACK_MISSING_CODE}) — 선커밋 행이 방치되지 않는다.
     *
     * <p>아래 catch 는 조립/구독이 동기 실패한 경우(클라이언트 즉시 throw)만 도달하며, 그 스레드는
     * 호출 스레드(커밋 후 콜백 또는 무트랜잭션)라 JPA 직접 호출이 안전하다.
     */
    private void subscribeSubmit(Long rawSn, Long procLogSn, KpstProjectRequest projectReq) {
        try {
            kpstClient.createProject(projectReq)
                    .subscribe(resp -> SubmitSignalDispatch.run(kpstSubmitScheduler, "KpstDeid", rawSn,
                                    () -> outcomeRecorder.onAccepted(rawSn, procLogSn, resp)),
                            err -> SubmitSignalDispatch.run(kpstSubmitScheduler, "KpstDeid", rawSn,
                                    () -> outcomeRecorder.onSubmitFailed(rawSn, procLogSn, err)));
        } catch (RuntimeException e) {
            outcomeRecorder.onSubmitFailed(rawSn, procLogSn, e);
        }
    }

    /**
     * B-ISSUE-01 — KPST 위탁 전 <b>원본 영상 실재</b> 검증(fail-closed).
     *
     * <p>기존에는 경로 문자열의 blank/부모 유무만 봤기 때문에, 원본 파일이 없는 영상도 위탁이 성공하고
     * 첫 폴링에서 곧바로 완료 처리되어 {@code DE_IDNTF_YN='Y'} + {@code MARKING_READY} 가 됐다
     * (산출물 실체는 18바이트 스텁). 원본 부재는 두 단계 뒤 프레임 추출에서야 드러났다.
     * mock 경로({@code DeidentifyStep.runMock})는 이미 원본 실재를 검증하고 있었으므로, 운영 실경로만
     * 비어 있던 비대칭을 여기서 메운다.
     *
     * <p>실패 시 {@link BatchTransitionService#recordDeidentFailure}(REQUIRES_NEW) 로 'F' 를 <b>커밋</b>한
     * 뒤 거부한다 — 본 메서드를 호출하는 {@code submit} 이 REQUIRES_NEW 라 예외 전파 시 자체 트랜잭션의
     * 변경은 롤백되기 때문이다. 로그/예외에 원본 경로 원문은 남기지 않는다(CWE-209).
     */
    private void verifySourceOrFail(Long rawSn, String rawFilePathNm) {
        if (!verifySourceExists) {
            // 침묵 금지 — 미검증 위탁임을 추적 가능하게 남긴다(영상 1건당 1줄).
            log.warn("[KpstDeid] source existence guard disabled — 원본 미검증 위탁 rawSn={}", rawSn);
            return;
        }
        boolean present = false;
        if (rawFilePathNm != null && !rawFilePathNm.isBlank()) {
            try {
                present = Files.isRegularFile(Paths.get(rawFilePathNm));
            } catch (java.nio.file.InvalidPathException e) {
                present = false;
            }
        }
        if (present) {
            return;
        }
        batchTransitionService.recordDeidentFailure(rawSn, SOURCE_MISSING_CODE, "source not found");
        log.warn("[KpstDeid] submit rejected — source video missing rawSn={}", rawSn);
        throw new CustomException(ErrorCode.INVALID_INPUT, "비식별 원본 영상이 존재하지 않습니다.");
    }

    /**
     * KPST 위탁 직전 export 디렉터리({@code {base}/videos/{rawSn}/}) 정리 — 이번 회차 산출물만 남도록
     * 기존 산출물(정규 파일)을 제거한다(HIGH). no-copy 비식별 결과 영상 전용 디렉터리이므로 안전하다
     * (프레임 추출물은 {@code {base}/frames/...} 별도 경로).
     *
     * <p><b>삭제 안전 가드(CWE-22 경로탈출·심링크 추종 방어)</b>:
     * <ol>
     *   <li>① <b>정확히 이번 회차의 비식별 영상 디렉터리인지</b> 단언 후에만 진행한다 —
     *       {@code exportDir.normalize()} 가 리졸버로 <b>다시 계산한</b>
     *       {@code deidVideoDir(rawSn, rawFilePathNm)} 와 {@code equals} 여야 한다. "설정 루트 하위 어디든"
     *       (= NAS 전체) 을 허용하는 넓은 판정은 삭제 가드로는 과하다(B-4 회귀 방지).</li>
     *   <li>② 디렉터리 <b>바로 아래 정규 파일만</b> 삭제(재귀 금지, 하위 디렉터리 미삭제) —
     *       {@code Files.list}(비재귀) + {@code isRegularFile(NOFOLLOW_LINKS)} 필터.</li>
     *   <li>③ 심링크는 따라가지 않음 — {@code NOFOLLOW_LINKS} 로 링크/디렉터리는 정규파일 판정에서
     *       제외되어 건너뛴다(링크 타깃 삭제·추종 없음).</li>
     *   <li>④ 디렉터리 미존재/비디렉터리면 no-op(최초 위탁 시 빈 디렉터리 → 삭제 대상 0건).</li>
     *   <li>⑤ 삭제/순회 실패(IOException)는 원문/경로 미노출(CWE-209) 로그 후 위탁 진행 —
     *       정리 실패가 위탁을 막지 않는다(정리 못 하면 이후 폴백이 모호 실패로 안전 종결).</li>
     * </ol>
     */
    private void cleanExportDir(Path exportDir, Long rawSn, String rawFilePathNm) {
        Path normalized = exportDir.normalize();
        // ① 삭제 대상은 <이 rawSn 의 비식별 영상 디렉터리 그 자체> 하나뿐이다(CWE-22). 리졸버로 다시
        //    계산한 경로(고정 allowlist + 실경로 검증 통과분)와 정확히 일치할 때만 진행한다.
        Path expected;
        try {
            expected = artifactRootResolver.deidVideoDir(rawSn, rawFilePathNm).normalize();
        } catch (RuntimeException e) {
            log.warn("[KpstDeid] skip export dir cleanup — base rejected rawSn={}", rawSn);
            return;
        }
        if (!normalized.equals(expected)) {
            log.warn("[KpstDeid] skip export dir cleanup — not the deid video dir rawSn={}", rawSn);
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
            // ── Phase C-2: WAITING + prjId null = <b>제출 ACK 대기</b> 구간.
            //
            //  제출이 논블로킹이 되면서 "원장은 커밋됐지만 ACK 는 아직" 인 창이 정상적으로 존재한다.
            //  이 구간에서 진행조회를 부를 수 없고(프로젝트 ID 가 없다), 구 코드처럼 markTimeoutIfExpired
            //  를 부르면 <b>시도 카운터/경과 타임아웃 예산만 헛되이 소모</b>한다.
            //
            //  ① 유예 안이면 아무것도 하지 않는다(외부 호출 0건, 카운터 미소모). 다음 틱에 재평가한다.
            //  ② 유예를 넘기면 ACK 가 영영 오지 않는 건(노드 사망·기록 실패)이므로 <b>폴러가 회수</b>한다
            //     — 별도 스위퍼를 만들지 않는다(폴러가 이미 회수기다. 이중 진실원 금지).
            //  종결은 조건부 UPDATE(WAITING + prjId null)라, 판정에 쓴 엔티티가 stale 이어서 그 사이 ACK 가
            //  도착했더라도 0행 no-op 이다(지각 ACK 를 강등하지 않는다).
            if (withinSubmitAckGrace(procLog)) {
                log.debug("[KpstDeid] skip poll — awaiting submit ack rawSn={}", rawSn);
                return;
            }
            boolean reclaimed = txService.failSubmit(
                    procLogSn, rawSn, ACK_MISSING_CODE, "submit ack not received");
            log.warn("[KpstDeid] submit ack missing — reclaimed rawSn={} applied={}", rawSn, reclaimed);
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
            // REDEIDENT 는 락 해제 포함 종결(영구잠금 방지). 배치 경로는 failPolling('F' 마킹 + terminal)으로
            // 종결하며, 선두 비식별 재시작(배치 재시작)이 잡은 잠금만 그 안에서 한정 해제한다(AC-1135).
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
                deidPathStr = downloadResult(rawSn, procLog.getOrgnlFilePathNm(), ds.fileName());
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
                // B-ISSUE-01 — 무결성 실패가 "쓰기 중"이라면 정상 건이므로 1회 유예 재확인한다(오탐 거부 방지).
                String recovered = recheckAfterGrace(rawSn, procLog.getOrgnlFilePathNm(),
                        ds.fileName(), deidPathStr);
                if (recovered != null) {
                    deidPathStr = recovered;
                }
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
            // R14 — 처리 결과 리포트(얼굴/번호판 검출 집계·처리 시각)를 완료 시점에 1회 조회해
            //   완료 트랜잭션에 함께 실어 커밋한다. 조회 실패는 null 이며 완료 전이를 막지 않는다.
            KpstDeidentReportSummary report = fetchReportQuietly(rawSn, prjId, datasetId);
            try {
                txService.finishDownloadAndComplete(rawSn, procLogSn, datasetId, deidPathStr, report);
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

    // ────────────────────────────── 결과 리포트 (R14) ──────────────────────────────

    /**
     * 처리 결과 리포트 조회 — <b>실패해도 절대 예외를 밖으로 내보내지 않는다</b>. [req: R14]
     *
     * <p>규격 §22.3.7 의 {@code GET /retrieve_report} 를 완료 시점에 <b>1회</b> 호출해, 우리 원장의
     * {@code DE_IDNTF_DATST_ID} 와 같은 {@code dsStatus[].dsId} 행을 찾아 요약으로 옮긴다.
     *
     * <h3>왜 실패를 삼키는가 (Critical)</h3>
     * <p>리포트는 "무엇을 얼마나 가렸나" 라는 <b>부가 정보</b>이고, 비식별 성패의 판정 근거가 아니다.
     * 여기서 예외가 나가면 {@link #pollOne} 의 완료 분기가 터져 {@code DE_IDNTF_YN='Y'} →
     * {@code MARKING_READY} 전이가 막히고, 그 영상은 외부 부가 API 하나 때문에 파이프라인에 고착된다
     * (자동 재비식별 큐가 없어 사람이 손대야 한다). 같은 이유로 {@link #resolveMaskingOptions} 도
     * 설정 조회 실패를 삼킨다.
     *
     * <h3>매칭 실패를 추측으로 메우지 않는다</h3>
     * <p>{@code dsId} 가 맞는 행이 없으면 {@code null} 이다. "프로젝트에 데이터셋이 하나뿐이니 그걸
     * 쓰자" 는 추측은 <b>다른 영상의 검출 집계를 우리 행에 적재</b>할 수 있다. 규격도
     * "완료된 데이터셋이 없는 프로젝트는 결과에서 제외된다" 고 명시하므로 빈 응답은 정상이다.
     *
     * <p>CWE-117/359: 로그에는 식별자(rawSn/prjId/datasetId)와 예외 <b>클래스명</b>만 남긴다 —
     * 외부 응답 본문·파일 경로·파일명은 남기지 않는다.
     *
     * @return 매칭된 리포트 요약, 조회 실패·미매칭이면 {@code null}
     */
    private KpstDeidentReportSummary fetchReportQuietly(Long rawSn, Long prjId, Long datasetId) {
        try {
            KpstReportResponse.DsStatus ds =
                    findReportDataset(kpstClient.retrieveReport(reqUserId, prjId), datasetId);
            if (ds == null) {
                log.warn("[KpstDeid] deident report has no matching dataset — 완료 전이는 계속한다 "
                        + "rawSn={} prjId={} datasetId={}", rawSn, prjId, datasetId);
                return null;
            }
            return KpstDeidentReportSummary.from(ds);
        } catch (RuntimeException e) {
            log.warn("[KpstDeid] deident report retrieval failed — 완료 전이는 계속한다 "
                    + "rawSn={} prjId={} errType={}", rawSn, prjId, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 리포트 응답에서 {@code datasetId} 와 일치하는 데이터셋 행을 찾는다(없으면 {@code null}).
     *
     * <p>리포트의 {@code prjStatus[]} 에는 규격상 {@code prjId} 가 없으므로 프로젝트로 좁히지 않고
     * <b>모든 프로젝트의 데이터셋</b>을 훑어 {@code dsId} 로 매칭한다(요청 자체를 {@code prjId} 로
     * 이미 좁혔다). 어떤 노드가 {@code null} 이어도 예외 없이 통과한다.
     */
    private KpstReportResponse.DsStatus findReportDataset(KpstReportResponse report, Long datasetId) {
        if (report == null || datasetId == null || report.data() == null
                || report.data().prjStatus() == null) {
            return null;
        }
        return report.data().prjStatus().stream()
                .filter(java.util.Objects::nonNull)
                .map(KpstReportResponse.PrjStatus::dsStatus)
                .filter(java.util.Objects::nonNull)
                .flatMap(List::stream)
                .filter(java.util.Objects::nonNull)
                .filter(ds -> datasetId.equals(ds.dsId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 제출 ACK 대기 유예 안인지 — {@code REQ_DT + submit-ack-grace-sec} 가 아직 미래이면 true.
     *
     * <p>{@code REQ_DT} 는 원장 발급(= 제출 직전) 시각이라 "제출 후 경과"의 근사로 정확하다.
     * 유예 설정이 0 이하이거나 {@code REQ_DT} 가 없으면 유예 없음(즉시 회수 대상)으로 본다 —
     * 판정 불가를 대기로 해석하면 죽은 건이 무기한 남는다(fail-closed).
     */
    private boolean withinSubmitAckGrace(LsDeidentProcLog procLog) {
        if (submitAckGraceSec <= 0 || procLog.getReqDt() == null) {
            return false;
        }
        return procLog.getReqDt().plusSeconds(submitAckGraceSec).isAfter(java.time.LocalDateTime.now());
    }

    /**
     * 다운로드 결과가 사용 가능한 비식별 산출물인지 확인 — 불완전/위장 산출물 차단(M-2 / B-ISSUE-01).
     *
     * <p>판정은 {@link DeidentArtifactIntegrity}(정규파일 + 크기 하한 + 컨테이너 시그니처) 단일 지점에
     * 위임한다. 과거 "존재 + >0바이트"만 보던 판정은 18바이트 텍스트 스텁을 비식별 완료로 승인했다.
     */
    private boolean isUsableDeidFile(String deidFilePath) {
        return DeidentArtifactIntegrity.isValidVideoArtifact(deidFilePath);
    }

    /**
     * B-ISSUE-01 — 무결성 실패 시 1회 유예 재확인(오탐 거부 방지).
     *
     * <p>무결성 실패는 terminal 'F' 로 이어지고 자동 재비식별 큐가 없다(외부 수동 재처리). 따라서 "쓰기
     * 중/가시성 지연"과 "진짜 불완전"을 구분해야 한다. <b>후보 파일이 존재하는데 판정만 실패</b>한 경우에만
     * {@code kpst.deid.result-recheck-delay-ms}(단, {@link #MAX_RESULT_RECHECK_DELAY_MS} 로 clamp)
     * 만큼 기다렸다가 회수 경로를 다시 산출해 1회 재판정한다.
     * 파일이 아예 없으면(진짜 미기록) 유예 없이 즉시 종결한다 — 실패 종결이 지연되지 않도록.
     *
     * @return 재확인으로 유효해진 회수 경로, 회복 실패면 {@code null}
     */
    private String recheckAfterGrace(Long rawSn, String orgnlFilePathNm,
                                     String fileNameFromResponse, String candidate) {
        if (resultRecheckDelayMs <= 0 || !fileExists(candidate)) {
            return null;
        }
        // 폴링 워커 점유 상한 — 오설정이 폴링 사이클을 정지시키지 못하게 한다(가시성 지연은 초 단위 현상).
        long delayMs = Math.min(resultRecheckDelayMs, MAX_RESULT_RECHECK_DELAY_MS);
        log.warn("[KpstDeid] deid artifact incomplete on first check — regrace rawSn={} delayMs={}",
                rawSn, delayMs);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        String recomputed;
        try {
            recomputed = downloadResult(rawSn, orgnlFilePathNm, fileNameFromResponse);
        } catch (RuntimeException e) {
            // 재산출 자체가 실패(모호/불량 fileName) — 호출측이 terminal 종결한다.
            return null;
        }
        return isUsableDeidFile(recomputed) ? recomputed : null;
    }

    /** 후보 경로가 (심링크 아닌) 정규 파일로 존재하는지 — 유예 재확인 대상 판정용. */
    private boolean fileExists(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        try {
            return Files.isRegularFile(Paths.get(path), java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (java.nio.file.InvalidPathException e) {
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
    private String downloadResult(Long rawSn, String orgnlFilePathNm, String fileNameFromResponse) {
        String base = sanitizeFileName(fileNameFromResponse);
        // 회수 대상 디렉터리는 <b>2-way</b>다(S6): 신 위치(co-locate export_path) 우선, 배포 전 위탁분이
        // 남아 있을 수 있으므로 구 위치({deid_base}/videos/{rawSn}/)도 폴백으로 훑는다.
        List<Path> dirs = recoveryDirs(rawSn, orgnlFilePathNm);
        Path firstMaskPath = null;
        for (Path dir : dirs) {
            // 1차: {stem}-mask{ext} 산출명 재구성(실측 계약).
            Path maskPath = VideoArtifactRootResolver.resolveUnder(dir, toMaskName(base));
            if (firstMaskPath == null) {
                firstMaskPath = maskPath;
            }
            if (isUsableDeidFile(maskPath.toString())) {
                return maskPath.toString();
            }
            // 폴백: 접미사/확장자 규칙 변화 대비 — 디렉터리 내 단일 산출 영상을 회수(no-copy=1개 기대).
            Path fallback = scanSingleUsable(dir);
            if (fallback != null) {
                // B-ISSUE-84 — 폴백은 안전망일 뿐이다. 1차 경로({stem}-mask{ext})가 빗나갔다는 것은
                // 산출물 명명 계약이 드리프트했다는 신호이므로 조용히 넘기지 않고 운영에서 관측 가능하게 한다.
                // (CWE-209: 파일명/경로 원문 미노출 — rawSn 만.)
                log.warn("[KpstDeid] primary mask path miss — recovered by fallback scan rawSn={}", rawSn);
                return fallback.toString();
            }
        }
        // 0개 — 1차 경로 반환(호출측 isUsableDeidFile 가 false → failPolling 로 깨끗이 종결).
        return firstMaskPath == null ? "" : firstMaskPath.toString();
    }

    /**
     * 회수 후보 디렉터리 — 신 위치(전략에 따른 export_path) + 구 위치({@code {deid_base}/videos/{rawSn}/}).
     * 신 위치 도출이 실패해도(원본 경로 손상 등) 구 위치 회수는 계속 시도한다.
     */
    private List<Path> recoveryDirs(Long rawSn, String orgnlFilePathNm) {
        java.util.LinkedHashSet<Path> dirs = new java.util.LinkedHashSet<>();
        artifactRootResolver.deidVideoDirQuietly(rawSn, orgnlFilePathNm).ifPresent(dirs::add);
        dirs.add(baseDeidentifiedPath.resolve(DIR_VIDEOS).resolve(String.valueOf(rawSn)).normalize());
        return List.copyOf(dirs);
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
            // 회수 대상은 <스캔한 회수 디렉터리 바로 아래> 파일이어야 한다(B-4 — "설정 루트 하위 어디든"
            // 보다 좁은 판정). dir 자체는 recoveryDirs 가 리졸버 검증을 거쳐 만든 경로다.
            if (!only.startsWith(dir.normalize())) {
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

    /**
     * 위탁 프로젝트 이름 — <b>KPST 에 같은 이름을 두 번 보내지 않는다</b>(KPST 는 동일 이름 생성을 409 로 거부).
     *
     * <ul>
     *   <li>그 영상의 첫 위탁: {@code raw{rawSn}} (종전 그대로)</li>
     *   <li>다시 위탁(선두 비식별 재시작 · 검수완료 재비식별 공통): {@code raw{rawSn}r{procLogSn}} —
     *       접미는 이번 회차에 선커밋된 원장 행 번호라 회차마다 다르다.</li>
     * </ul>
     *
     * <p>첫 위탁 판정은 여기 한 곳이다 — 그 영상에 이번 회차보다 <b>앞선 비식별 이력 행이 하나라도</b> 있으면
     * 다시 위탁으로 본다. 요청 종류(REQ_KND_CD)로 거르지 않는다: 제출 이전 실패·비식별 제외·신고 해소·
     * 반입 행처럼 KPST 에 나가지 않은 이력이 있어 접미가 붙어도 불변식은 지켜진다(과잉 접미는 무해,
     * 누락 접미는 409). 이전 프로젝트 삭제는 호출하지 않는다. 문자는 영문·숫자만 쓴다.
     *
     * @design INT-004
     * @design AC-1133
     */
    private String projectName(Long rawSn, Long procLogSn) {
        String base = "raw" + rawSn;
        if (procLogSn != null
                && procLogRepository.existsByDataRawSnAndProcLogSnLessThan(rawSn, procLogSn)) {
            return base + "r" + procLogSn;
        }
        return base;
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
