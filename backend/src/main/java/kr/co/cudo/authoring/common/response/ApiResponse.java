package kr.co.cudo.authoring.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import kr.co.cudo.authoring.common.exception.ErrorCode;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ApiResponse<T>(boolean success, T data, String message, String errorCode) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, data, message, null);
    }

    public static <T> ApiResponse<T> error(ErrorCode code, String message) {
        return new ApiResponse<>(false, null, message, code.name());
    }

    public static <T> ApiResponse<T> error(ErrorCode code) {
        return new ApiResponse<>(false, null, code.defaultMessage(), code.name());
    }
}
