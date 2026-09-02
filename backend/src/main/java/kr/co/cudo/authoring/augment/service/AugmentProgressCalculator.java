package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.dto.AugmentProgressStatus;
import kr.co.cudo.authoring.augment.dto.AugmentProgressUnavailableReason;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;

import java.util.List;

/**
 * 증강 진행상태 <b>산출 규칙</b> — 순수 계산(입출력 없음).
 *
 * <p>I/O(DB·외부 HTTP)와 분리한 이유는 이 규칙이 이번 Phase 에서 가장 오해가 잦은 부분이기 때문이다:
 * 가중 평균 산식(min 아님), 상태 집계 우선순위(부분 실패 = 전체 실패), degrade 사유 우선순위가 모두
 * 여기 모여 있고 전부 예제 테스트로 고정된다({@code AugmentProgressCalculatorTest}).
 *
 * <p>산식 정본은 {@code AugmentProgressResponse} Javadoc + Swagger 설명이다.
 */
public final class AugmentProgressCalculator {

    /** 종결 상태에서는 더 폴링할 필요가 없다. */
    static final long POLL_NONE = 0L;
    /** 외부 처리중 — 진행률이 자주 바뀐다. */
    static final long POLL_RUNNING_MS = 3_000L;
    /** 접수 상태 — 아직 진행률이 움직이지 않는다. */
    static final long POLL_RECEIVED_MS = 5_000L;
    /** 일시 장애 — 벤더/서킷 회복을 기다린다(과도한 재시도로 서킷을 계속 열지 않도록 완화). */
    static final long POLL_TRANSIENT_ERROR_MS = 10_000L;
    /** 미연동 — 폴링해도 값이 생기지 않는다. 사실상 중단에 가깝게 늦춘다. */
    static final long POLL_NOOP_MS = 30_000L;
    /**
     * 자체 상한(청크 수·요청 예산) — 더 자주 물어도 <b>같은 상한에 다시 걸린다</b>. 벤더 장애가
     * 아니므로 회복을 기다리는 것도 아니다. 회수 기회는 유지하되 호출량은 크게 낮춘다.
     */
    static final long POLL_QUERY_LIMIT_MS = 15_000L;

    private AugmentProgressCalculator() {
    }

    /**
     * 청크 job 1건의 진행 관점 — 계산에 필요한 최소 정보만 담는다.
     *
     * @param jobSeq           청크 순번
     * @param jobSttsCd        {@code LS_DATA_AUG_JOB.JOB_STTS_CD}
     * @param totalCount       그 청크가 위탁한 입력 파일 수({@code TOT_NOCS})
     * @param externalProgress 외부 상태조회(§4.2)가 준 진행률(0~100). 미조회/미제공이면 null
     */
    public record JobView(int jobSeq, String jobSttsCd, int totalCount, Integer externalProgress) {

        static JobView of(LsDataAugJob job, Integer externalProgress) {
            return new JobView(
                    job.getJobSeq() == null ? 0 : job.getJobSeq(),
                    job.getJobSttsCd(),
                    job.getTotalCount() == null ? 0 : job.getTotalCount(),
                    externalProgress);
        }

        boolean terminal() {
            return LsDataAugJob.STTS_SUCCEEDED.equals(jobSttsCd)
                    || LsDataAugJob.STTS_FAILED.equals(jobSttsCd)
                    || LsDataAugJob.STTS_CANCELED.equals(jobSttsCd);
        }

        /**
         * 가중치 — 위탁 파일 수. 거부 기록({@code createRejected})은 0 이라 최소 1 로 올린다.
         * 0 을 그대로 쓰면 그 청크가 분모에서 사라져 <b>실패한 청크가 없는 것처럼</b> 보인다.
         */
        int weight() {
            return Math.max(1, totalCount);
        }

        /** 이 청크의 진행률 — 종결이면 100(성패와 무관하게 "더 진행할 것이 없다"). */
        int progress() {
            if (terminal()) {
                return 100;
            }
            if (externalProgress == null) {
                return 0;
            }
            return Math.max(0, Math.min(100, externalProgress));
        }
    }

    /**
     * 산출 결과.
     *
     * @param status   집계 상태
     * @param progress 0~100 또는 null(산출 불가)
     * @param reason   progress 가 null 인 사유(그 외 null)
     */
    public record Result(AugmentProgressStatus status,
                         Integer progress,
                         AugmentProgressUnavailableReason reason,
                         int totalJobCount,
                         int terminalJobCount,
                         boolean cancelable,
                         long nextPollAfterMs) {
    }

