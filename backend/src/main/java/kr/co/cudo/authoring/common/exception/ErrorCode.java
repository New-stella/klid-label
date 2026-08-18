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
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 요청 메서드입니다."),
    LENGTH_REQUIRED(HttpStatus.LENGTH_REQUIRED, "Content-Length 헤더가 필요합니다."),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "요청 페이로드가 허용 크기를 초과했습니다."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청 횟수가 제한을 초과했습니다."),
    GONE(HttpStatus.GONE, "리소스가 만료되었거나 더 이상 존재하지 않습니다."),
    PRECONDITION_FAILED(HttpStatus.PRECONDITION_FAILED, "요청 전제 조건을 충족하지 않습니다."),
    /**
     * 사용자가 취소해서 중단된 요청 — 실패가 아니라 <b>정상 동선</b>이다.
     *
     * <h3>왜 409 에서 옮겼나</h3>
     * <p>취소가 409 였을 때 <b>같은 추론 엔드포인트</b>가 이미 409 를 잠금 사유로 쓰고 있었다
     * ({@code AutolabelOnlineService} 의 «작업이 잠긴 영상입니다» · «이미 오토라벨링이 진행 중인
     * 프레임입니다»). 한 상태코드에 «남이 잡고 있다» 와 «내가 그만뒀다» 가 겹쳐, 로그·지표·앞단
     * 어디서도 둘을 가를 수 없었다. 더 나쁜 것은 화면 쪽이다 — 공용 헬퍼가 400/409/412 를 «서버
     * 문구를 그대로 사용자에게 보여도 되는 코드» 로 다루므로, 스스로 멈춘 사용자에게
     * «요청이 취소되었습니다» 가 <b>오류 문구</b>로 뜰 수 있었다.
     *
     * <h3>왜 422 인가</h3>
     * <ul>
     *   <li><b>499 아님</b> — 의미는 가장 정확하지만(nginx·gRPC {@code CANCELLED} 관례) IANA
     *       미등록이다. 앞단이 두 형상(Caddy·nginx)인데 nginx 는 <b>자기 자신이</b> 클라이언트
     *       이탈에 499 를 적으므로, 상류 499 와 앞단 499 가 로그에서 한 값으로 뭉개진다 — 코드를
     *       나눈 목적이 앞단에서 도로 무너진다.</li>
     *   <li><b>5xx 아님</b> — 취소는 서버 실패가 아니다. 5xx 면 감시·앞단이 장애로 집계한다.</li>
     *   <li><b>408·410 아님</b> — 408 은 재시도를 유도해 방금 취소한 추론을 되살리고, 410 은 이미
     *       «세션 만료» 로 쓰이는 데다 휴리스틱 캐시 대상이다(RFC 9110 §15.1).</li>
     *   <li><b>422 는</b> RFC 9110 표준이라 어느 앞단도 알고, 우리 API 가 한 번도 쓰지 않은 값이며,
     *       «요청은 이해했으나 그 지시를 끝까지 수행하지 못했다» 가 사실 그대로다.</li>
     * </ul>
     *
     * <p>⚠ 세밀한 구분은 상태코드가 아니라 <b>본문의 {@code errorCode}</b> 가 진다. 상태코드는
     * «겹치지 않고 해롭지 않은 값» 이면 충분하며, 이 값을 바꾸려면 위 근거를 먼저 뒤집어야 한다
     * (회귀 고정: {@code AiCancelErrorCodeTest}).
     */
    AI_REQUEST_CANCELLED(HttpStatus.UNPROCESSABLE_ENTITY, "요청이 취소되었습니다."),
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
