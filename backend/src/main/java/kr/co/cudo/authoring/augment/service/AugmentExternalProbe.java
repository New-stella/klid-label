package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.dto.AugmentProgressUnavailableReason;
import kr.co.cudo.authoring.augment.integration.AugmentQueryResult;
import kr.co.cudo.authoring.common.client.NonRetryableExternalException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 외부 증강 <b>조회/취소</b> 호출을 동기 API 경계로 옮기는 어댑터 — 결과를 "값" 으로 돌려준다.
 *
 * <h3>★ 블로킹은 <b>요청 스레드에서만</b> 일어난다 (S3 — Phase C-3 실사고 재발 방지)</h3>
 * <p>{@code ExternalAugmentClient} 는 {@link Mono} 를 돌려주는데 컨트롤러는 동기
 * {@code ApiResponse} 다. 이 프로젝트에는 위탁 경로가 {@code .block()} 으로 <b>배치 풀</b>
 * ({@code batchAsyncExecutor}, core 2 · CallerRuns)을 고갈시켜 역압이 커밋 스레드까지 물고 늘어졌던
 * 실사고가 있다. 그래서 규칙은 다음과 같다:
 * <ul>
 *   <li>{@link #query(Mono, String, Object)} 는 <b>서블릿 요청 스레드에서만</b> 호출한다.
 *       reactor 스레드(이벤트 루프·parallel)에서 부르면 Reactor 가 {@code IllegalStateException} 을
 *       던지고, 우리는 그것을 {@link AugmentProgressUnavailableReason#TRANSIENT_ERROR} 로 degrade 한다
 *       — 즉 <b>잘못된 스레드에서 부르면 조용히 굶는 대신 즉시 드러난다</b>.</li>
 *   <li>구독 자체는 reactor 스레드를 <b>점유하지 않는다</b>(요청 스레드가 대기할 뿐이다). 따라서
 *       동시 폴링이 늘어도 reactor 풀이 마르지 않는다.</li>
 *   <li>{@link #blockTimeout} 상한을 항상 건다 — 클라이언트 내부 타임아웃(기본 10s)에 재시도가
 *       곱해질 수 있어, 상한이 없으면 요청 스레드가 예측 불가능하게 묶인다(CWE-770).</li>
 * </ul>
 *
 * <h3>사유 구분 (S6)</h3>
 * <p>미연동({@code EXTERNAL_DISABLED})과 진짜 장애(서킷 open·타임아웃·계약 위반)를 <b>다른 값</b>으로
 * 돌려준다. 장애는 WARN 으로 실측 근거를 남긴다 — 그러지 않으면 벤더 장애가 "미연동" 으로 위장된다.
 */
@Slf4j
@Component
public class AugmentExternalProbe {

    /**
     * 외부 조회 1회의 결과.
     *
     * <h3>{@code externalStatusCode} — 결정적 4xx 를 타임아웃과 구분한다 (DEV_FIX MED-7)</h3>
     * <p>구 구현은 <b>모든</b> {@code RuntimeException} 을 {@link AugmentProgressUnavailableReason#TRANSIENT_ERROR}
     * 로 뭉갰다. 그러면 벤더의 409({@code STATE_CONFLICT})·404({@code JOB_NOT_FOUND})가 네트워크 타임아웃과
     * 같은 값이 되어, 취소 경로가 이를 "실패" 로 세고 <b>수행 불가능한 재시도</b>를 안내했다(재클릭하면
     * 이미 CANCELED 라 "취소할 수 없습니다" 가 뜬다 — CLAUDE.md "안내는 실제 수행 가능한 동선만 말한다"
     * 위반). 상태 코드를 값으로 남겨 소비 계층이 정확히 분기하게 한다.
     *
     * @param payload            성공 응답(그 외 null)
     * @param reason             실패/미연동 사유(성공이면 null)
     * @param externalStatusCode 외부 4xx 상태 코드(그 외 null) — 결정적 거부 판별용
     */
    public record Probe<T>(T payload, AugmentProgressUnavailableReason reason, Integer externalStatusCode) {

        public Probe(T payload, AugmentProgressUnavailableReason reason) {
            this(payload, reason, null);
        }

        public boolean isSuccess() {
            return payload != null;
        }

        /**
         * 외부가 <b>결정적으로</b> 거부했는가(4xx) — 재시도해도 결과가 같다.
         * 이 경우 사용자에게 재시도를 권하면 안 된다.
         */
        public boolean isDeterministicRejection() {
            return externalStatusCode != null;
        }

        /**
         * 외부에 <b>취소할 대상이 없다</b>고 확정된 응답인가 — 404({@code JOB_NOT_FOUND}) ·
         * 409({@code STATE_CONFLICT}).
         *
         * <p>둘 다 "그 job 은 외부에서 이미 종결됐거나 존재하지 않는다" 는 뜻이라, 취소 관점에서는
         * <b>사실상 성공</b>이다(보낼 것이 남아 있지 않다). Phase 2 가 이 두 응답을 "정상 흐름의 결정적
         * 응답" 으로 분류해 서킷 집계에서 제외한 것과 같은 판단이다.
         */
        public boolean isAlreadyTerminalAtVendor() {
            return externalStatusCode != null
                    && (externalStatusCode == 404 || externalStatusCode == 409);
        }
    }

    private final Duration blockTimeout;

    public AugmentExternalProbe(
            @Value("${authoring.augment.external.query-block-timeout-seconds:15}") long blockTimeoutSeconds) {
        this.blockTimeout = Duration.ofSeconds(Math.max(1, blockTimeoutSeconds));
    }

    /**
     * 외부 조회/취소를 수행하고 결과를 값으로 돌려준다. <b>예외를 던지지 않는다</b> —
     * 외부 장애가 화면을 500 으로 깨뜨리지 않게 하는 것이 이 어댑터의 목적이다.
     *
     * @param call      클라이언트 호출({@code Mono}) — 구독은 이 메서드가 한다
     * @param operation 로그용 연산명(우리 리터럴)
     * @param key       로그용 상관키(내부 식별자 — 외부 문자열이 아니다)
     */
    public <T> Probe<T> query(Mono<AugmentQueryResult<T>> call, String operation, Object key) {
        return query(call, operation, key, blockTimeout);
    }

    /**
     * 블로킹 상한을 <b>호출자가 지정</b>하는 변형 — 요청 단위 데드라인({@link AugmentRequestBudget})
     * 안에서 여러 번 호출할 때 쓴다.
     *
     * <p>지정 값은 기본 상한({@link #blockTimeout})을 <b>넘길 수 없다</b> — 넘길 수 있게 두면 예산 계산
     * 실수 하나로 개별 호출 상한이 통째로 무력화된다(CWE-770).
     *
     * @param maxBlock 이 호출에 허용할 최대 블로킹 시간(잔여 예산)
     */
    public <T> Probe<T> query(Mono<AugmentQueryResult<T>> call, String operation, Object key,
                              Duration maxBlock) {
        Duration limit = maxBlock == null || maxBlock.compareTo(blockTimeout) > 0
                ? blockTimeout : maxBlock;
        try {
            AugmentQueryResult<T> result = call.block(limit);
            if (result == null) {
                // 빈 완료(onComplete only) — 판단 근거가 없다. 미연동으로 오해하면 안 된다.
                log.warn("[Augment] genai {} 응답 없음 — degrade key={}", operation, key);
                return new Probe<>(null, AugmentProgressUnavailableReason.TRANSIENT_ERROR);
            }
            if (result.isSkipped()) {
                return new Probe<>(null, reasonOf(result.skipReason()));
            }
            return new Probe<>(result.payload(), null);
        } catch (RuntimeException e) {
            // 서킷 open(CallNotPermittedException) · 타임아웃 · 5xx · 계약 위반 · 잘못된 스레드 블로킹.
            // 예외 원문은 남기지 않는다(외부 응답 조각 포함 가능 — CWE-209). 종류만 남겨 진단한다.
            Integer statusCode = statusCodeOf(e);
            log.warn("[Augment] genai {} 실패 — progress degrade key={} errType={} status={}",
                    operation, key, e.getClass().getSimpleName(), statusCode);
            return new Probe<>(null, AugmentProgressUnavailableReason.TRANSIENT_ERROR, statusCode);
        }
    }

    /**
     * 원인 체인에서 외부 4xx 상태 코드를 찾는다 — 없으면 null(타임아웃·5xx·서킷 open 등).
     *
     * <p>체인을 훑는 이유는 Reactor 가 예외를 감싸 던지는 경우가 있기 때문이다
     * ({@code HttpExternalAugmentClient#isDecodingFailure} 가 같은 이유로 체인을 훑는다).
     * <b>메시지 문자열을 파싱하지 않는다</b> — 문구가 바뀌면 조용히 오분류된다.
     */
    private static Integer statusCodeOf(Throwable e) {
        for (Throwable c = e; c != null && c != c.getCause(); c = c.getCause()) {
            if (c instanceof NonRetryableExternalException nre && nre.getStatusCode() != null) {
                return nre.getStatusCode();
            }
        }
        return null;
    }

    /**
     * 스킵 사유 매핑.
     *
     * <p>{@code LOCAL_TERMINAL} 은 <b>우리가</b> 이미 종결로 판정해 왕복을 생략한 경우라 장애가 아니다.
     * 진행률 관점에서는 "종결" 이므로 degrade 사유를 만들지 않는다(null).
     */
    private static AugmentProgressUnavailableReason reasonOf(AugmentQueryResult.SkipReason skipReason) {
        return skipReason == AugmentQueryResult.SkipReason.EXTERNAL_DISABLED
                ? AugmentProgressUnavailableReason.NOOP
                : null;
    }
}
