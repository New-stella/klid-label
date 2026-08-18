package kr.co.cudo.authoring.common.exception;

import kr.co.cudo.authoring.common.client.AiCallCancelledException;
import kr.co.cudo.authoring.common.response.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사용자 취소 응답이 <b>작업 잠금 충돌과 다른 상태코드</b>를 쓰는지 고정한다.
 *
 * <h3>무엇이 겹쳐 있었나</h3>
 * <p>취소는 처음에 409 로 나갔는데, <b>같은 추론 엔드포인트</b>가 이미 409 를 두 가지 잠금 사유로
 * 쓰고 있었다({@code AutolabelOnlineService} 의 «작업이 잠긴 영상입니다» ·
 * «이미 오토라벨링이 진행 중인 프레임입니다»). 한 상태코드에 «남이 잡고 있다» 와 «내가 그만뒀다» 가
 * 겹치면, 로그·지표·앞단 어디서도 둘을 가를 수 없다.
 *
 * <h3>왜 422 인가 (선택 근거)</h3>
 * <ul>
 *   <li><b>409 유지 불가</b> — 위 충돌. 게다가 화면의 공용 헬퍼가 400/409/412 를 «서버 문구를 그대로
 *       사용자에게 보여도 되는 코드» 로 다루므로, 취소가 409 면 스스로 멈춘 사용자에게
 *       «요청이 취소되었습니다» 가 <b>오류 문구</b>로 뜰 수 있다. 「취소는 오류가 아니다」와 정면 충돌.</li>
 *   <li><b>499 아님</b> — 의미는 가장 정확하지만(nginx·gRPC CANCELLED 관례) IANA 미등록이다. 앞단이
 *       두 형상(Caddy·nginx)이고, nginx 는 <b>자기 자신이</b> 클라이언트 이탈에 499 를 적으므로 상류
 *       499 와 구분이 사라진다 — 코드를 나눈 목적인 «가를 수 있음» 이 앞단에서 도로 무너진다.</li>
 *   <li><b>5xx 아님</b> — 취소는 서버 실패가 아니다. 5xx 면 앞단·감시가 장애로 집계한다.</li>
 *   <li><b>408·410 아님</b> — 408 은 재시도를 유도하고(취소한 추론이 되살아난다), 410 은 이미
 *       «세션 만료» 로 쓰이는 데다 휴리스틱 캐시 대상이다(RFC 9110 §15.1).</li>
 *   <li><b>422 는</b> RFC 9110 표준이라 앞단이 모두 알고, 우리 API 가 한 번도 쓰지 않았으며,
 *       «요청은 이해했으나 그 지시를 끝까지 수행하지 못했다» 가 사실 그대로다.</li>
 * </ul>
 *
 * <p>세밀한 구분은 상태코드가 아니라 <b>본문의 {@code errorCode}</b> 가 진다 — 그래서 상태코드는
 * «겹치지 않고 해롭지 않은 것» 이면 충분하다.
 */
class AiCancelErrorCodeTest {

    /** 취소로 쓰기로 한 값. 바꾸려면 위 근거를 먼저 뒤집어야 한다. */
    private static final HttpStatus EXPECTED = HttpStatus.UNPROCESSABLE_ENTITY;

    /**
     * 화면 공용 헬퍼가 «서버 문구를 그대로 노출» 하는 상태코드
     * ({@code frontend/src/lib/api/resolveApiMessage.ts} 의 {@code USER_FACING_STATUSES}).
     */
    private static final Set<Integer> USER_FACING = Set.of(400, 409, 412);

    /** 휴리스틱 캐시 대상(RFC 9110 §15.1) — 취소가 캐시되면 뒤 요청까지 취소로 응답될 수 있다. */
    private static final Set<Integer> HEURISTICALLY_CACHEABLE = Set.of(404, 405, 410, 414);

    @Test
    @DisplayName("취소는_작업_잠금_충돌과_다른_상태코드를_쓴다")
    void 취소는_작업_잠금_충돌과_다른_상태코드를_쓴다() {
        assertThat(ErrorCode.AI_REQUEST_CANCELLED.status()).isEqualTo(EXPECTED);
        assertThat(ErrorCode.AI_REQUEST_CANCELLED.status()).isNotEqualTo(ErrorCode.CONFLICT.status());
    }

    @Test
    @DisplayName("취소_상태코드는_다른_어떤_오류코드도_쓰지_않는_전용값이다")
    void 취소_상태코드는_다른_어떤_오류코드도_쓰지_않는_전용값이다() {
        Set<ErrorCode> others = EnumSet.allOf(ErrorCode.class);
        others.remove(ErrorCode.AI_REQUEST_CANCELLED);

        assertThat(others)
                .isNotEmpty()
                .allSatisfy(code -> assertThat(code.status())
                        .as("%s 가 취소와 같은 상태코드를 쓰면 사유가 다시 겹친다", code.name())
                        .isNotEqualTo(ErrorCode.AI_REQUEST_CANCELLED.status()));
    }

