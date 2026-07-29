package kr.co.cudo.authoring.webhook.service;

/**
 * 증강 결과 인계({@link AugmentResultService#handle})의 처리 판정 — Phase 8-B.
 *
 * <h3>왜 boolean 으로 부족한가 (E-ISSUE-11)</h3>
 * <p>구 계약은 {@code boolean}(적용 여부) 하나였다. 그래서 <b>재전송 멱등 흡수</b>·<b>롤업 보류</b>가
 * "적용됨" 과 구분되지 않았고, 웹훅 응답이 {@code applied:true} 로 나가 외부는 정상 인계로 오해했다.
 *
 * <h3>정책 보류(WITHHELD)는 2026-07-29 로 폐기됐다</h3>
 * <p>부모가 비식별 누락 신고({@code 'F'}) 구간이면 증강본 생성을 보류하고 해소 시 재개하던 값
 * ({@code WITHHELD_PARENT_NOT_DEIDENTIFIED})이 있었다. "파생영상은 비식별 신고 체계 바깥" 확정으로
 * 신고는 파생 생성을 막지 않게 됐고(막는 것은 외부 위탁뿐), 재개 배선도 함께 철회됐다. 재개 트리거
 * 없는 보류는 PENDING 영구 고착이므로 남기지 않는다 — 부모를 물리적으로 쓸 수 없는 경우
 * (비식별 미완료·부모/프레임 부재)는 {@code REJECTED} 로 실패 확정한다.
 */
public enum AugmentApplyResult {

    /** 증강 1건의 상태를 실제로 전이시켰다(ACCEPTED 또는 REJECTED). */
    APPLIED,

    /** 이미 종결(non-PENDING)된 행의 재전송 — 상태를 바꾸지 않고 멱등 흡수했다. */
    DUPLICATE,

    /** 비종결 job 이 남아 롤업을 보류했다(정상 진행중 — 아직 증강 1건을 확정할 수 없다). */
    DEFERRED;

    /** 증강 1건의 상태가 실제로 전이됐는가 — 웹훅 응답 {@code applied} 의 단일 근거. */
    public boolean applied() {
        return this == APPLIED;
    }

    /**
     * 외부 회신용 사유 코드 — 지금은 항상 {@code null}(정책 보류 폐기).
     *
     * <p>내부 경로·식별자·스택트레이스를 담지 않는 <b>고정 코드값</b>만 노출한다는 규약을 유지하기
     * 위해 필드 자체는 남긴다(CWE-209).
     */
    public String reasonCode() {
        return null;
    }
}
