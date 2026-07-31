package kr.co.cudo.authoring.augment.integration;

import java.util.Objects;

/**
 * 외부 증강 <b>조회/취소</b> 호출의 결과 — 외부 호출을 하지 않은 경우를 값으로 표현한다 (Phase 7-A2).
 *
 * <p>{@link AugmentSubmitResult#skipped()} 와 같은 계열의 장치다. <b>가짜 데이터를 조립하지 않기 위한
 * 타입</b>이며, 이유는 dev/stg/prd 기본이 {@code mode=noop} 이기 때문이다 — noop 이 그럴듯한
 * {@code SUCCEEDED}/{@code RUNNING} 을 지어내면 존재하지 않는 job 을 <b>성공 확정</b>하게 되어 폴링·화면이
 * 통째로 오작동한다. 그래서 미연동은 payload 를 만들지 않고 {@link SkipReason} 만 돌려준다.
 *
 * <p><b>소비 규약</b>: {@link #isSkipped()} 를 먼저 보고 분기한다. 스킵 결과의 {@link #payload()} 는
 * null 이며, 이는 "성공했는데 값이 없다" 가 아니라 "판단할 근거가 없다" 는 뜻이다.
 *
 * <h3>불변식은 타입이 강제한다 (CWE-476)</h3>
 * <p>{@code payload} 와 {@code skipReason} 은 <b>정확히 하나만</b> 존재한다. 구 구현은 정준 생성자가
 * 검증 없이 열려 있어 {@code new AugmentQueryResult<>(null, null)} 이 가능했고, 그 값은
 * {@code isSkipped()=false} 인데 {@code payload()=null} 이라 <b>소비 규약을 지킨 소비처에서도 NPE</b> 가
 * 났다. 이 타입의 존재 이유가 "가짜/모순 상태를 만들지 않는 것" 이므로 조립 불가능성을 스스로 지킨다.
 * (record 는 public 레코드의 정준 생성자를 좁힐 수 없으므로 compact 생성자 검증을 쓴다.)
 *
 * @param payload    성공 응답 본문. 스킵이면 null.
 * @param skipReason 외부 호출을 하지 않은 사유. 정상 호출이면 null.
 * @param <T>        응답 DTO 타입
 */
public record AugmentQueryResult<T>(T payload, SkipReason skipReason) {

    public AugmentQueryResult {
        if ((payload == null) == (skipReason == null)) {
            throw new IllegalArgumentException(
                    "AugmentQueryResult 는 payload 와 skipReason 중 정확히 하나만 가져야 한다.");
        }
    }

    /** 외부 호출을 개시하지 않은 사유. */
    public enum SkipReason {
        /** 외부 연동 자체가 꺼져 있음({@code authoring.augment.external.mode=noop}). */
        EXTERNAL_DISABLED,
        /** 로컬 DB 기준 이미 종결된 job — 결과가 바뀌지 않으므로 왕복(409)을 생략했다. */
        LOCAL_TERMINAL
    }

    public static <T> AugmentQueryResult<T> of(T payload) {
        return new AugmentQueryResult<>(Objects.requireNonNull(payload, "payload"), null);
    }

    public static <T> AugmentQueryResult<T> skipped(SkipReason skipReason) {
        return new AugmentQueryResult<>(null, Objects.requireNonNull(skipReason, "skipReason"));
    }

    /** 외부 호출을 하지 않았는가 — true 면 {@link #payload()} 를 신뢰하면 안 된다. */
    public boolean isSkipped() {
        return skipReason != null;
    }
}
