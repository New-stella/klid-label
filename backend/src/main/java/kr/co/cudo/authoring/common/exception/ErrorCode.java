package kr.co.cudo.authoring.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 유효하지 않습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다."),
    CONFLICT(HttpStatus.CONFLICT, "리소스가 충돌합니다."),
    ASSIGNMENT_ALREADY_COMPLETED(HttpStatus.CONFLICT, "완료된 작업은 재배정할 수 없습니다."),
    /**
     * 라벨 0건 영상을 검수자 확인 없이 승인하려 한 경우 (DEV_FIX H6).
     *
     * <p>승인 경로의 409 에는 <b>동시 승인 충돌·상태 전이 불가</b>도 함께 존재한다. 화면이 상태코드만
     * 보고 분기하면 낙관적 잠금 충돌에도 "라벨이 없는 영상입니다" 다이얼로그가 떠서, 사용자가 확인을
     * 누르면 {@code noLabelConfirmed=true} 재요청 → 400 으로 끝나는 오도 경로가 된다. 사유를
     * errorCode 로 구분할 수 있게 전용 코드를 둔다(메시지 문자열 매칭 금지).
     */
    REVIEW_NO_LABEL(HttpStatus.CONFLICT, "라벨이 없는 영상입니다."),
    NOT_REVIEWED(HttpStatus.BAD_REQUEST, "검수가 완료되지 않은 영상이 포함되어 있습니다."),
    LENGTH_REQUIRED(HttpStatus.LENGTH_REQUIRED, "Content-Length 헤더가 필요합니다."),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "요청 페이로드가 허용 크기를 초과했습니다."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청 횟수가 제한을 초과했습니다."),
    GONE(HttpStatus.GONE, "리소스가 만료되었거나 더 이상 존재하지 않습니다."),
    PRECONDITION_FAILED(HttpStatus.PRECONDITION_FAILED, "요청 전제 조건을 충족하지 않습니다."),
    EXTERNAL_API_ERROR(HttpStatus.BAD_GATEWAY, "외부 API 호출에 실패했습니다."),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "서비스를 일시적으로 사용할 수 없습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
