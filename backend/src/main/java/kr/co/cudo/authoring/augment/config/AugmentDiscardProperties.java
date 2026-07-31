package kr.co.cudo.authoring.augment.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.LocalDateTime;

/**
 * 증강 파생영상 <b>폐기 유예 · 실삭제 스윕</b> 설정 ({@code authoring.augment.discard.*}) — Phase 7.
 *
 * <h2>fail-closed 검증 — 오설정이면 기동을 거부한다 (H9)</h2>
 * <p>이 설정이 무너지는 방향은 단 하나, <b>유예가 사라지는 쪽</b>이다. {@code grace-days} 가 0/음수로
 * 들어오면 "반려 즉시 영구 삭제" 가 되어 되돌릴 수 없다. 이 리포에는 {@code .env.example} 의 <b>빈 값</b>이
 * {@code ${KEY:default}} 를 무력화해 운영에서만 조용히 다르게 동작한 실사고가 있다 — 그래서 경고가 아니라
 * {@code @PostConstruct} 기동 차단이다({@code QuartzClusteringGuard}·{@code VlmUrlPolicy} 와 동형).
 *
 * <p>판정은 순수 메서드({@link #validate()})라 컨테이너 없이도 단위 검증할 수 있고, 실제 배선은
 * 바인딩 컨텍스트 테스트로 고정한다.
 *
 * @param enabled     스윕 활성화. 끄면 표식만 쌓이고 실삭제가 일어나지 않는다(복구는 계속 가능).
 * @param graceDays   유예 기간(일). 반려({@code DSCD_DT}) 후 이 기간이 지나야 실삭제 대상이 된다.
 * @param intervalMs  스윕 주기(ms). 하한 {@value #MIN_INTERVAL_MS}.
 * @param initialDelayMs 기동 후 첫 스윕까지 지연(ms).
 * @param batchSize   tick 당 처리 상한(자원 소모 방어, CWE-770).
 * @param claimStaleMinutes 클레임 후 이 시간이 지나도 집행이 끝나지 않으면 스트랜드 클레임으로 보고
 *                          재클레임을 허용한다(클레임 직후 프로세스 사망 시 영구 미집행 방지).
 * @param fileCleanupMaxAttempts 파일 정리 재시도 상한(회). 초과하면 그 비석을 재시도 큐에서 <b>제외</b>하고
 *                          "사람 개입 필요"로 종결 표시한다 — 상한이 없으면 영구히 정리되지 않는 비석이
 *                          오래된 순 배치의 앞자리를 점유해 이후 비석의 파일 정리를 전면 정지시킨다.
 */
