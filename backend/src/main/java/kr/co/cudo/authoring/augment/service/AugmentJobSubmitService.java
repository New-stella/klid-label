package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
import kr.co.cudo.authoring.augment.integration.AugmentInputFile;
import kr.co.cudo.authoring.augment.integration.AugmentSubmitCommand;
import kr.co.cudo.authoring.augment.integration.ExternalAugmentClient;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.async.SubmitSignalDispatch;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.observability.metrics.AugmentMetrics;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 증강 외부 위탁 분할 실행 서비스 — Phase 7-A1.
 *
 * <p>증강 결과 1건({@code LS_DATA_AUG})에 대해 대상 영상의 <b>비식별 프레임</b> 전량을
 * {@code input_files} 상한(기본 100장) 단위로 쪼개 여러 job 으로 위탁하고, 결과를
 * {@code LS_DATA_AUG_JOB} 에 행으로 남긴다.
 *
 * <h3>핵심 규칙</h3>
 * <ul>
 *   <li><b>PII fail-closed</b>: 비식별 경로가 비어 있는 프레임이 하나라도 있으면 위탁하지 않는다.
 *       원본(비-비식별) 경로로 대체하지 않는다 — 원본 유출은 보안 결함이다. 거부 사유는
 *       {@code LS_DATA_AUG_JOB}(FAILED/{@code DEID_PATH_MISSING})에 남긴다.</li>
 *   <li><b>비식별 누락 신고 게이트</b>(DEV_FIX HIGH-2): 대상 영상이 신고 구간
 *       ({@code LS_DATA_RAW.DE_IDNTF_YN='F'})이면 <b>한 건도 위탁하지 않는다</b>. 신고는 "이 영상의
 *       비식별본에 PII 가 남아 있다" 는 확인이므로, 그 프레임 경로를 외부 벤더에 넘기면 벤더가 공유
 *       NAS 에서 PII 파일을 실제로 읽는다(CWE-359). 콜백 시점의 부모 {@code 'Y'} 게이트
 *       ({@code AugmentResultService})는 <b>이미 유출된 뒤</b>라 이 창을 닫지 못한다.
 *       판정은 새로 만들지 않고 단일 원천 {@link DeidentReportGate} 를 호출한다.</li>
 *   <li><b>전량 선기록 후 위탁</b>(DEV_FIX 2차 MEDIUM-2): <b>모든</b> 청크를 RECEIVED 로 먼저
 *       선기록(멱등키 + 순서↔프레임 대응 확보)한 뒤에 위탁 루프를 돈다. 선기록이 하나라도 실패하면
 *       한 건도 위탁하지 않고 이미 기록된 앞 청크까지 FAILED 로 종결한다 — 그래야 수신부 롤업이
 *       <b>부분 프레임셋을 전량으로 오인해 성공 확정</b>하는 일이 원천적으로 불가능해진다.
 *       202 수신 후 외부 job_id 를 채우고, 실패는 FAILED + 사유로 남긴다(조용한 삼킴 금지).</li>
 *   <li><b>건별 격리</b>: 2번째 청크의 <b>위탁</b>이 실패해도 3번째 청크를 계속 위탁한다. 전체
 *       성공/부분 실패 판정(집계)은 결과 수신부(A2)의 책임이다.</li>
 * </ul>
 *
 * <h3>★ 논블로킹 제출 (Phase C-3) — "외부연동은 모두 비동기" 의 스레드 축</h3>
 * <p>프로토콜은 원래 비동기였으나(결과는 웹훅) <b>202 ACK 왕복 동안 스레드를 점유</b>했다
 * ({@code .block()}). 그 스레드는 {@code AugmentRequestBridge} 의 {@code batchAsyncExecutor}
 * (core 2 · CallerRuns)라, 벤더가 느려지면 배치 풀이 마르고 역압이 커밋 스레드까지 물고 늘어졌다.
 * 게다가 청크가 N 개면 그 점유가 N 배였다. 이제 ACK 도 기다리지 않는다:
 * <ol>
 *   <li><b>선커밋</b> — 청크 job 행 전량({@link #issueAllChunks})을 <b>제출 전에</b> REQUIRES_NEW 로
 *       독립 커밋한다(기존과 동일). ACK·콜백이 먼저 도착해도 기록 대상이 존재한다.</li>
 *   <li><b>직렬 제출</b> — {@code concatMap} 으로 "앞 청크 완료 → 신고 재판정 → 다음 청크" 순서를
 *       <b>그대로 보존</b>한다. 병렬 발사는 금지다({@link #dispatchChunks} 주석).</li>
 *   <li><b>완료 핸들러</b> — 전용 풀({@code augmentSubmitScheduler})에서
 *       {@link AugmentSubmitOutcomeRecorder} 가 ACK(job_id 적재)/실패를 <b>조건부 원자 UPDATE</b> 로
 *       기록한다(지각 신호가 콜백이 올린 상태를 강등하지 못한다).</li>
 *   <li><b>종결 판정</b> — 시퀀스 종료 시 1회 롤업 시도({@link AugmentSubmitRollupTxService}).
 *       구 "{@code accepted==0} 즉시 롤업" 의 이관처다.</li>
 *   <li><b>회수</b> — 아무 신호도 기록되지 않으면 기존 {@code AugmentJobExpirySweeper} 가 비종결 job 을
 *       회수한다. <b>새 스위퍼를 만들지 않는다</b>(이중 진실원 금지).</li>
 * </ol>
 *
 * <p><b>동기 실패 전파가 남는 것은 제출 이전의 사전 조건뿐</b>이다(신고 구간 거부 · 비식별 경로 부재 ·
 * 선기록 실패). 외부에 아무것도 나가지 않은 실패이므로 기존과 동일하게 {@code dispatched=0} 을
 * 반환하고 호출부가 즉시 실패 롤업한다.
 *
 * <p>트랜잭션: 본 서비스는 <b>트랜잭션을 열지 않는다</b>. 조회는 리포지토리 자신의 짧은 트랜잭션,
 * 기록은 {@link AugmentJobRecorder}(REQUIRES_NEW)에 위임한다 — 외부 HTTP 왕복 동안 트랜잭션과
 * 커넥션을 붙잡지 않기 위함이다.
 *
 * <h3>★ 이 클래스에는 트랜잭션 애너테이션을 <b>붙이지 않는다</b> — 커넥션 2중 점유 회피</h3>
 * <p>구 구현은 클래스 레벨 {@code @Transactional(readOnly = true)} 였다. 그러면 그 트랜잭션이 잡은
 * 커넥션 1개를 <b>외부 HTTP 왕복 내내</b> 놓지 않은 채 청크 선기록({@code REQUIRES_NEW})이 같은 풀에서
 * <b>두 번째 커넥션</b>을 요구한다. 즉 위탁 1건이 스레드당 커넥션 2개를 동시 점유한다. 위탁이 2건
 * 겹치면(요청 2회의 AFTER_COMMIT 이 {@code batchAsyncExecutor} 에서 병렬 실행) 두 스레드가 각자
 * 첫 커넥션을 잡고 서로의 두 번째를 기다려 <b>풀 데드락</b>(30s 후
 * {@code HikariPool … request timed out} → {@code CannotCreateTransactionException})이 된다 —
 * 그 30초 동안 같은 풀을 쓰는 무관한 작업까지 전부 대기열에 쌓인다.
 *
 * <h3>{@code NOT_SUPPORTED} — 실측된 사실과 <b>미규명 부분</b>을 구분한다 (2026-07-30)</h3>
 * <p><b>실측(재현됨)</b>: 이 클래스에 {@code @Transactional(propagation = NOT_SUPPORTED)} 를 붙이면
 * <ul>
 *   <li>외부 HTTP 왕복 시점에 {@code TransactionSynchronizationManager.isSynchronizationActive()} 가
 *       {@code true} 다 — {@code AbstractPlatformTransactionManager} 는 {@code NOT_SUPPORTED}(기존
 *       트랜잭션 없음)에서 "empty transaction" 을 만들면서 {@code initSynchronization()} 을 호출한다.
 *       ({@code isActualTransactionActive()} 는 이때 {@code false} 로 남으므로 그 플래그로는 관측되지
 *       않는다 — 회귀 테스트가 이 축을 보는 이유다.)</li>
 *   <li>테스트 풀(2)에서 같은 클래스의 다른 위탁 테스트가 30.08s 만에
 *       {@code CannotCreateTransactionException: Could not open JPA EntityManager for transaction}
 *       (=Hikari 30s 커넥션 획득 타임아웃)으로 실패한다 — 커넥션 고갈이 실제로 재현된다.</li>
 * </ul>
 *
 * <p><b>미규명</b>: 위 고갈의 정확한 인과는 아직 규명되지 않았다. 동기화가 살아 있으면
 * {@code EntityManagerFactoryUtils.doGetTransactionalEntityManager} 가 EM 을 스레드에 바인딩하는 것은
 * 맞지만, Hibernate 기본 커넥션 정책({@code DELAYED_ACQUISITION_AND_RELEASE_AFTER_TRANSACTION})에서는
 * 내부 리포지토리 트랜잭션이 커밋될 때 <b>물리 커넥션은 반납</b>된다. 즉 "EM 이 바인딩된다" 만으로는
 * 커넥션 점유가 설명되지 않는다. 따라서 위 관측은 <b>현상 기록</b>이고 기전 설명은 아니다.
 *
 * <p><b>{@code NOT_SUPPORTED} 가 애너테이션 부재보다 강한 국면도 있다</b>: {@code NOT_SUPPORTED} 는 기존
 * 트랜잭션을 <b>suspend(리소스 언바인딩)</b> 하므로, 아래 CallerRunsPolicy 재진입 경로에서 상위
 * 트랜잭션 참여를 원천 차단한다. 애너테이션이 없으면 suspend 자체가 없어 <b>무조건 참여</b>한다.
 * 그러므로 이 형태를 금지 사항으로 못 박지 않는다 — <b>이 형태로 되돌릴 때는 반드시 위 두 관측
 * (동기화 활성 / 커넥션 고갈)을 재측정</b>하고, 고갈이 재현되지 않는 근거를 남긴 뒤에 바꾼다.
 *
 * <p>현재는 <b>애너테이션을 두지 않는다</b>. 동기화가 없으면 조회는 Spring Data 리포지토리가
 * 자기 짧은 트랜잭션으로 처리하고 <b>즉시 커넥션을 반납</b>하며, 기록은 {@link AugmentJobRecorder}
 * ({@code REQUIRES_NEW})가 한 건씩 순차로 잡는다. 회귀 고정:
 * {@code AugmentRequestServiceTest.외부_위탁_HTTP_왕복중에는_트랜잭션_동기화와_EntityManager를_잡지_않는다}.
 *
 * <h3>"스레드당 커넥션 1개" 의 <b>성립 조건</b> — 무조건 명제가 아니다</h3>
 * <p>위 결론("어느 순간에도 스레드당 커넥션 1개")은 <b>{@code submit()} 호출 스택에 활성 트랜잭션·
 * 트랜잭션 동기화가 없을 때만</b> 성립한다. 상위 스코프가 이미 열려 있으면 리포지토리의
 * {@code REQUIRED} 트랜잭션이 그 스코프에 <b>참여</b>하므로 커넥션이 그 스코프 종료까지 붙잡힌다.
 *
 * <p><b>실제로 그런 경로가 하나 있다(알려진 잔여 위험 — 이번 변경의 회귀는 아니다)</b>:
 * {@code AugmentRequestBridge.onAugmentRequested} 는 {@code @Async("batchAsyncExecutor")} +
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 이고 {@code batchAsyncExecutor} 는
 * {@code CallerRunsPolicy}(core 2 / max 4 / queue 50)다. 큐가 포화되면 리스너가 <b>AFTER_COMMIT 콜백
 * 스레드에서 동기 실행</b>되는데, 그 시점은 {@code triggerAfterCommit()} 이 {@code cleanupAfterCompletion()}
 * <b>이전</b>이라 원 트랜잭션의 리소스가 아직 스레드에 바인딩돼 있다 → 리포지토리 트랜잭션이 그
 * 트랜잭션에 참여해 커넥션이 콜백 반환까지 유지된다. 해소에는 구조 변경(리스너가 {@code REQUIRES_NEW}
 * 진입점을 통해서만 리포지토리를 호출하도록 등)이 필요하므로 <b>별도 이슈</b>로 두고, 여기서는
 * 사실만 기록한다(코드 미변경).
 */
@Slf4j
@Service
public class AugmentJobSubmitService {

    /** 명세서 §4.1 input_files 상한. 설정으로 낮출 수는 있어도 계약 상한을 넘길 수 없다. */
    private static final int CONTRACT_MAX_INPUT_FILES = 100;

    private final LsDataSrcRepository srcRepository;
    private final AugmentJobRecorder jobRecorder;
    private final ExternalAugmentClient externalClient;
    private final AugmentMetrics metrics;
    /** 비식별 누락 신고 구간 판정 — 단일 원천(자체 재구현 금지). */
    private final DeidentReportGate deidentReportGate;
    /** 비동기 제출 완료 기록 + 종결 판정 — 트랜잭션은 이 빈이 REQUIRES_NEW 로 위임한다. */
    private final AugmentSubmitOutcomeRecorder outcomeRecorder;
    /** 완료 신호·다음 청크 실행 전용 풀({@code augmentSubmitExecutor}) — 이벤트 루프 블로킹 차단. */
    private final Scheduler submitScheduler;
    private final int maxInputFiles;

    public AugmentJobSubmitService(LsDataSrcRepository srcRepository,
                                   AugmentJobRecorder jobRecorder,
                                   ExternalAugmentClient externalClient,
                                   AugmentMetrics metrics,
                                   DeidentReportGate deidentReportGate,
                                   AugmentSubmitOutcomeRecorder outcomeRecorder,
                                   @Qualifier("augmentSubmitScheduler") Scheduler submitScheduler,
                                   @Value("${authoring.augment.external.max-input-files:100}")
                                   int maxInputFiles) {
        this.srcRepository = srcRepository;
        this.jobRecorder = jobRecorder;
        this.externalClient = externalClient;
        this.metrics = metrics;
        this.deidentReportGate = deidentReportGate;
        this.outcomeRecorder = outcomeRecorder;
        this.submitScheduler = submitScheduler;
        this.maxInputFiles = clampChunkSize(maxInputFiles);
    }

    private static int clampChunkSize(int configured) {
        if (configured < 1) {
            return CONTRACT_MAX_INPUT_FILES;
        }
        return Math.min(configured, CONTRACT_MAX_INPUT_FILES);
    }

    /**
     * 증강 요청 1건을 청크 단위로 외부 위탁한다.
     *
     * <p><b>전송 진입점 단일 fail-closed</b> — 비식별 누락 신고 구간이면 여기서 전량 거부한다. 게이트를
     * 호출처마다 배선하면 반드시 새므로(Phase 6 교훈), 판정은 {@link DeidentReportGate} 하나에 두고
     * 차단은 <b>실제로 경로가 밖으로 나가는 이 메서드</b> 한 곳에서 한다.
     *
     * <p><b>차단은 보류가 아니라 거부다 (2026-07-29 정책)</b> — 파생 생성이 원본 신고와 무관해지면서
     * 신고 해소 시의 증강 재개 배선이 철회됐다. 재개 트리거가 없는 보류는 아무도 깨우지 못하는
     * PENDING 고착이므로 사유를 남기고 실패로 종결한다(해소 후 재요청이 정상 동선).
     *
     * <p><b>트랜잭션 애너테이션 없음</b> — 클래스 주석 "커넥션 2중 점유 회피" 참조. 조회는 리포지토리별
     * 짧은 트랜잭션, 기록은 {@link AugmentJobRecorder}({@code REQUIRES_NEW})가 각각 자기 커넥션을 잡고
     * 즉시 반납한다(단, 상위 스코프가 열려 있지 않을 때 — 클래스 주석 "성립 조건" 참조). 이 메서드
     * (또는 클래스)에 {@code @Transactional(readOnly)} 를 씌우면 위탁 동시 2건에서 커넥션 풀 데드락이
     * 재발한다(실측). {@code NOT_SUPPORTED} 도 커넥션 고갈이 실측 재현됐으나 인과가 미규명이므로,
     * 되돌리려면 클래스 주석의 두 관측을 <b>재측정</b>하고 근거를 남긴다.
     *
     * @return 위탁 결과 — 수락 job 수
     */
    public SubmitOutcome submit(AugmentRequestedItemEvent event) {
        // PII 게이트를 <b>가장 먼저</b> 둔다 — 프레임 경로 조회조차 하기 전에 끊는다.
        if (deidentReportGate.isUnderDeidentReport(event.rawSn())) {
            jobRecorder.recordRejected(event.originAugSn(), event.idempotencyKey(),
                    LsDataAugJob.ERR_DEID_REPORT_OPEN,
                    "비식별 누락 신고 구간이라 외부 위탁을 거부했습니다. 신고 해소 후 다시 요청하세요.");
            metrics.externalRequestFailure();
            log.warn("[Augment] 위탁 거부 — 비식별 누락 신고 구간 originAugSn={} rawSn={}",
                    event.originAugSn(), event.rawSn());
            return SubmitOutcome.of(0);
        }

        List<FrameInput> inputs;
        try {
            inputs = resolveDeidInputFiles(event.rawSn());
        } catch (DeidPathMissingException e) {
            // PII fail-closed — 원본 경로로 대체하지 않고 거부 사유만 남긴다.
            jobRecorder.recordRejected(event.originAugSn(), event.idempotencyKey(),
                    LsDataAugJob.ERR_DEID_PATH_MISSING, e.getMessage());
            metrics.externalRequestFailure();
            log.warn("[Augment] 위탁 거부 — 비식별 프레임 경로 부재 originAugSn={} rawSn={} missingCount={}",
                    event.originAugSn(), event.rawSn(), e.missingCount());
            return SubmitOutcome.of(0);
        }

        List<List<FrameInput>> chunks = partition(inputs);

        // 전량 선기록 — <위탁 전에> 기대 job 집합을 완성한다(DEV_FIX 2차 MEDIUM-2).
        Optional<List<Long>> issued = issueAllChunks(event, chunks);
        if (issued.isEmpty()) {
            // 선기록 단계에서 끊었으므로 외부로 나간 청크는 없다 → 개시 0건(호출부가 실패 롤업).
            return SubmitOutcome.of(0);
        }
        List<Long> augJobSns = issued.get();

        dispatchChunks(event, chunks, augJobSns);
        log.info("[Augment] 위탁 개시 originAugSn={} rawSn={} jobCount={}",
                event.originAugSn(), event.rawSn(), chunks.size());
        return SubmitOutcome.of(chunks.size());
    }

    /**
     * 청크 <b>직렬</b> 논블로킹 위탁 개시 — 구독만 하고 즉시 반환한다 (Phase C-3).
     *
     * <h3>★ 병렬 발사 금지 — {@code concatMap} 으로 직렬화한다</h3>
     * <p>청크 사이에는 비식별 누락 신고 <b>재판정</b>이 있다(아래 {@link #submitChunkAsync}). 위탁 도중
     * 신고가 커밋되면 남은 청크의 PII 경로 전송을 끊는 방어인데, 청크를 병렬로 발사하면 그 방어가
     * 통째로 무력화된다("앞 청크 완료 → 재판정 → 다음 청크" 순서가 깨지므로). 벤더 rate 측면에서도
     * 100장×N 을 동시에 던지지 않아야 한다. 그래서 {@code flatMap} 이 아니라 {@code concatMap} 이다.
     *
     * <h3>실행 스레드</h3>
     * <p>첫 청크의 조립은 호출 스레드(브리지의 {@code batch-async-})에서 일어나지만 <b>블로킹은 없다</b>.
     * 두 번째 청크부터는 앞 청크의 {@code publishOn(augmentSubmitScheduler)} 덕에 전용 풀 스레드에서
     * 재판정·제출이 실행된다 — 신고 재판정(DB 조회)이 reactor-netty 이벤트 루프에서 돌지 않는다.
     *
     * <h3>종결 판정</h3>
     * <p>어떤 경로로 끝나든({@code doFinally}) 시퀀스 종료를 핸들러에 알린다. 구 동기 구현의
     * "{@code accepted==0} 이면 즉시 실패 롤업" 이 여기로 이관됐다 —
     * {@link AugmentSubmitRollupTxService} 주석 참조.
     */
    private void dispatchChunks(AugmentRequestedItemEvent event,
                                List<List<FrameInput>> chunks, List<Long> augJobSns) {
        int jobCount = chunks.size();
        try {
            Flux.range(0, jobCount)
                    .concatMap(i -> submitChunkAsync(
                            event, chunks.get(i), augJobSns, i, jobCount))
                    .then()
                    // 중단 신호는 오류가 아니다 — 남은 청크 종결 기록은 이미 끝났고 정상 완료로 흡수한다.
                    .onErrorResume(SubmitAbortedException.class, e -> Mono.empty())
                    // 종결 판정(JPA)도 전용 풀에서만 실행한다 — publishOn 이 거부된 경우 이 콜백은
                    // reactor-netty 이벤트 루프에서 실행되기 때문이다(M2). 풀이 거부하면 기록을
                    // 포기하고 기존 만료 스윕(AugmentJobExpirySweeper)이 비종결 job 을 회수한다.
                    .doFinally(signal -> SubmitSignalDispatch.run(submitScheduler, "Augment",
                            event.originAugSn(),
                            () -> outcomeRecorder.onSubmitSequenceFinished(event.originAugSn())))
                    .subscribe(ignored -> { },
                            err -> log.error("[Augment] 위탁 시퀀스 비정상 종료 originAugSn={} errType={}",
                                    event.originAugSn(), err.getClass().getSimpleName()));
        } catch (RuntimeException e) {
            // 여기 도달하는 것은 <b>동기 구독 구간</b>의 실패뿐이다(첫 청크 조립·구독이 즉시 throw).
            // 전용 풀 포화로 인한 publishOn 거부는 응답 도착 후에 발생하므로 이 catch 로는 잡히지
            // 않는다 — 그 경로는 submitChunkAsync 의 onErrorResume 이 (이벤트 루프에서 JPA 를 돌리지
            // 않도록) 별도로 처리한다. 선기록 job 은 durable 하므로 만료 스윕이 회수한다. 여기서 예외를
            // 위로 던지면 호출부가 "위탁 0건" 으로 오판해 실제 나간 청크가 있는데도 증강을 REJECTED 로
            // 못박을 수 있다.
            metrics.externalRequestFailure();
            log.error("[Augment] 위탁 구독 거부 originAugSn={} errType={}",
                    event.originAugSn(), e.getClass().getSimpleName());
            outcomeRecorder.onSubmitSequenceFinished(event.originAugSn());
        }
    }

    /**
     * 청크 1건의 비동기 위탁 — <b>신고 재판정 → 제출 → 결과 기록</b>.
     *
     * <p>재판정을 {@code Mono.defer} 안에 두는 것이 핵심이다. 조립 시점이 아니라 <b>앞 청크가 끝난
     * 뒤 구독 시점</b>에 평가돼야 "위탁 도중 신고" 를 관측할 수 있다(조립 시점에 평가하면 전 청크가
     * 같은 순간의 판정을 공유해 방어가 사라진다).
     *
     * <p>실패는 {@code onErrorResume} 으로 흡수해 <b>다음 청크를 계속</b> 위탁한다(건별 격리 —
     * 기존 동기 계약과 동일). 사유는 핸들러가 DB 에 남긴다(조용한 삼킴 금지).
     */
    private Mono<Void> submitChunkAsync(AugmentRequestedItemEvent event,
                                        List<FrameInput> chunk, List<Long> augJobSns,
                                        int index, int jobCount) {
        return Mono.defer(() -> {
            // 청크마다 재판정한다 — 250장 위탁은 수 초~수십 초 걸리고, 그 사이 신고가 커밋되면 남은
            // 청크의 PII 경로 전송을 막을 수 있다(전송 단위가 청크이므로 여기서 끊는 것이 유효하다).
            // 잠금(FOR UPDATE)은 쓰지 않는다 — 외부 왕복 전체를 한 트랜잭션으로 묶어 신고 자체를
            // 블록하게 되므로, 여기서는 무잠금 판정으로 남은 노출량만 줄인다.
            if (index > 0 && deidentReportGate.isUnderDeidentReport(event.rawSn())) {
                abortRemainingChunks(event, augJobSns, index, jobCount);
                return Mono.error(new SubmitAbortedException());
            }
            int jobSeq = index + 1;
            Long augJobSn = augJobSns.get(index);
            // 생성 조건·자유 지시문은 <요청 시점에 확정된 값>을 그대로 나른다 — 여기서 영상을 다시
            // 읽어 재조립하면 적재 원문과 나간 값이 두 벌이 되어 갈라진다.
            // 이벤트 유형은 나르지 않는다 — 요청자가 고르지 않고 클라이언트가 위탁 바디를 만들 때
            // 서버 중립값(GenAiJobSubmitRequest.EVENT_TYPE_ETC)을 고정으로 채운다.
            // [design: INT-008] [design: ADR-059]
            AugmentSubmitCommand command = new AugmentSubmitCommand(
                    event.originAugSn(), event.augType(), event.mtdt(), event.promptText(),
                    chunkRequestId(event.idempotencyKey(), jobSeq),
                    event.requestUserNo(), event.callbackUrl(),
                    chunk.stream().map(FrameInput::toInputFile).toList(), jobSeq, jobCount);
            return externalClient.requestAugment(command)
                    // 빈 응답(onComplete only)은 어느 핸들러도 타지 않으므로 실패로 승격한다.
                    .switchIfEmpty(Mono.error(() -> new CustomException(
                            ErrorCode.EXTERNAL_API_ERROR, "생성형AI 위탁 응답이 비어있습니다.")))
                    // 완료 신호를 전용 풀로 옮긴다 — 기록(JPA)과 다음 청크의 신고 재판정이
                    // reactor-netty 이벤트 루프에서 실행되면 모든 외부 호출이 동반 지연된다.
                    .publishOn(submitScheduler)
                    .doOnNext(result -> outcomeRecorder.onAccepted(
                            event.originAugSn(), augJobSn, result.externalJobId(), jobSeq, jobCount))
                    .onErrorResume(err -> {
                        // ★ 전용 풀 거부(publishOn 스케줄 거부)는 <b>이벤트 루프</b>에서 흐른다 (M2).
                        //  여기서 기록(JPA)을 하면 이벤트 루프가 커넥션 대기에 묶여 모든 외부 호출이
                        //  동반 지연되고, 이어서 concatMap 이 다음 청크를 같은 스레드에서 구독해
                        //  신고 재판정(DB 조회)까지 루프에서 돈다. 그래서 기록도 다음 청크도 하지 않고
                        //  시퀀스를 중단한다 — 선기록 job 은 비종결로 남아 만료 스윕이 회수하고,
                        //  롤업은 "부분 실패 = 전체 실패" 로 끝낸다(부분 프레임셋 성공 확정 불가).
                        if (SubmitSignalDispatch.isPoolRejection(err)) {
                            log.error("[Augment] 완료 신호 전용 풀 포화 — 남은 청크 중단 originAugSn={} jobSeq={}/{}",
                                    event.originAugSn(), jobSeq, jobCount);
                            return Mono.error(new SubmitAbortedException());
                        }
                        outcomeRecorder.onSubmitFailed(
                                event.originAugSn(), augJobSn, jobSeq, jobCount, err);
                        return Mono.empty();
                    })
                    .then();
        });
    }

    /**
     * <b>전 청크 선기록</b>(위탁 전) — 멱등키 + 순서↔프레임 대응을 청크 수만큼 확보한다.
     *
     * <h3>왜 위탁 루프와 분리했나 (DEV_FIX 2차 MEDIUM-2)</h3>
     * <p>구 구현은 "청크마다 선기록 → 즉시 위탁" 이었다. 그래서 2번째 청크의 선기록이 실패하면
     * (예: 원 AFTER_COMMIT 경로와 신고 해제 재개 경로가 같은 멱등키로 동시 진입 → {@code IDMP_KEY}
     * UNIQUE 충돌) <b>그 청크의 job 행이 아예 생기지 않은 채</b> 1·3번 청크만 위탁됐다. 수신부 롤업은
     * "기대 job 수" 를 갖고 있지 않으므로 <b>존재하는 행만 보고 전부 SUCCEEDED</b> 라고 판정해 증강을
     * 성공 확정했다 — 프레임이 빠진 산출물이 ACCEPTED 로 남는 정합 붕괴다.
     *
     * <p>선기록을 전량 먼저 하면 실패가 <b>외부 위탁 전에</b> 드러나므로 전체를 실패로 끝낼 수 있고,
     * 롤업이 대조할 "기대 job 수" 컬럼(스키마 추가)이 필요 없다. 이미 선기록된 앞 청크는
     * {@link LsDataAugJob#ERR_ISSUE_RECORD_FAILED} 로 terminal 종결시켜 <b>비종결 고아 행</b>도 남기지 않는다.
     *
     * @return 청크 순서대로의 {@code AUG_JOB_SN} 목록. 선기록이 하나라도 실패하면 {@link Optional#empty()}
     */
    private Optional<List<Long>> issueAllChunks(AugmentRequestedItemEvent event,
                                                List<List<FrameInput>> chunks) {
        List<Long> augJobSns = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            int jobSeq = i + 1;
            try {
                augJobSns.add(jobRecorder.recordIssued(
                        event.originAugSn(), jobSeq, chunkRequestId(event.idempotencyKey(), jobSeq),
                        chunks.get(i).stream().map(FrameInput::toRef).toList()));
            } catch (Exception e) {
                metrics.externalRequestFailure();
                log.warn("[Augment] job 선기록 실패 — 전 청크 위탁 중단 originAugSn={} jobSeq={}/{} err={}",
                        event.originAugSn(), jobSeq, chunks.size(), sanitize(e.getMessage()));
                failIssuedJobs(augJobSns, LsDataAugJob.ERR_ISSUE_RECORD_FAILED,
                        "선기록 실패로 위탁 전 전체를 중단했습니다. failedJobSeq=" + jobSeq);
                return Optional.empty();
            }
        }
        return Optional.of(augJobSns);
    }

    /**
     * 위탁 <b>도중</b> 신고가 관측돼 남은 청크를 끊었을 때의 종결 기록.
     *
     * <p>이미 나간 청크는 되돌릴 수 없고 프레임셋이 불완전하므로, 이 증강 1건은 성공으로 확정돼선
     * 안 된다. <b>선기록만 되고 위탁되지 않은</b> 남은 job 행을 terminal FAILED 로 종결해 롤업이
     * "부분 실패 = 전체 실패" 규칙으로 끝내게 한다(위탁 <b>전</b> 보류와 달리 여기서는 실패가 맞다).
     * 이 행들을 그냥 두면 비종결이라 롤업이 영원히 보류된다.
     *
     * @param fromIndex 중단 시작 청크의 0-base 인덱스(= 위탁된 마지막 청크 다음)
     */
    private void abortRemainingChunks(AugmentRequestedItemEvent event, List<Long> augJobSns,
                                      int fromIndex, int jobCount) {
        metrics.externalRequestFailure();
        failIssuedJobs(augJobSns.subList(fromIndex, augJobSns.size()),
                LsDataAugJob.ERR_DEIDENT_REPORT,
                "위탁 중 비식별 누락 신고가 확인되어 남은 청크를 중단했습니다. abortedFromJobSeq="
                        + (fromIndex + 1));
        log.warn("[Augment] 위탁 중단 — 비식별 누락 신고 관측 originAugSn={} rawSn={} abortedFrom={}/{}",
                event.originAugSn(), event.rawSn(), fromIndex + 1, jobCount);
    }

    /** 선기록된 job 들을 terminal FAILED 로 종결한다(건별 격리 — 한 건 실패가 나머지를 막지 않는다). */
    private void failIssuedJobs(List<Long> augJobSns, String errorCode, String reason) {
        for (Long augJobSn : augJobSns) {
            try {
                jobRecorder.markFailed(augJobSn, errorCode, reason);
            } catch (Exception e) {
                log.error("[Augment] 선기록 job 종결 실패 — 비종결 행 잔존 가능 augJobSn={} err={}",
                        augJobSn, sanitize(e.getMessage()));
            }
        }
    }

    /**
     * 위탁 결과 — <b>제출을 개시한</b> job 개수 (Phase C-3 로 의미가 바뀌었다).
     *
     * <h3>구 의미("202 수락 건수")를 유지할 수 없는 이유</h3>
     * <p>제출이 논블로킹이 되면 반환 시점에는 수락 여부를 알 수 없다. 억지로 알려면 ACK 를 기다려야
     * 하는데 그것이 바로 제거 대상이었다. 그래서 이 값은 "외부로 나가기 시작한 청크 수" 이고,
     * <b>실제 수락/실패 집계와 종결 판정은 완료 핸들러</b>({@link AugmentSubmitOutcomeRecorder})<b>가
     * {@code LS_DATA_AUG_JOB} 에 누적한 뒤</b> 수행한다.
     *
     * <p>따라서 {@code dispatched==0} 은 이제 "제출 <b>전에</b> 거부됐다"(신고 구간·비식별 경로 부재·
     * 선기록 실패)는 뜻이며, 이 경우에만 호출부가 즉시 실패 롤업한다 — 외부로 나간 것이 없으므로
     * 그 판정이 안전하다. 개시된 뒤의 전 청크 실패는 핸들러의 시퀀스 종료 롤업이 처리한다.
     *
     * @param dispatched 제출을 개시한 job 개수
     */
    public record SubmitOutcome(int dispatched) {

        static SubmitOutcome of(int dispatched) {
            return new SubmitOutcome(dispatched);
        }

        /** 외부로 한 건도 나가지 않았는가(제출 전 거부) — 호출부의 즉시 실패 롤업 판정. */
        public boolean requiresFailureRollup() {
            return dispatched == 0;
        }
    }

    /** 위탁 도중 신고 관측으로 남은 청크를 끊는 내부 신호 — 오류가 아니라 <b>정상 중단</b>이다. */
    private static final class SubmitAbortedException extends RuntimeException {

        private SubmitAbortedException() {
            // 스택트레이스 미수집 — 제어 흐름 신호라 비용만 든다.
            super(null, null, false, false);
        }
    }

    /**
     * 대상 영상의 <b>비식별</b> 프레임 경로를 순서대로 만든다. 각 항목은 외부로 나갈 입력 파일과
     * 내부 대응(프레임 {@code srcSn})을 함께 들고 다닌다 — 결과를 정확한 프레임에 되붙이기 위함이다.
     *
     * @throws DeidPathMissingException 프레임이 없거나 비식별 경로가 빈 프레임이 하나라도 있을 때
     */
    private List<FrameInput> resolveDeidInputFiles(Long rawSn) {
        List<Object[]> rows = srcRepository.findDeidFramePathsByRawSn(rawSn);
        if (rows.isEmpty()) {
            throw new DeidPathMissingException("증강 대상 영상에 프레임이 없습니다.", 0);
        }
        List<FrameInput> files = new ArrayList<>(rows.size());
        int missing = 0;
        int sequence = 1;
        for (Object[] row : rows) {
            Long srcSn = (Long) row[0];
            String deidPath = (String) row[1];
            if (deidPath == null || deidPath.isBlank()) {
                missing++;
                continue;
            }
            files.add(new FrameInput(sequence++, srcSn, deidPath));
        }
        if (missing > 0) {
            throw new DeidPathMissingException(
                    "비식별 프레임 경로가 없는 프레임이 있어 외부 위탁을 거부합니다. missingCount=" + missing,
                    missing);
        }
        return files;
    }

    private List<List<FrameInput>> partition(List<FrameInput> inputs) {
        List<List<FrameInput>> chunks = new ArrayList<>();
        for (int start = 0; start < inputs.size(); start += maxInputFiles) {
            chunks.add(List.copyOf(inputs.subList(start, Math.min(start + maxInputFiles, inputs.size()))));
        }
        return chunks;
    }

    /**
     * 청크별 request_id — aug 단위 멱등 키에 청크 순서를 덧붙인다.
     * 형식({@code ^[A-Za-z0-9_-]+$}) 과 길이(≤64) 를 모두 유지한다.
     */
    static String chunkRequestId(String augIdempotencyKey, int jobSeq) {
        return augIdempotencyKey + "-" + jobSeq;
    }

    /** Log Injection (CWE-117) 방어 — CR/LF 제거. 절대경로는 애초에 로그에 싣지 않는다. */
    private static String sanitize(String value) {
        if (value == null) return null;
        return value.replace('\n', '_').replace('\r', '_');
    }

    /**
     * 위탁 입력 1건 — 외부로 나갈 {@link AugmentInputFile} 과 내부 대응({@code srcSn})을 함께 든다.
     * {@code srcSn} 은 외부 페이로드에 절대 싣지 않는다(내부 식별자 노출 금지).
     */
    private record FrameInput(int sequence, Long srcSn, String deidPath) {

        AugmentInputFile toInputFile() {
            return new AugmentInputFile(sequence, deidPath);
        }

        AugmentJobFileRef toRef() {
            return new AugmentJobFileRef(sequence, srcSn);
        }
    }

    /** 비식별 경로 부재 — 위탁 거부 신호(내부 전용). */
    private static final class DeidPathMissingException extends RuntimeException {
        private final int missingCount;

        private DeidPathMissingException(String message, int missingCount) {
            super(message);
            this.missingCount = missingCount;
        }

        private int missingCount() {
            return missingCount;
        }
    }
}
