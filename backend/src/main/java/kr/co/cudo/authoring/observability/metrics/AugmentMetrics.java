package kr.co.cudo.authoring.observability.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 외부 증강 요청 관찰성 메트릭 (콜백 충실 플로우 Phase 1 — DEV_FIX MEDIUM-3).
 *
 * <ul>
 *   <li>{@code augment.external.request} (Counter, tag status=success|failure) —
 *       AFTER_COMMIT 리스너에서 {@code ExternalAugmentClient.requestAugment} 호출 결과 집계.
 *       장애 시 failure 카운트로 외부 연동 가용성을 모니터링한다.</li>
 *   <li>{@code augment.cleanup.failed} (Counter) — 프레임 재추출 Phase B 산출 아티팩트(재추출 프레임
 *       디렉토리) cleanup 후에도 파일이 잔존(삭제 실패)한 건수. 증가 시 스토리지 고아 파일 누적 신호이므로
 *       운영자가 수동 정리·알림 대상으로 삼는다(커넥션-점유 분리 리팩터 — 증강 경로).</li>
 * </ul>
 */
@Component
public class AugmentMetrics {

    /** 생성형 AI 콜백을 <b>상태 변경 없이</b> 거부한 건수(tag reason). */
    private static final String CALLBACK_REJECTED = "augment.callback.rejected";
    private static final String TAG_REASON = "reason";

    /** 산출 경로가 읽기 허용 루트 밖 — 벤더 재시도 소진 시 증강이 PENDING 에 고착된다. */
    public static final String REASON_OUTPUT_PATH = "output_path";
    /** SUCCEEDED 인데 {@code results[]} 가 비어 있음(계약 위반). */
    public static final String REASON_MISSING_RESULTS = "missing_results";

    /** 만료 스윕이 회수한 비종결 job 건수 (Phase 8-A). */
    private static final String JOB_EXPIRED = "augment.job.expired";

    /** 만료 스윕이 회수한 <b>job 행 0건 장기 PENDING</b> 증강 건수 (적대검증 2차 MEDIUM-2). */
    private static final String AUG_ORPHAN_EXPIRED = "augment.aug.orphan.expired";

    /** 정책 보류(PII) 재개가 실패한 건수 (tag stage). */
    private static final String RESUME_FAILED = "augment.resume.failed";
    private static final String TAG_STAGE = "stage";

    /** 재개 후보 조회 자체가 실패 — 그 영상의 보류분 <b>전체</b>가 이번 해제로 깨어나지 못했다. */
    public static final String STAGE_LOOKUP = "lookup";
    /**
     * 후보 1건의 <b>보류 유형 판별</b>(job 행 조회)이 실패 — 그 건은 이번 해제로 재개되지 않았다
     * (적대검증 2차 LOW-1). 판별이 try 밖에 있던 구 구현에서는 이 실패가 <b>나머지 후보까지</b>
     * 스킵시키면서 어떤 축에도 잡히지 않았다.
     */
    public static final String STAGE_CLASSIFY = "classify";
    /** 위탁 전 보류(job 0건) 재개 실패. */
    public static final String STAGE_SUBMIT = "submit";
    /** 결과 인계 보류(전 job SUCCEEDED) 재개 실패. */
    public static final String STAGE_RESULT = "result";

    private static final String EXTERNAL_REQUEST = "augment.external.request";
    private static final String CLEANUP_FAILED = "augment.cleanup.failed";
    private static final String TAG_STATUS = "status";
    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILURE = "failure";

    private final MeterRegistry registry;

    public AugmentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void externalRequestSuccess() {
        registry.counter(EXTERNAL_REQUEST, TAG_STATUS, STATUS_SUCCESS).increment();
    }

    public void externalRequestFailure() {
        registry.counter(EXTERNAL_REQUEST, TAG_STATUS, STATUS_FAILURE).increment();
    }

    /**
     * 생성형 AI 콜백을 상태 변경 없이 거부했을 때 1 증가.
     *
     * <p>이 카운터가 오르는 동안 해당 job 은 비종결로 남아 증강 1건이 PENDING 이다(만료 스윕 없음).
     * 운영은 이 값 + {@code LS_DATA_AUG_JOB} 비종결 행 경과시간으로 고착을 감지한다.
     */
    public void callbackRejected(String reason) {
        registry.counter(CALLBACK_REJECTED, TAG_REASON, reason).increment();
    }

    /** 프레임 재추출 Phase B cleanup 후에도 아티팩트가 잔존(삭제 실패)했을 때 1 증가. */
    public void cleanupFailed() {
        registry.counter(CLEANUP_FAILED).increment();
    }

    /**
     * 만료 스윕이 비종결 job 을 회수(FAILED 종결)했을 때 1 증가 (Phase 8-A).
     *
     * <p>정상 연동에서는 0 이어야 한다 — 지속 상승은 외부가 웹훅을 못 보내고 있다는 신호이며,
     * 그만큼 증강이 실패로 확정된다(만료는 성공이 아니다).
     */
    public void jobExpired() {
        registry.counter(JOB_EXPIRED).increment();
    }

    /**
     * 만료 스윕이 <b>job 행 0건 장기 PENDING</b> 증강을 회수했을 때 1 증가 (적대검증 2차 MEDIUM-2).
     *
     * <p>이 상태는 위탁 전 롤업이 예외로 끝나(DB 순단·커넥션 고갈 등) job 도 없고 콜백도 오지 않는
     * 고아 증강이다. 그 영상에 비식별 신고가 없었다면 재개 이벤트가 <b>영원히 발생하지 않으므로</b>
     * 어떤 회수기도 집지 못했다. 0 이 아니면 위탁 경로 장애가 있었다는 신호다(정책 보류는 여기 오지
     * 않는다 — 회수 대상에서 제외된다).
     */
    public void augOrphanExpired() {
        registry.counter(AUG_ORPHAN_EXPIRED).increment();
    }

    /**
     * 정책 보류(PII) 재개가 실패했을 때 1 증가 (Phase 8 DEV_FIX HIGH-1).
     *
     * <p>보류분의 <b>유일한 복구 경로</b>가 신고 해제 재트리거이므로, 여기서 실패하면 그 증강은 다음
     * 신고·해제가 다시 일어날 때까지 PENDING 에 남는다. 구 구현은 WARN 한 줄로 삼켜 고착이 관측되지
     * 않았다 — 이 카운터가 0 이 아니면 {@code LS_DATA_AUG} PENDING 잔존을 즉시 점검해야 한다.
     *
     * @param stage {@link #STAGE_LOOKUP} / {@link #STAGE_SUBMIT} / {@link #STAGE_RESULT}
     */
    public void resumeFailed(String stage) {
        registry.counter(RESUME_FAILED, TAG_STAGE, stage).increment();
    }
}
