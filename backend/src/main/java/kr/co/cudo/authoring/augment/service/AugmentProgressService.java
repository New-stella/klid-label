package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.dto.AugmentProgressResponse;
import kr.co.cudo.authoring.augment.dto.AugmentProgressUnavailableReason;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.integration.ExternalAugmentClient;
import kr.co.cudo.authoring.augment.integration.dto.GenAiJobStatusResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 증강 진행상태 조회 — {@code GET /v1/augments/{id}/progress} (FE 폴링 대상).
 *
 * <h3>트랜잭션을 열지 않는다 (커넥션 점유 차단)</h3>
 * <p>이 경로는 외부 HTTP 왕복(§4.4 상태조회, 필요 시 §4.5 결과조회)을 동반한다. 여기에
 * {@code @Transactional} 을 걸면 커넥션 1개를 외부 왕복 내내 붙잡아, 폴링(화면 수 × 주기)만으로
 * 풀이 마른다. DB 접근은 {@link AugmentSnapshotLoader}(짧은 readOnly 트랜잭션)와
 * {@link AugmentJobRecoveryTxService}(회수 반영)에만 있다.
 *
 * <h3>degrade 원칙 — 외부 장애가 화면을 깨지 않는다 (S6)</h3>
 * <p>외부 조회가 실패해도 <b>200 + {@code progress:null} + 사유</b>로 회신한다. 사유는
 * {@link AugmentProgressUnavailableReason} 의 값으로 구분되어 "미연동" 과 "진짜 장애" 와 "우리 자체
 * 상한" 이 섞이지 않는다.
 *
 * <h3>★ 외부 호출 상한은 <b>시간 예산</b>이다 (DEV_FIX HIGH-1, CWE-770/400)</h3>
 * <p>구 구현은 건수 상한({@value #MAX_STATUS_QUERIES})만 뒀는데 그것은 <b>시간 예산이 아니다</b>.
 * 비종결 청크 10개면 상태조회 10회 × 블로킹 상한(15s) = 150초, 벤더가 SUCCEEDED 를 보고하면 청크마다
 * 결과조회가 더해져 최대 300초 동안 서블릿 스레드 하나가 묶였다(재시도까지 세면 인바운드 GET 1회가
 * 아웃바운드 최대 40 요청). Tomcat 기본 200 스레드에서 동시 폴링 200건이면 전 API 스레드가 고갈된다.
 * 게다가 <b>14초에 성공하는 느린 벤더</b>는 실패로 집계되지 않아 서킷이 영원히 열리지 않는다.
 *
 * <p>그래서 <b>요청 단위 데드라인</b>({@link AugmentRequestBudget}, 기본
 * {@value #DEFAULT_REQUEST_BUDGET_SECONDS}초)을 두고 상태조회·결과회수가 <b>같은 예산</b>을 나눠 쓴다.
 * 예산이 마르면 남은 호출을 개시하지 않고 degrade 한다. 속도 제한(RateLimiter) 도입이 아니라
 * <b>자기 방어</b>다(그 결정과 충돌하지 않는다 — {@code AugmentRequestBudget} 주석 참조).
 *
 * <h3>자체 상한은 벤더 장애와 <b>다른 사유</b>로 표기한다 (DEV_FIX HIGH-2)</h3>
 * <p>건수 상한 초과·예산 소진은 우리 쪽 사정이지 벤더 장애가 아니다. 이를 {@code TRANSIENT_ERROR}
 * ("일시적")로 표기하면 초대형 영상의 <b>정상 상황</b>과 실제 서킷 open 이 같은 알람으로 섞인다.
 * {@link AugmentProgressUnavailableReason#QUERY_LIMIT_EXCEEDED} 로 분리한다.
 *
 * <h3>상한을 넘어도 <b>결과회수는 시도</b>한다 (DEV_FIX HIGH-2)</h3>
 * <p>구 구현은 {@code pending > 상한} 이면 즉시 return 해서 회수 블록에 <b>도달조차 못 했다</b>.
 * 그 결과 청크 11개 이상인 증강은 웹훅이 유실되면 영원히 회수되지 못하고 6시간 뒤 만료 스윕이
 * FAILED 로 폐기 — <b>벤더가 이미 만든 산출물이 유실</b>됐다. 지금은 상한 안에서 조회한 청크에 대해
 * 회수를 시도한다(진행률만 degrade).
 *
 * <h3>조회 창은 <b>폴링마다 회전</b>한다 (DEV_FIX 2차 MED-1)</h3>
 * <p>위 조치 후에도 상한을 <b>앞에서부터</b> 잘랐기 때문에, 앞 10개가 비종결로 머무는 동안 11번째
 * 이후 청크는 어떤 폴링에서도 조회되지 않았다(목록이 {@code JOB_SEQ} 오름차순 고정이라 폴링을 N 회
 * 반복해도 같은 앞 10개다 — 구 주석의 "남은 청크는 다음 폴링에서 처리된다" 는 <b>사실이 아니었다</b>).
 * 결국 같은 피해(벤더 산출물이 만료 스윕에서 폐기)가 남아 있었다. 지금은
 * {@link AugmentStatusWindowRotator} 가 창을 회전시켜 <b>모든 비종결 청크가 최대
 * {@code ceil(비종결 수 / 상한)} 번의 폴링 안에</b> 조회된다.
 *
 * <h3>한 청크의 실패가 형제 청크를 굶기지 않는다 (DEV_FIX 2차 MED-2)</h3>
 * <p>구 구현은 조회 실패 <b>첫 건</b>에서 루프 전체를 return 했다. 그래서 벤더가 계약을 어긴 청크
 * 하나(예: 상한 초과 {@code error_code})가 있으면 <b>뒤따르는 모든 청크</b>의 상태조회·회수가 매
 * 폴링마다 결정적으로 차단됐다 — 위반이 고쳐지지 않는 한 영원히 재현된다. 지금은 실패한 청크만
 * degrade 하고 루프는 계속한다(계약 검증 강도는 그대로 유지 — 바뀐 것은 <b>실패의 전파 범위</b>다).
 */
@Slf4j
@Service
public class AugmentProgressService {

    /**
     * 폴링 1회가 개시할 수 있는 외부 상태조회 최대 건수 = <b>회전 창의 크기</b>.
     *
     * <p>정상 형상에서 증강 1건의 청크 수는 (프레임 수 / 100) 이라 대부분 한 자릿수다. 이 상한에
     * 걸리는 것은 초대형 영상뿐이며, 그 경우 진행률 대신 "자체 상한" 사유가 표시된다.
     *
     * <p><b>건수 상한은 진행률 왜곡 방지용</b>이다 — 일부만 조회하고 나머지를 0% 로 채우면 진행률이
     * 실제보다 낮게 보인다(거짓 값보다 "모름" 이 안전하다). 스레드 점유 방어는 시간 예산이 맡는다.
     *
     * <p>상한을 넘는 청크는 <b>버려지는 것이 아니라 다음 폴링의 창</b>으로 넘어간다
     * ({@link AugmentStatusWindowRotator} — DEV_FIX 2차 MED-1).
     */
    static final int MAX_STATUS_QUERIES = 10;

    /**
     * 요청 1회의 외부 호출 wall-clock 예산(초) 기본값.
     *
     * <p>값 근거: 개별 블로킹 상한이 기본 15초라 <b>느린 벤더 1~2회 왕복</b>은 허용하면서, FE 폴링
     * 주기(정상 3초 / degrade 10초)와 겹쳐도 스레드가 오래 눌러앉지 않는 크기다. 정상 형상(청크 한
     * 자릿수 × 1초 미만 응답)에서는 이 예산에 닿지 않는다.
     */
    static final int DEFAULT_REQUEST_BUDGET_SECONDS = 20;

    private final AugmentSnapshotLoader snapshotLoader;
    private final ExternalAugmentClient externalClient;
    private final AugmentExternalProbe probe;
    /** 웹훅 유실분 회수(INT-030) — 조회가 종결을 알려주면 결과를 실제로 인계한다. */
    private final AugmentResultRecoveryService recoveryService;
    /** 조회 창 회전 — 뒤쪽 청크가 영원히 조회되지 않는 것을 막는다(MED-1). */
    private final AugmentStatusWindowRotator windowRotator;
    /** 요청 1회의 외부 호출 총 시간 예산. */
    private final Duration requestBudget;

    public AugmentProgressService(
            AugmentSnapshotLoader snapshotLoader,
            ExternalAugmentClient externalClient,
            AugmentExternalProbe probe,
            AugmentResultRecoveryService recoveryService,
            AugmentStatusWindowRotator windowRotator,
            @Value("${authoring.augment.external.request-budget-seconds:"
                    + DEFAULT_REQUEST_BUDGET_SECONDS + "}") long requestBudgetSeconds) {
        this.snapshotLoader = snapshotLoader;
        this.externalClient = externalClient;
        this.probe = probe;
        this.recoveryService = recoveryService;
        this.windowRotator = windowRotator;
        this.requestBudget = Duration.ofSeconds(Math.max(1, requestBudgetSeconds));
    }

    /**
     * 진행상태를 조회한다.
     *
     * @param dataAugSn 증강 결과 PK
     * @param actor     인증 주체(REVIEWER/WORKER)
     * @throws CustomException 401 미인증, 403 역할 미달, 404 없는 id, 400 해상도 파생(RESL_*)
     */
    public AugmentProgressResponse progress(Long dataAugSn, TokenClaims actor) {
        requireViewer(actor);
        AugmentSnapshotLoader.Snapshot snapshot = snapshotLoader.load(dataAugSn);

        // 1) 이미 종결됐거나 청크가 없으면 외부를 부르지 않는다(불필요한 왕복 제거).
        List<LsDataAugJob> pending = nonTerminalJobs(snapshot);
        if (pending.isEmpty()) {
            return toResponse(snapshot, AugmentProgressCalculator.compute(
                    snapshot.augStatus(), AugmentProgressCalculator.viewsOf(snapshot.jobs()), null));
        }

        // 2) 202 ACK 유실 — 외부 job_id 가 없으면 조회 자체가 불가능하다(S5).
        //    클라이언트의 requireValidJobId 는 이 경우 <예외>를 던지므로(skip 이 아니다) 여기서
        //    미리 분기하지 않으면 정상 진행 중인 작업이 화면에서 에러로 보인다.
        if (pending.stream().anyMatch(job -> !hasExternalJobId(job))) {
            return degrade(snapshot, AugmentProgressUnavailableReason.AWAITING_ACK);
        }

        // 3) 요청 단위 예산 개시 — 상태조회와 결과회수가 이 하나를 나눠 쓴다(HIGH-1).
        AugmentRequestBudget budget = AugmentRequestBudget.start(requestBudget);
        StatusQuery query = queryStatuses(snapshot, pending, budget);

        // 4) 웹훅 유실 회수 — 외부는 종결인데 우리 DB 는 비종결이면 결과조회로 인계한다(INT-030).
        //    ★ 상한/예산으로 degrade 된 경우에도 <조회에 성공한 청크에 한해> 시도한다(HIGH-2).
        boolean recovered = recoverLostWebhooks(snapshot, pending, query.statuses(), budget, actor);
        AugmentSnapshotLoader.Snapshot effective = recovered
                // 회수로 상태가 바뀌었으니 스냅샷을 다시 읽어 <실제 DB> 기준으로 회신한다.
                ? snapshotLoader.load(snapshot.dataAugSn())
                : snapshot;

        return toResponse(effective, AugmentProgressCalculator.compute(
                effective.augStatus(), viewsWith(effective.jobs(), query.statuses()), query.reason()));
    }

    /**
     * 상태조회 결과 묶음.
     *
     * @param statuses 조회에 성공한 청크의 §4.4 응답 (job PK → 응답)
     * @param reason   진행률을 신뢰할 수 없는 사유(전량 조회에 성공했으면 null)
     */
    private record StatusQuery(Map<Long, GenAiJobStatusResponse> statuses,
                               AugmentProgressUnavailableReason reason) {
    }

    /**
     * 비종결 청크의 외부 상태를 <b>예산 안에서</b> 조회한다.
     *
     * <p>진행률은 <b>전량 조회 성공</b>일 때만 산출한다 — 일부만 조회하고 나머지를 0% 로 채우면
     * 진행률이 실제보다 낮게 왜곡된다(거짓 값보다 "모름" 이 안전하다). 그래서 아래 (a)~(d) 중
     * 하나라도 발생하면 사유를 남기고 진행률을 포기한다. 단 <b>수집한 상태는 버리지 않는다</b> —
     * 회수(HIGH-2)의 근거가 되기 때문이다.
     *
     * <h3>실패는 <b>청크 단위</b>로 격리한다 (DEV_FIX 2차 MED-2)</h3>
     * <p>구 구현은 첫 실패에서 즉시 return 해, 계약을 어긴 청크 하나가 <b>형제 청크 전부</b>의
     * 상태조회·회수를 매 폴링마다 결정적으로 막았다. 지금은 실패한 청크만 건너뛰고 계속 조회한다.
     *
     * <p><b>서킷·재시도 예산과의 상호작용</b>: ①계약 위반은 클라이언트에서 재시도·서킷 연산자
     * <b>뒤</b>({@code .map(validateStatus)})에 검증되므로 서킷 failure 로 집계되지 않는다(비재시도
     * 결정적 오류를 서킷에 쌓지 않는다는 기존 설계 그대로). ②타임아웃·5xx 같은 진짜 장애는 이제
     * 폴링당 최대 {@value #MAX_STATUS_QUERIES} 건까지 집계될 수 있으나, 그것이 곧 <b>실제 벤더 장애의
     * 실측</b>이라 서킷이 더 빨리 열리는 것은 의도한 동작이다. 열린 뒤에는 호출이 즉시 거부되어
     * (왕복 0) 남은 청크를 훑어도 시간 비용이 없고, 총 호출량은 창 크기 × 요청 예산으로 이미 유계다.
     */
    private StatusQuery queryStatuses(AugmentSnapshotLoader.Snapshot snapshot,
                                      List<LsDataAugJob> pending,
                                      AugmentRequestBudget budget) {
        Map<Long, GenAiJobStatusResponse> statuses = new LinkedHashMap<>();
        AugmentProgressUnavailableReason reason = null;

        // (a) 건수 상한 — 창 하나만 조회하되 그 창은 폴링마다 회전한다(MED-1).
        List<LsDataAugJob> targets =
                windowRotator.nextWindow(snapshot.dataAugSn(), pending, MAX_STATUS_QUERIES);
        if (targets.size() < pending.size()) {
            log.warn("[Augment] 진행률 조회 건수 상한 초과 — degrade(회수는 계속) dataAugSn={} "
                            + "pendingJobs={} window={}",
                    snapshot.dataAugSn(), pending.size(), targets.size());
            reason = AugmentProgressUnavailableReason.QUERY_LIMIT_EXCEEDED;
        }

        int failed = 0;
        for (LsDataAugJob job : targets) {
            // (b) 시간 예산 — 남은 예산이 없으면 <개시하지 않는다>. 개시했다가 타임아웃 나면 우리
            //     자체 상한이 "벤더 장애(TRANSIENT_ERROR)" 로 위장된다.
            if (!budget.hasSlice()) {
                log.warn("[Augment] 진행률 조회 요청 예산 소진 — degrade dataAugSn={} queried={}/{}",
                        snapshot.dataAugSn(), statuses.size(), targets.size());
                return new StatusQuery(statuses, escalate(reason,
                        AugmentProgressUnavailableReason.QUERY_LIMIT_EXCEEDED));
            }
            AugmentExternalProbe.Probe<GenAiJobStatusResponse> result = probe.query(
                    externalClient.fetchJobStatus(job.getExternalJobId()), "상태조회",
                    snapshot.dataAugSn(), budget.remaining());
            if (result.isSuccess()) {
                statuses.put(job.getAugJobSn(), result.payload());
                continue;
            }
            // (c) 미연동은 <전역 조건>이다 — 청크마다 확인해봐야 결과가 같으므로 즉시 끊는다.
            if (result.reason() == AugmentProgressUnavailableReason.NOOP) {
                return new StatusQuery(statuses, AugmentProgressUnavailableReason.NOOP);
            }
            // (d) 그 외 실패는 <이 청크만> degrade 하고 나머지 청크는 계속 조회한다(MED-2).
            failed++;
            reason = escalate(reason, result.reason() == null
                    ? AugmentProgressUnavailableReason.TRANSIENT_ERROR
                    : result.reason());
        }
        if (failed > 0) {
            // 개별 실패 원인은 probe 가 이미 WARN 으로 남긴다 — 여기서는 범위만 요약한다.
            log.warn("[Augment] 진행률 조회 일부 실패(청크 단위 degrade) dataAugSn={} failed={}/{}",
                    snapshot.dataAugSn(), failed, targets.size());
        }
        return new StatusQuery(statuses, reason);
    }

    /**
     * degrade 사유 승격 — <b>벤더 장애가 우리 자체 상한보다 우선</b>한다.
     *
     * <p>둘 다 발생했을 때 자체 상한({@code QUERY_LIMIT_EXCEEDED} = "정상 상황")으로 표기하면 실제
     * 장애가 정상 상황으로 <b>위장</b>된다. HIGH-2 가 금지한 것은 그 반대 방향(자체 상한을 벤더
     * 장애로 표기)이므로 이 우선순위와 충돌하지 않는다.
     */
    private static AugmentProgressUnavailableReason escalate(
            AugmentProgressUnavailableReason current, AugmentProgressUnavailableReason candidate) {
        if (current == null || current == AugmentProgressUnavailableReason.QUERY_LIMIT_EXCEEDED) {
            return candidate;
        }
        return current;
    }

    /**
     * 외부가 종결로 보고한 비종결 job 을 회수한다. <b>부가 기능</b>이라 어떤 실패도 응답을 깨지 않는다.
     *
     * <h3>★ 부작용은 REVIEWER 폴링에서만 일어난다 (DEV_FIX MED-4)</h3>
     * <p>회수는 이름과 달리 <b>읽기가 아니다</b> — job 종결 → 롤업 → {@code LS_DATA_RAW} 파생영상
     * INSERT → AFTER_COMMIT {@code @Async} 프레임 재추출(ffmpeg)까지 연쇄한다. 즉 안전 메서드(GET)가
     * 상태를 바꾸고, <b>그 시점을 비-REVIEWER 가 정하게</b> 된다. 데이터 자체는 벤더가 준 것이고 회수는
     * 멱등이라 증폭은 없지만, WORKER 에게 "확정 시점을 유발할 능력" 을 새로 주는 것은 별개 문제다.
     * 그래서 <b>진행률 읽기는 WORKER 도 계속 가능</b>하되 회수 부작용만 REVIEWER 로 좁힌다.
     *
     * <p><b>트레이드오프</b>: R2("웹훅 누락 보완")의 트리거가 REVIEWER 폴링으로 좁아진다. 백스톱은
     * 그대로다 — 만료 스윕({@code AugmentJobExpirySweeper})이 비종결 job 을 회수해 <b>영구 대기는
     * 발생하지 않는다</b>(다만 그 경로는 실패 종결이라 산출물은 인계되지 않는다). 별도 회수 스케줄러
     * 신설은 이번 범위 밖이다.
     *
     * @return 하나라도 회수했는가
     */
    private boolean recoverLostWebhooks(AugmentSnapshotLoader.Snapshot snapshot,
                                        List<LsDataAugJob> pending,
                                        Map<Long, GenAiJobStatusResponse> statuses,
                                        AugmentRequestBudget budget,
                                        TokenClaims actor) {
        if (statuses.isEmpty() || actor.role() != Role.REVIEWER) {
            return false;
        }
        boolean recovered = false;
        for (LsDataAugJob job : pending) {
            GenAiJobStatusResponse status = statuses.get(job.getAugJobSn());
            if (status == null || !isExternalTerminal(status.status())) {
                continue;
            }
            if (!budget.hasSlice()) {
                // 남은 종결분은 그 청크가 창에 다시 들어오는 폴링에서 회수된다(회수는 멱등).
                log.warn("[Augment][Recovery] 요청 예산 소진 — 남은 회수 보류 dataAugSn={}",
                        snapshot.dataAugSn());
                break;
            }
            try {
                recovered |= recoveryService.recover(snapshot.dataAugSn(), job, status, budget);
            } catch (RuntimeException e) {
                // 회수 실패는 화면을 깨지 않는다 — 다음 폴링에서 재시도되고, 끝내 안 되면 만료 스윕이 회수한다.
                log.warn("[Augment][Recovery] 회수 실패(무시) dataAugSn={} jobSeq={} errType={}",
                        snapshot.dataAugSn(), job.getJobSeq(), e.getClass().getSimpleName());
            }
        }
        return recovered;
    }

    private static boolean isExternalTerminal(String externalStatus) {
        return LsDataAugJob.STTS_SUCCEEDED.equals(externalStatus)
                || LsDataAugJob.STTS_FAILED.equals(externalStatus)
                || LsDataAugJob.STTS_CANCELED.equals(externalStatus);
    }

    private static List<LsDataAugJob> nonTerminalJobs(AugmentSnapshotLoader.Snapshot snapshot) {
        return snapshot.jobs().stream().filter(job -> !job.isTerminal()).toList();
    }

    private static boolean hasExternalJobId(LsDataAugJob job) {
        return job.getExternalJobId() != null && !job.getExternalJobId().isBlank();
    }

    /** 외부 진행률을 얹은 계산용 관점 — 종결 job 은 외부 값과 무관하게 100 이다. */
    private static List<AugmentProgressCalculator.JobView> viewsWith(
            List<LsDataAugJob> jobs, Map<Long, GenAiJobStatusResponse> statuses) {
        List<AugmentProgressCalculator.JobView> views = new ArrayList<>(jobs.size());
        for (LsDataAugJob job : jobs) {
            GenAiJobStatusResponse status = statuses.get(job.getAugJobSn());
            views.add(new AugmentProgressCalculator.JobView(
                    job.getJobSeq() == null ? 0 : job.getJobSeq(),
                    job.getJobSttsCd(),
                    job.getTotalCount() == null ? 0 : job.getTotalCount(),
                    status == null ? null : status.progress()));
        }
        return views;
    }

    private AugmentProgressResponse degrade(AugmentSnapshotLoader.Snapshot snapshot,
                                            AugmentProgressUnavailableReason reason) {
        return toResponse(snapshot, AugmentProgressCalculator.compute(
                snapshot.augStatus(), AugmentProgressCalculator.viewsOf(snapshot.jobs()), reason));
    }

    private static AugmentProgressResponse toResponse(AugmentSnapshotLoader.Snapshot snapshot,
                                                      AugmentProgressCalculator.Result result) {
        return new AugmentProgressResponse(
                snapshot.dataAugSn(),
                snapshot.augTypeCd(),
                result.status().name(),
                result.progress(),
                result.reason() == null ? null : result.reason().name(),
                result.totalJobCount(),
                result.terminalJobCount(),
                result.cancelable(),
                result.nextPollAfterMs());
    }

    /**
     * 조회 권한 — REVIEWER/WORKER (컨트롤러 {@code @PreAuthorize} 와 이중 검증).
     *
     * <p><b>소유자 스코프는 두지 않는다</b>: 증강 목록({@code GET /v1/augments})도 무필터라 이 정책은
     * <b>기존 정책 상속</b>이며 신규 결함이 아니다(S9 MEDIUM). 열거만은 완화한다 — 없는 id 는
     * {@code AugmentSnapshotLoader} 가 404 를 주고, 403 과 404 를 섞어 존재 여부를 흘리지 않는다.
     */
    private static void requireViewer(TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        if (actor.role() != Role.REVIEWER && actor.role() != Role.WORKER) {
            throw new CustomException(ErrorCode.FORBIDDEN, "증강 진행상태 조회 권한이 없습니다.");
        }
    }
}
