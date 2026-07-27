package kr.co.cudo.authoring.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 라벨 좌표 <b>상한(이미지 경계) 검증</b> 관측 메트릭 (C-ISSUE-22 / DEV_FIX H4).
 *
 * <h3>왜 필요한가 (실측 근거)</h3>
 * 상한 검증은 프레임 이미지 파일에서 실측한 폭/높이를 기준으로 하는데, 파일을 열 수 없으면
 * <b>상한만 조용히 건너뛰고</b> 저장이 진행된다(정상 작업 차단 금지 정책). 문제는 그 skip 이 WARN 로그
 * 한 줄로만 남아 <b>"검증 통과"와 구분되지 않았다</b>는 점이다. 로컬 실측에서는 {@code ls_data_src} 의
 * 프레임 경로가 앱 base 와 규약이 달라(호스트 절대경로 vs 컨테이너 base) 전 프레임에서 skip 되었는데도
 * 운영 지표상으로는 아무 신호가 없었다.
 *
 * <p>그래서 skip 을 카운터로 노출한다 — 대시보드/알람에서 "상한 검증이 실효 중인가"를 볼 수 있게.
 *
 * <p>메트릭:
 * <ul>
 *   <li>{@code label.bounds.skipped}{@code {reason}} — 상한 검증을 건너뛴 프레임 저장 건수</li>
 *   <li>{@code label.bounds.resolved} — 기준값(폭/높이) 실측에 성공한 건수(캐시 적중 제외한 실측만)</li>
 * </ul>
 * reason ∈ {unreadable(파일 열림 실패/미지원/손상), unresolved(경로 해석 실패 — 경로 규약 불일치 포함)}
 * — 저카디널리티 고정 문자열만 사용한다(observability.md). 경로 원문/PII 는 태그에 넣지 않는다(CWE-209/359).
 */
@Component
public class LabelBoundsMetrics {

    /** 상한 검증 skip 건수. */
    private static final String SKIPPED = "label.bounds.skipped";
    /** 기준값 실측 성공 건수. */
    private static final String RESOLVED = "label.bounds.resolved";
    private static final String TAG_REASON = "reason";

    /** 프레임 이미지 경로 자체를 해석하지 못함(경로 부재·기준 경로 밖 등). */
    public static final String REASON_UNRESOLVED = "unresolved";
    /** 경로는 얻었으나 파일을 읽지 못함(부재·권한·손상·미지원 포맷). */
    public static final String REASON_UNREADABLE = "unreadable";

    private final MeterRegistry registry;

    public LabelBoundsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 상한 검증을 건너뛴 사실을 사유별로 계상한다. */
    public void incrementSkipped(String reason) {
        Counter.builder(SKIPPED)
                .tag(TAG_REASON, reason)
                .description("라벨 좌표 상한(이미지 경계) 검증을 건너뛴 건수 — 값이 늘면 상한 검증이 실효되지 않는 중")
                .register(registry)
                .increment();
    }

    /** 기준값(폭/높이) 실측에 성공한 사실을 계상한다(skip 과 대비해 실효 비율을 볼 수 있게). */
    public void incrementResolved() {
        Counter.builder(RESOLVED)
                .description("라벨 좌표 상한 기준값(이미지 폭/높이) 실측 성공 건수")
                .register(registry)
                .increment();
    }
}
