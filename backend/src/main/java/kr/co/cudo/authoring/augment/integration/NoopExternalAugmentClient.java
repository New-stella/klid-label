package kr.co.cudo.authoring.augment.integration;

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

    private static final Pattern LOG_UNSAFE = Pattern.compile("[\\r\\n\\t]");

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

    private static String safe(String s) {
        if (s == null) return "null";
        return LOG_UNSAFE.matcher(s).replaceAll("_");
    }
}
