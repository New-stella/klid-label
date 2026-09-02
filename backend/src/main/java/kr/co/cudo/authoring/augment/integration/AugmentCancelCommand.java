package kr.co.cudo.authoring.augment.integration;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;

/**
 * 외부 증강 작업 취소 요청 컨텍스트 — 「생성형 AI API 연동명세서 v1.3」 §4.4 (INT-031).
 *
 * <h3>{@code requested_by} 는 자유 문자열로 받지 않는다 — <b>관례적 방어</b>다</h3>
 * <p>취소는 감사 대상 행위라 "누가 눌렀는가" 가 호출부의 임의 문자열이면 안 된다. 그래서 이 커맨드는
 * <b>{@link TokenClaims}(인증 주체) 자체</b>를 받고 {@link #requestedBy()} 를 그 {@code sub} 에서만
 * 파생시킨다.
 *
 * <p><b>단, 이것은 "타입 수준에서 위조를 불가능하게" 만들지 않는다</b>(과장 금지). {@link TokenClaims}
 * 는 평범한 {@code record} 라 어떤 호출부든 {@code new TokenClaims("아무개", …)} 를 만들어 넘길 수 있고,
 * 실제로 본 클래스의 테스트가 그 경로를 쓴다. 이 설계가 주는 것은 "String 을 그대로 받는 시그니처보다
 * <b>실수로 아무 값이나 넣기 어렵게</b> 만드는" 관례적 방어까지다.
 *
 * <p><b>취소 인가(누가 이 job 을 취소할 자격이 있는가)는 Phase 3 컨트롤러의 책임</b>이다 —
 * IDOR(소유자·범위 검증)·역할 게이트·CSRF 가 거기서 걸린다. 이 커맨드는 인가를 대신하지 않는다
 * ({@code externalJobId} 만으로 취소를 받으면 타인 job 취소가 열린다).
 *
 * <h3>{@code localTerminal} — 불필요한 409 왕복 제거</h3>
 * <p>로컬 {@code LS_DATA_AUG_JOB} 이 이미 종결({@code LsDataAugJob#isTerminal()})이면 외부는 확정적으로
 * 409 {@code STATE_CONFLICT} 를 준다. 클라이언트는 DB 를 읽지 않으므로(순수 HTTP 어댑터) 호출부가 그
 * 판정을 실어 보내고, 클라이언트는 외부 호출을 개시하지 않은 채
 * {@link AugmentQueryResult.SkipReason#LOCAL_TERMINAL} 로 회신한다.
 *
 * @param externalJobId 외부가 발급한 job_id (필수)
 * @param actor         인증 주체 — {@code sub} 가 {@code requested_by} 가 된다 (필수)
 * @param reason        취소 사유(선택, ≤500). 공백/빈 문자열은 null 로 정규화해 전송에서 뺀다.
 * @param localTerminal 로컬 DB 기준 이미 종결된 job 인가
 */
public record AugmentCancelCommand(
        String externalJobId,
        TokenClaims actor,
        String reason,
        boolean localTerminal) {

    /** §4.4 {@code reason} 상한. */
    public static final int REASON_MAX = 500;
    /** §4.4 {@code requested_by} 상한. */
    public static final int REQUESTED_BY_MAX = 64;

    public AugmentCancelCommand {
        if (actor == null || actor.sub() == null || actor.sub().isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "취소 요청자를 확인할 수 없습니다.");
        }
        if (actor.sub().length() > REQUESTED_BY_MAX) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "취소 요청자 식별자가 너무 깁니다.");
        }
        if (reason != null && reason.length() > REASON_MAX) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "취소 사유는 " + REASON_MAX + "자를 초과할 수 없습니다.");
        }
        reason = (reason == null || reason.isBlank()) ? null : reason.strip();
    }

    /** §4.4 {@code requested_by} — 인증 주체에서만 파생된다. */
    public String requestedBy() {
        return actor.sub();
    }
}
