package kr.co.cudo.authoring.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 관제 인입 <b>일괄</b> 재큐 요청 (설계 §6-0-1-b).
 *
 * <p>대상 조건은 <b>처리상태 = {@code FAILED}</b> 하나이며 요청으로 넓힐 수 없다. 상태 조건을 요청
 * 파라미터로 받으면 {@code DONE}(적재 완료) 행을 되살려 중복 적재를 유발할 수 있기 때문이다
 * (fail-closed — 요청은 "몇 건까지"만 정한다).
 *
 * @param limit 이번 호출이 되살릴 최대 건수. 미지정 시 {@link #DEFAULT_LIMIT}.
 *              <b>상한 필수</b>(CWE-770) — 무제한 조회·갱신은 한 트랜잭션이 수십만 행을 잠가 인입
 *              폴링과 관제 INSERT 를 함께 멈춘다. 남은 건수는 응답을 보고 재호출로 이어서 처리한다.
 */
@Schema(description = "관제 인입 일괄 재큐 요청 — 대상은 FAILED 행 고정, 건수만 지정한다")
public record ControlIngestRequeueBulkRequest(
        @Schema(description = "이번 호출이 되살릴 최대 건수(1~500). 미지정 시 100", example = "100")
        @Min(value = 1, message = "limit 은 1 이상이어야 합니다.")
        @Max(value = MAX_LIMIT, message = "limit 은 " + MAX_LIMIT + " 이하여야 합니다.")
        Integer limit) {

    /** 1회 호출 상한 — 이 값을 넘는 요청은 400 으로 거부한다(무제한 갱신 차단). */
    public static final int MAX_LIMIT = 500;

    /** 미지정 시 기본 건수 — 스캔 tick 상한(100)과 같은 자릿수로 두어 회수 속도를 예측 가능하게 한다. */
    public static final int DEFAULT_LIMIT = 100;

    /** 미지정(null)을 기본값으로 접은 실효 건수. 범위 검증은 {@code @Valid} 가 이미 수행했다. */
    public int effectiveLimit() {
        return limit == null ? DEFAULT_LIMIT : limit;
    }
}