@ConfigurationProperties(prefix = "authoring.augment.discard")
public record AugmentDiscardProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("7") int graceDays,
        @DefaultValue("3600000") long intervalMs,
        @DefaultValue("600000") long initialDelayMs,
        @DefaultValue("50") int batchSize,
        @DefaultValue("60") int claimStaleMinutes,
        @DefaultValue("5") int fileCleanupMaxAttempts
) {

    /** 유예 하한(일). 0 이하는 "반려 즉시 영구 삭제" 이므로 설정으로도 도달할 수 없어야 한다. */
    public static final int MIN_GRACE_DAYS = 1;
    /** 스윕 주기 하한(ms) — 과도한 폴링 방지. */
    public static final long MIN_INTERVAL_MS = 60_000L;
    /** 클레임 만료 하한(분) — 너무 짧으면 집행 중인 건을 다른 노드가 가로챈다. */
    public static final int MIN_CLAIM_STALE_MINUTES = 5;
    /** 파일 정리 재시도 상한의 하한(회) — 0 이면 첫 실패에 곧바로 포기해 NAS 순단 한 번에 고아가 남는다. */
    public static final int MIN_FILE_CLEANUP_MAX_ATTEMPTS = 1;

    /**
     * <b>유예 강제 클램프</b> — 실삭제 cutoff 는 <b>넓힐 수만 있고 좁힐 수 없다</b> (DEV_FIX FIX-1).
     *
     * <h3>왜 파라미터를 그대로 믿으면 안 되는가</h3>
     * <p>최종 DELETE 의 다른 두 조건({@code ORGNL_RAW_SN IS NOT NULL}, {@code NOT EXISTS(APPROVED)})은
     * SQL 리터럴이라 호출처가 무엇을 넘기든 무력화되지 않는데, <b>유예만 파라미터</b>였다. 즉 "7일 유예"가
     * {@code run()} 이라는 호출처 <b>한 곳에만</b> 존재했다 — 나중에 누군가 "즉시 폐기" 운영 버튼이나 dev
     * 트리거를 만들며 {@code purgeExpired(now())} 를 부르면 <b>반려 직후 DB 행과 NAS 파일이 영구 삭제</b>되고
     * 클레임·최종 DELETE 어느 가드도 걸리지 않는다(셋 다 같은 cutoff 를 신뢰한다).
     *
     * <p>이 리포는 "게이트를 호출처마다 배선하면 반드시 샌다" 로 반복해서 사고를 냈다. 그래서 판정을
     * <b>설정 소유자(이 레코드)에 한 곳</b>으로 모으고, 집행 경로 두 곳(스윕 진입 · 트랜잭션 서비스 진입)이
     * 모두 이 메서드를 통과하게 한다.
     *
     * @param requested 호출자가 제시한 cutoff (null 이면 하드 컷오프를 그대로 쓴다)
     * @return {@code min(requested, now - graceDays)} — 유예를 좁히는 방향의 값은 하드 컷오프로 되돌린다
     */
    public LocalDateTime clampCutoff(LocalDateTime requested) {
        LocalDateTime hardCutoff = LocalDateTime.now().minusDays(graceDays);
        return (requested == null || requested.isAfter(hardCutoff)) ? hardCutoff : requested;
    }

    @PostConstruct
    public void validate() {
        if (graceDays < MIN_GRACE_DAYS) {
            throw new IllegalStateException(
                    "authoring.augment.discard.grace-days=" + graceDays + " 는 허용되지 않습니다"
                            + " (최소 " + MIN_GRACE_DAYS + "일). 0 이하는 반려 즉시 영구 삭제를 뜻하며,"
                            + " 잘못 누른 반려를 되돌릴 수 없게 만듭니다. 값을 비우면(빈 문자열) 기본값이"
                            + " 적용되지 않으니 .env / application-{profile}.yml 에 값을 명시하세요.");
        }
        if (batchSize < 1) {
            throw new IllegalStateException(
                    "authoring.augment.discard.batch-size=" + batchSize + " 는 허용되지 않습니다 (최소 1).");
        }
        if (intervalMs < MIN_INTERVAL_MS) {
            throw new IllegalStateException(
                    "authoring.augment.discard.interval-ms=" + intervalMs + " 는 허용되지 않습니다"
                            + " (최소 " + MIN_INTERVAL_MS + "ms).");
        }
        if (claimStaleMinutes < MIN_CLAIM_STALE_MINUTES) {
            throw new IllegalStateException(
                    "authoring.augment.discard.claim-stale-minutes=" + claimStaleMinutes
                            + " 는 허용되지 않습니다 (최소 " + MIN_CLAIM_STALE_MINUTES + "분)."
                            + " 너무 짧으면 집행 중인 삭제를 다른 노드가 가로챕니다.");
        }
        if (fileCleanupMaxAttempts < MIN_FILE_CLEANUP_MAX_ATTEMPTS) {
            throw new IllegalStateException(
                    "authoring.augment.discard.file-cleanup-max-attempts=" + fileCleanupMaxAttempts
                            + " 는 허용되지 않습니다 (최소 " + MIN_FILE_CLEANUP_MAX_ATTEMPTS + "회)."
                            + " 0 이하는 첫 실패(NAS 순단 등)에 곧바로 포기해 고아 파일을 남깁니다.");
        }
    }
}
