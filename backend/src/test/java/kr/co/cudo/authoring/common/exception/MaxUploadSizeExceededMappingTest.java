package kr.co.cudo.authoring.common.exception;

import kr.co.cudo.authoring.common.response.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * multipart 한도 초과 예외 → 표준 ApiResponse(413 PAYLOAD_TOO_LARGE) 매핑 검증.
 * 스택트레이스/내부 경로 비노출(CWE-209) — 고정 메시지만 반환한다.
 */
class MaxUploadSizeExceededMappingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("MaxUploadSizeExceededException_표준_응답_매핑")
    void mapsToPayloadTooLarge() {
        ResponseEntity<ApiResponse<Void>> res =
                handler.handleMaxUploadSizeExceeded(new MaxUploadSizeExceededException(20_971_520L));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        ApiResponse<Void> body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isFalse();
        assertThat(body.errorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE.name());
        // 내부 상세(스택/경로) 비노출 — 고정 메시지.
        assertThat(body.message()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE.defaultMessage());
    }
}
