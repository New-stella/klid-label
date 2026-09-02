package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.common.config.ExternalUrlPolicy;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

/**
 * 관리 창구로 들어온 <b>장비 주소</b>의 판정. [@design ADR-046] [@design API-227] [@design AC-1089]
 *
 * <h3>판정을 새로 만들지 않는다</h3>
 * <p>어떤 주소를 허용할지는 이미 외부 연동 주소 정책({@link ExternalUrlPolicy})이 소유한다. 여기서
 * 하는 일은 <b>그 판정을 부르고 거부를 우리 응답 형태로 옮기는 것</b>뿐이다. 규칙을 복제하면 그
 * 사본이 두 번째 진실원이 되어, 한쪽만 통과하는 주소가 조용히 생긴다.
 *
 * <h3>평문 http 와 사설 대역은 통과가 정상이다</h3>
 * <p>연동 대상이 내부망의 별도 장비에 있는 것이 통상이라, 대역으로 막으면 공격자가 아니라 <b>정당한
 * 대상</b>을 막는다(구 정책은 폐기됐다). 남는 거부는 클라우드 메타데이터·링크로컬 같은 예약 대역과
 * 비허용 스킴·형식 위반뿐이다.
 *
 * <h3>★거부 사유에 입력을 싣지 않는다</h3>
 * <p>정책이 던지는 메시지에는 설정 키·스킴·호스트가 담긴다. 그것을 그대로 응답에 실으면 <b>그 응답
 * 자체가 내부망을 훑는 수단</b>이 된다(CWE-209) — 주소를 바꿔 가며 문구 차이를 읽으면 어떤 호스트가
 * 해석되는지 알 수 있다. 그래서 응답은 <b>사유를 가리지 않는 한 문장</b>으로 고정하고, 진단에 필요한
 * 원문은 서버 로그에만 남긴다.
 */
@Slf4j
public final class AiSrvrAddressPolicy {

    /** 설정 축이 아니라 <b>화면 입력</b> 축이라, 정책에 넘기는 이름도 사용자에게 보일 말로 둔다. */
    static final String SUBJECT = "AI 장비 주소";

    /**
     * ★사유를 가르지 않는 <b>단일 거부 문구</b> — 형식·스킴·예약 대역이 모두 같은 말로 떨어진다.
     * 문구가 갈리면 그 차이가 곧 내부망 탐지 신호가 된다.
     */
    static final String REJECT_MESSAGE = "장비 주소를 사용할 수 없습니다. http 또는 https 로 시작하는 주소인지 확인하세요.";

    /** 로그에 남길 사유 길이 상한 — 「무엇이 걸렸는지」만 알면 된다. */
    private static final int LOG_REASON_MAX_LENGTH = 200;

    private static final ExternalUrlPolicy POLICY = ExternalUrlPolicy.internalNetwork(SUBJECT);

    private AiSrvrAddressPolicy() {
    }

    /**
     * 주소를 검증한다 — 위반이면 400 이다.
     *
     * @throws CustomException 형식·스킴 위반 또는 예약 대역({@link ErrorCode#INVALID_INPUT})
     */
    public static void requireValid(String srvrAddr) {
        try {
            POLICY.check(srvrAddr);
        } catch (IllegalStateException rejected) {
            // 사유는 서버 로그에만. 응답은 위 고정 문구 하나뿐이다.
            log.warn("[AiSrvr] 장비 주소가 거부됐습니다. reason={}", sanitizeForLog(rejected.getMessage()));
            throw new CustomException(ErrorCode.INVALID_INPUT, REJECT_MESSAGE);
        }
    }

    /**
     * 로그에 실을 사유를 안전한 표기로 바꾼다.
     *
     * <p>이 문장에는 <b>사용자가 보낸 주소에서 뽑은 호스트</b>가 섞여 있다. 개행·제어문자가 들어가면
     * 로그 한 줄에 여러 줄이 들어가 기록을 위조할 수 있다(CWE-117).
     */
    private static String sanitizeForLog(String reason) {
        if (reason == null) {
            return "(none)";
        }
        String printable = reason.replaceAll("[\\p{Cntrl}]", "?");
        return printable.length() <= LOG_REASON_MAX_LENGTH
                ? printable
                : printable.substring(0, LOG_REASON_MAX_LENGTH) + "...";
    }
}
