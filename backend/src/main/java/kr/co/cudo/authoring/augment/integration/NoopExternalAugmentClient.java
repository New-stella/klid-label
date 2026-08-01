package kr.co.cudo.authoring.augment.integration;

import kr.co.cudo.authoring.augment.integration.dto.GenAiCancelResponse;
import kr.co.cudo.authoring.augment.integration.dto.GenAiJobResultsResponse;
import kr.co.cudo.authoring.augment.integration.dto.GenAiJobStatusResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;

/**
 * 외부 미연동 no-op ExternalAugmentClient — {@code authoring.augment.external.mode=noop} 명시 전용.
 *
 * <p>실제 외부 호출 없이 로그만 남긴다. 실벤더 미연동인 dev/stg/prd 가 이 구현체를 쓴다
 * (각 프로파일 yml 에 {@code mode: noop} 을 <b>명시</b>해 기본값 변경이 운영으로 새지 않게 한다).
 *
 * <p>Phase 7-A1 변경: 과거에는 {@code @Primary} + {@code matchIfMissing=true} 로 <b>전 환경 기본</b>
 * 이었다. 그 결과 증강 요청이 HTTP 로 나간 적이 한 번도 없었다(E-ISSUE-03). 이제 기본은
 * {@link HttpExternalAugmentClient} 이고 본 구현체는 "명시 비활성" 전용으로 강등됐다.
 *
 * <p>job_id 는 외부가 발급하는 값이므로 <b>가짜 ID 를 만들지 않는다</b> —
 * {@link AugmentSubmitResult#skipped()} 로 null job_id 를 반환해 미위탁 사실을 그대로 드러낸다.
 *
 * <p>로그 안전(CWE-117): 출력 값은 CR/LF/TAB 을 제거한다. 파일 절대경로·시크릿은 출력하지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "authoring.augment.external.mode", havingValue = "noop")
public class NoopExternalAugmentClient implements ExternalAugmentClient {

    /**
     * 로그 sanitize — 개행 계열 제거(CWE-117). {@code U+2028}/{@code U+2029} 도 포함한다(로그 소비자가
     * 줄바꿈으로 해석하는 위조 벡터 — {@code VisibleTextNormalizer} 와 동일 판단).
     */
    private static final Pattern LOG_UNSAFE = Pattern.compile("[\\r\\n\\t\\u2028\\u2029]");

    /**
     * 외부 문자열 로그 상한 — <b>이 구현체가 dev/stg/prd 기본</b>이라 오히려 노출면이 넓다.
     * 여기 들어오는 job_id 는 형식 검증을 거치지 않으므로 길이가 무제한이다(CWE-770).
     */
    private static final int LOG_VALUE_MAX = 50;

    @Override
    public boolean syncDecision(Long dataAugSn, String decision, String reasonOrNull) {
        // 외부 SFR-07 시스템 미연결 — mock 응답. 실제 연동 시 WebClient + Resilience4j 적용 예정.
        log.info("[Augment] external decision sync (noop) dataAugSn={} decision={} reason={}",
                dataAugSn, safe(decision), reasonOrNull == null ? "" : safe(reasonOrNull));
        return true;
    }

    /** 구독 시점에만 로그를 남긴다 — 조립만 하고 구독하지 않은 요청이 "나갔다" 로 보이지 않게 한다. */
    @Override
    public Mono<AugmentSubmitResult> requestAugment(AugmentSubmitCommand command) {
        return Mono.fromSupplier(() -> {
            // 외부 미연동 — 위탁하지 않는다. 파일 경로는 로그에 남기지 않고 개수만 남긴다.
            log.info("[Augment] external request (noop) originAugSn={} augType={} jobSeq={}/{} inputCount={}",
                    command.originAugSn(), safe(command.augType()),
                    command.jobSeq(), command.jobCount(), command.inputFiles().size());
            return AugmentSubmitResult.skipped();
        });
    }

    /**
     * 조회/취소도 <b>가짜 응답을 조립하지 않는다</b> — 미연동 신호({@code EXTERNAL_DISABLED})만 돌려준다.
     *
     * <p>dev/stg/prd 기본이 이 구현체다. 여기서 그럴듯한 {@code RUNNING}/{@code SUCCEEDED} 를 만들면
     * 존재하지 않는 job 을 성공 확정하게 되어 폴링·화면이 통째로 오작동한다(위탁의
     * {@link AugmentSubmitResult#skipped()} 와 같은 원칙).
     */
    @Override
    public Mono<AugmentQueryResult<GenAiJobStatusResponse>> fetchJobStatus(String externalJobId) {
        return notLinked("status", externalJobId);
    }

    @Override
    public Mono<AugmentQueryResult<GenAiJobResultsResponse>> fetchJobResults(String externalJobId) {
        return notLinked("results", externalJobId);
    }

    @Override
    public Mono<AugmentQueryResult<GenAiCancelResponse>> cancelJob(AugmentCancelCommand command) {
        return notLinked("cancel", command.externalJobId());
    }

    /** 구독 시점에만 로그를 남긴다(조립만 하고 버린 호출이 "나갔다" 로 보이지 않게). */
    private <T> Mono<AugmentQueryResult<T>> notLinked(String operation, String externalJobId) {
        return Mono.fromSupplier(() -> {
            log.info("[Augment] genai {} (noop) job_id={}", operation, logValue(externalJobId));
            return AugmentQueryResult.skipped(AugmentQueryResult.SkipReason.EXTERNAL_DISABLED);
        });
    }

    private static String safe(String s) {
        if (s == null) return "null";
        return LOG_UNSAFE.matcher(s).replaceAll("_");
    }

    /** 외부에서 온 문자열의 단일 로그 통로 — sanitize + 길이 상한. */
    private static String logValue(String s) {
        if (s == null) return "null";
        return safe(s.length() <= LOG_VALUE_MAX ? s : s.substring(0, LOG_VALUE_MAX) + "…(생략)");
    }
}