    /**
     * 진행상태를 산출한다.
     *
     * @param augStatus    {@code LS_DATA_AUG.AUG_PROC_STTS_CD}
     * @param jobs         청크 job 관점 목록(빈 목록 허용 — 위탁 전/고아 PENDING)
     * @param degradeReason 외부 조회를 신뢰할 수 없는 사유(없으면 null)
     */
    public static Result compute(String augStatus, List<JobView> jobs,
                                 AugmentProgressUnavailableReason degradeReason) {
        List<JobView> safeJobs = jobs == null ? List.of() : jobs;
        int total = safeJobs.size();
        int terminal = (int) safeJobs.stream().filter(JobView::terminal).count();
        boolean pendingAug = LsDataAug.STTS_PENDING.equals(augStatus);

        // 1) 사용자 취소가 최우선 사실이다 — 늦게 도착한 성공 웹훅은 폐기되므로 화면도 CANCELED 다.
        if (LsDataAug.STTS_CANCELED.equals(augStatus)) {
            return terminalResult(AugmentProgressStatus.CANCELED, total, terminal);
        }

        // 2) 청크가 없다 — 위탁 전이거나 위탁이 통째로 실패한 고아 PENDING.
        if (safeJobs.isEmpty()) {
            if (pendingAug) {
                return new Result(AugmentProgressStatus.RECEIVED, null,
                        AugmentProgressUnavailableReason.AWAITING_ACK, 0, 0, true,
                        POLL_RECEIVED_MS);
            }
            return terminalResult(
                    LsDataAug.STTS_ACCEPTED.equals(augStatus)
                            ? AugmentProgressStatus.SUCCEEDED
                            : AugmentProgressStatus.FAILED,
                    0, 0);
        }

        // 3) 전 청크 종결 — 부분 실패 = 전체 실패(AugmentJobRollup 과 같은 태도).
        if (terminal == total) {
            return terminalResult(aggregateTerminal(safeJobs), total, terminal);
        }

        // 4) 진행 중 — 상태의 진실원은 DB({@code JOB_STTS_CD}) 다(외부 조회로 덮어쓰지 않는다).
        AugmentProgressStatus status = safeJobs.stream()
                .anyMatch(j -> LsDataAugJob.STTS_RUNNING.equals(j.jobSttsCd()))
                ? AugmentProgressStatus.RUNNING
                : AugmentProgressStatus.RECEIVED;
        if (degradeReason != null) {
            return new Result(status, null, degradeReason, total, terminal, pendingAug,
                    pollFor(degradeReason));
        }
        int progress = weightedAverage(safeJobs);
        // 진행률이 0 보다 크면 <이미 작업이 시작됐다>. 이때 RECEIVED 로 표시하면 "접수됨 99%" 라는
        // 자기모순이 화면에 나온다 — RUNNING 웹훅을 보내지 않고 SUCCEEDED 만 보내는 벤더나, 앞 청크가
        // 끝났는데 뒤 청크가 아직 RECEIVED 인 정상 구간에서 실제로 발생한다. 이것은 DB 상태를
        // <덮어쓰는> 것이 아니라(테이블은 그대로다) <표시 문구>를 데이터와 모순되지 않게 맞추는 것이다.
        if (status == AugmentProgressStatus.RECEIVED && progress > 0) {
            status = AugmentProgressStatus.RUNNING;
        }
        return new Result(status, progress, null, total, terminal, pendingAug,
                status == AugmentProgressStatus.RUNNING ? POLL_RUNNING_MS : POLL_RECEIVED_MS);
    }

    /** 종결 집계 — FAILED 우선, 다음 CANCELED, 그 외 SUCCEEDED. */
    private static AugmentProgressStatus aggregateTerminal(List<JobView> jobs) {
        if (jobs.stream().anyMatch(j -> LsDataAugJob.STTS_FAILED.equals(j.jobSttsCd()))) {
            return AugmentProgressStatus.FAILED;
        }
        if (jobs.stream().anyMatch(j -> LsDataAugJob.STTS_CANCELED.equals(j.jobSttsCd()))) {
            return AugmentProgressStatus.CANCELED;
        }
        return AugmentProgressStatus.SUCCEEDED;
    }

    private static Result terminalResult(AugmentProgressStatus status, int total, int terminal) {
        return new Result(status, 100, null, total, terminal, false, POLL_NONE);
    }

    /**
     * 파일 수 가중 평균 — {@code round(Σ(weight×p) / Σweight)}.
     *
     * <p>{@code long} 누산 + 반올림으로 계산한다. 100장 × 100 청크여도 오버플로 여지가 없다.
     */
    private static int weightedAverage(List<JobView> jobs) {
        long weighted = 0;
        long weights = 0;
        for (JobView job : jobs) {
            weighted += (long) job.weight() * job.progress();
            weights += job.weight();
        }
        if (weights == 0) {
            return 0;
        }
        return (int) ((weighted + weights / 2) / weights);
    }

    private static long pollFor(AugmentProgressUnavailableReason reason) {
        return switch (reason) {
            case NOOP -> POLL_NOOP_MS;
            case TRANSIENT_ERROR -> POLL_TRANSIENT_ERROR_MS;
            case AWAITING_ACK -> POLL_RECEIVED_MS;
            case QUERY_LIMIT_EXCEEDED -> POLL_QUERY_LIMIT_MS;
        };
    }

    /** 엔티티 목록 → 계산용 관점(외부 진행률 미조회). */
    public static List<JobView> viewsOf(List<LsDataAugJob> jobs) {
        return jobs.stream().map(job -> JobView.of(job, null)).toList();
    }
}
