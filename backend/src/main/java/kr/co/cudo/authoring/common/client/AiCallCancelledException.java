package kr.co.cudo.authoring.common.client;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;

/**
 * 사용자가 <b>스스로 취소</b>해서 끝난 추론 요청.
 *
 * <p>{@link CustomException} 을 상속해 전역 예외 처리기가 표준 응답 형태로 내보내게 한다.
 *
 * <p>⚠ 이것을 «외부 연동 실패(502)» 로 바꾸지 말 것. 취소는 정상 동선이며, 실패로 기록하면
 * 사용자가 누른 취소가 서킷 브레이커를 열어 <b>다른 사람의 추론까지</b> 막게 된다. 그래서 추론
 * 서비스의 {@code catch} 블록들은 이 예외를 <b>가장 먼저</b> 그대로 다시 던진다.
 */
public class AiCallCancelledException extends CustomException {

    public AiCallCancelledException() {
        super(ErrorCode.AI_REQUEST_CANCELLED);
    }
}
