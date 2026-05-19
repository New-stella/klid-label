package kr.co.cudo.authoring.common.exception;

public class CustomException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Object details;

    public CustomException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
        this.details = null;
    }

    public CustomException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.details = null;
    }

    public CustomException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.details = null;
    }

    /**
     * 에러 응답 본문 data 필드에 부가 정보를 함께 반환할 때 사용.
     * 예) NOT_REVIEWED 응답에 blockedVideoIds 포함.
     */
    public CustomException(ErrorCode errorCode, String message, Object details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public Object getDetails() {
        return details;
    }
}
