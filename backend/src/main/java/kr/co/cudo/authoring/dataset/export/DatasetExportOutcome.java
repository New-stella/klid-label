package kr.co.cudo.authoring.dataset.export;

/**
 * D-ISSUE-61 — 학습데이터 산출({@link DatasetExportService#export(long, boolean)})의 <b>종결 결과</b>.
 *
 * <h3>왜 반환값이 필요한가 (CRITICAL)</h3>
 * 구 구현은 산출 성공 여부를 <b>예외 유무</b>로만 상위에 알렸다. 그런데 {@code export} 는 예외를 던지지
 * 않고 정상 반환하면서 실패로 마감하는 경로가 4종이다 — {@link #NO_INPUT}(프레임/활성 메타 부재) ·
 * {@link #FAILED}(산출 base 거부 · 산출물 0건 · 쓰기 중 런타임 예외) · {@link #VERSION_EXHAUSTED}(버전 채번
 * 소진). 그래서 {@code AsyncDatasetExportRunner} 가 이 4경로를 성공으로 오판해 TASK_COMPLETED/
 * TASK_MODIFIED 통지를 발송했고, 관제는 {@code V_COMPLETED_VIDEO.EXPORT_PATH_NM} 을 조회해 <b>존재하지
 * 않거나 구 버전인 폴더</b>를 픽업했다(실측 rawSn=72 — export FAILED 직후 TASK_COMPLETED, 뷰 0행).
 *
 * <p>이제 종결 분기가 이 enum 하나로 표현되고, 통지 여부는 {@link #notifiable()} <b>단일 판정</b>을 따른다.
 * CLAUDE.md 구속 정책(*"통지는 export 성공 후 발송한다 … 실패하면 통지를 보류하고 재산출 성공 후 재개"*)의
 * 구현 지점이다.
 *
 * <h3>통지 가능 여부 판정 근거</h3>
 * 기준은 "지금 이 순간 관제가 뷰({@code V_COMPLETED_VIDEO})를 조회하면 <b>최신 산출물</b>을 집는가"다.
 * <ul>
 *   <li>{@link #COMPLETED} — 이번 실행이 산출물을 만들었고 SUCCEEDED 로 마감했다. 통지 O.</li>
 *   <li>{@link #PARTIAL} — 일부 프레임만 산출됐으나 <b>PARTIAL 도 뷰에 노출</b>된다(V160, E-ISSUE-81).
 *       뷰·통지·멱등 baseline 세 판정이 PARTIAL 을 동일하게 취급하므로 통지 O.</li>
 *   <li>{@link #IDEMPOTENT_SKIP} — 재동결 경로에서 콘텐츠가 직전 산출과 동일해 재산출을 생략했다.
 *       디스크의 직전 SUCCEEDED/PARTIAL 산출물이 곧 최신이므로 관제 픽업이 유효하다. 통지 O.</li>
 *   <li>{@link #NO_INPUT} · {@link #FAILED} · {@link #VERSION_EXHAUSTED} — 이번 실행의 산출물이 없다.
 *       통지하면 관제가 구 버전 폴더를 최신으로 오인하거나(재승인) 아무 폴더도 못 찾는다(최초 승인).
 *       통지 X — 회수기({@code DatasetExportFailureRecoverer})가 재산출 성공 후 재개한다.</li>
 *   <li>{@link #DEIDENT_BLOCKED} — 비식별 누락 신고 구간이라 산출 자체를 하지 않았다(정책적 보류).
 *       {@code export} 가 예외로 이탈하므로 실제로는 이 값이 반환되지 않으나, 판정 누락 시에도
 *       통지되지 않도록 {@code notifiable=false} 로 명시한다(fail-closed).</li>
 * </ul>
 *
 * <p>{@link #metricTag()} 는 기존 {@code dataset.export.result{outcome}} 태그 값을 그대로 승계한다 —
 * 관측 대시보드/알람의 태그 계약을 바꾸지 않는다(저카디널리티 고정 문자열 7종).
 */
public enum DatasetExportOutcome {

    /** 전 프레임 산출 성공 — SUCCEEDED 마감. */
    COMPLETED("completed", true),

    /** 일부 프레임 산출(원천 이미지 부재 등으로 skip 발생) — PARTIAL 마감. */
    PARTIAL("partial", true),

    /** 재동결 경로에서 직전 산출과 콘텐츠 해시가 같아 재산출을 생략 — 산출물은 직전 것이 최신. */
    IDEMPOTENT_SKIP("idempotent_skip", true),

    /** 산출 실패(산출 base 거부 · 산출물 0건 · 쓰기 중 런타임 예외) — FAILED 마감. */
    FAILED("failed", false),

    /** 버전 채번 UK 충돌이 재시도 상한까지 지속돼 예약 자체를 못 함 — 산출물 없음. */
    VERSION_EXHAUSTED("version_exhausted", false),

    /** 프레임/활성 메타 부재로 산출할 입력이 없음 — 산출물 없음. */
    NO_INPUT("no_input", false),

    /** 비식별 누락 신고 구간이라 산출을 수행하지 않고 차단(실패 아닌 정책적 보류). */
    DEIDENT_BLOCKED("deident_blocked", false);

    private final String metricTag;
    private final boolean notifiable;

    DatasetExportOutcome(String metricTag, boolean notifiable) {
        this.metricTag = metricTag;
        this.notifiable = notifiable;
    }

    /** {@code dataset.export.result}/{@code duration} 의 {@code outcome} 태그 값(기존 계약 승계). */
    public String metricTag() {
        return metricTag;
    }

    /**
     * 이 종결 이후 관제 통지(TASK_COMPLETED/TASK_MODIFIED)를 발송해도 되는가.
     *
     * <p>{@code false} 면 통지를 <b>보류</b>한다(유실이 아니라 재산출 성공 시점으로 지연 —
     * {@code DatasetExportFailureRecoverer} 가 재개).
     */
    public boolean notifiable() {
        return notifiable;
    }
}
