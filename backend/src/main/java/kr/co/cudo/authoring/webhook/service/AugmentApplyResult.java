package kr.co.cudo.authoring.webhook.service;

/**
 * 증강 결과 인계({@link AugmentResultService#handle})의 처리 판정 — Phase 8-B.
 *
 * <h3>왜 boolean 으로 부족한가 (E-ISSUE-11)</h3>
 * <p>구 계약은 {@code boolean}(적용 여부) 하나였다. 그래서 <b>부모가 비식별 완료가 아니라 증강본
 * 생성을 보류</b>한 경우가 "적용됨" 과 구분되지 않았고, 웹훅 응답이 {@code applied:true} 로 나가
 * 외부는 정상 인계로 오해했다. 실제로는 증강 행이 종결(ACCEPTED)로 못박히고 신규 영상은 0건이라
 * 재콜백도 멱등 스킵되어 <b>영구 유실</b>됐다.
 *
 * <h3>보류(WITHHELD)는 실패가 아니다</h3>
 * <p>이 리포의 확립된 규약이다 — 정책 차단은 terminal 로 못박지 않고 <b>보류</b>로 남기고, 해제
 * 시점에 재개한다({@code DeidentReportResolvedEvent} → {@code AugmentRequestBridge}). terminal 로
 * 만들면 회수기가 반드시 다시 막힐 재시도로 상한만 소진하거나, 애초에 회수 대상조차 되지 않는다.
 */
public enum AugmentApplyResult {

    /** 증강 1건의 상태를 실제로 전이시켰다(ACCEPTED 또는 REJECTED). */
    APPLIED,

    /** 이미 종결(non-PENDING)된 행의 재전송 — 상태를 바꾸지 않고 멱등 흡수했다. */
    DUPLICATE,

    /** 비종결 job 이 남아 롤업을 보류했다(정상 진행중 — 아직 증강 1건을 확정할 수 없다). */
    DEFERRED,

    /**
     * 부모 영상이 <b>비식별 완료('Y')가 아니어서</b> 증강본 생성을 보류했다(CWE-359).
     *
     * <p>증강 행은 {@code PENDING} 그대로 남으며, 신고 해소({@code DE_IDNTF_YN 'F'→'Y'}) 시점의
     * {@code DeidentReportResolvedEvent} 재개 배선이 다시 인계한다.
     */
    WITHHELD_PARENT_NOT_DEIDENTIFIED;

    /** 증강 1건의 상태가 실제로 전이됐는가 — 웹훅 응답 {@code applied} 의 단일 근거. */
    public boolean applied() {
        return this == APPLIED;
    }

    /** 정책 보류(재개 대기)인가 — 실패가 아니므로 회수·재시도 상한 소진 대상이 아니다. */
    public boolean withheld() {
        return this == WITHHELD_PARENT_NOT_DEIDENTIFIED;
    }

    /**
     * 외부 회신용 사유 코드 — 보류가 아니면 {@code null}.
     *
     * <p>내부 경로·식별자·스택트레이스를 담지 않는 <b>고정 코드값</b>만 노출한다(CWE-209).
     */
    public String reasonCode() {
        return withheld() ? name() : null;
    }
}