    @Test
    @DisplayName("취소_상태코드는_화면이_서버_문구를_그대로_노출하는_코드가_아니다")
    void 취소_상태코드는_화면이_서버_문구를_그대로_노출하는_코드가_아니다() {
        assertThat(USER_FACING)
                .as("취소가 이 집합에 들면 스스로 멈춘 사용자에게 서버 문구가 오류로 뜬다")
                .doesNotContain(ErrorCode.AI_REQUEST_CANCELLED.status().value());
    }

    @Test
    @DisplayName("취소_상태코드는_자동_재시도되거나_캐시되는_코드가_아니다")
    void 취소_상태코드는_자동_재시도되거나_캐시되는_코드가_아니다() {
        int value = ErrorCode.AI_REQUEST_CANCELLED.status().value();

        assertThat(value)
                .as("408 은 재시도를 유도해 취소한 추론이 되살아난다")
                .isNotEqualTo(HttpStatus.REQUEST_TIMEOUT.value());
        assertThat(HEURISTICALLY_CACHEABLE)
                .as("캐시되면 뒤 요청까지 취소 응답을 받는다")
                .doesNotContain(value);
    }

    @Test
    @DisplayName("취소는_서버_실패로_집계되는_5xx_가_아니다")
    void 취소는_서버_실패로_집계되는_5xx_가_아니다() {
        // 타입을 못 박지 않는다 — 타입 자체를 검사하는 것은 아래 전용 가드의 일이고,
        // 여기서 못 박으면 타입이 바뀔 때 이 시험이 «컴파일 실패» 로 먼저 터져 그 가드를 가린다.
        var status = ErrorCode.AI_REQUEST_CANCELLED.status();

        assertThat(status.is5xxServerError())
                .as("5xx 면 앞단·감시가 취소를 장애로 센다 — 취소는 정상 동선이다")
                .isFalse();
        assertThat(status.is4xxClientError()).isTrue();
    }

    @Test
    @DisplayName("취소_응답은_사유코드를_본문에_실어_상태코드만으로_구분하지_않는다")
    void 취소_응답은_사유코드를_본문에_실어_상태코드만으로_구분하지_않는다() {
        ResponseEntity<ApiResponse<Object>> response =
                new GlobalExceptionHandler().handleCustom(new AiCallCancelledException());

        assertThat(response.getStatusCode()).isEqualTo(EXPECTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().errorCode()).isEqualTo(ErrorCode.AI_REQUEST_CANCELLED.name());
    }

    @Test
    @DisplayName("같은_추론_경로의_잠금_충돌과_취소는_상태코드와_사유코드가_모두_다르다")
    void 같은_추론_경로의_잠금_충돌과_취소는_상태코드와_사유코드가_모두_다르다() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        // 추론 엔드포인트가 실제로 내는 잠금 충돌 — AutolabelOnlineService(requireNotBlocked).
        ResponseEntity<ApiResponse<Object>> locked =
                handler.handleCustom(new CustomException(ErrorCode.CONFLICT, "작업이 잠긴 영상입니다."));
        ResponseEntity<ApiResponse<Object>> cancelled =
                handler.handleCustom(new AiCallCancelledException());

        assertThat(cancelled.getStatusCode()).isNotEqualTo(locked.getStatusCode());
        assertThat(cancelled.getBody()).isNotNull();
        assertThat(locked.getBody()).isNotNull();
        assertThat(cancelled.getBody().errorCode()).isNotEqualTo(locked.getBody().errorCode());
    }

    /**
     * 등록되지 않은 코드(499 등)를 쓰려면 {@code ErrorCode} 의 상태 타입을 {@code HttpStatus} 에서
     * {@code HttpStatusCode} 로 넓혀야 한다 — {@code HttpStatus} 는 IANA 등록 코드만 담기 때문이다.
     * 그 타입이 곧 «표준 코드만 쓴다» 는 강제 장치이므로, 넓히는 변경이 이 가드를 깨서 위 근거를
     * 다시 논하게 만든다(값만 검사하면 타입이 보장해 주는 것이라 무엇을 바꿔도 통과한다).
     */
    @Test
    @DisplayName("취소_상태코드는_표준_등록_코드만_담는_타입으로_선언돼_있다")
    void 취소_상태코드는_표준_등록_코드만_담는_타입으로_선언돼_있다() throws NoSuchMethodException {
        assertThat(ErrorCode.class.getMethod("status").getReturnType())
                .as("HttpStatusCode 로 넓히면 499 같은 미등록 코드가 들어올 수 있다 — 앞단이 제각각 해석한다")
                .isEqualTo(HttpStatus.class);

        int value = ErrorCode.AI_REQUEST_CANCELLED.status().value();
        assertThat(Arrays.stream(HttpStatus.values()).anyMatch(s -> s.value() == value)).isTrue();
    }
}
