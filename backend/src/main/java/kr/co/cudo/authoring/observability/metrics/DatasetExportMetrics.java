package kr.co.cudo.authoring.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * 검수 승인 후 학습데이터 파일 산출(dataset.export) 메트릭 (Phase 4).
 *
 * <p>{@code DatasetExportService} 오케스트레이터의 종결 분기를 관측한다. outcome 은 6종 고정
 * 저카디널리티 값이라 파라미터화 메서드로 태그를 부여한다(observability.md — 태그 기반 저카디널리티).
 *
 * <p>메트릭:
 * <ul>
 *   <li>{@code dataset.export.result}{@code {outcome}} — 산출 종결 건수(outcome 별 배타 1회)</li>
 *   <li>{@code dataset.export.skipped_frames} — 원천 부재 등으로 건너뛴 프레임 총량</li>
 *   <li>{@code dataset.export.duration}{@code {outcome}} — 산출 1건 소요 시간</li>
 * </ul>
 * outcome ∈ {completed, partial, failed, version_exhausted, idempotent_skip, no_input}.
 */
@Component
public class DatasetExportMetrics {

    private static final String RESULT = "dataset.export.result";
    private static final String SKIPPED_FRAMES = "dataset.export.skipped_frames";
    private static final String DURATION = "dataset.export.duration";
    private static final String TAG_OUTCOME = "outcome";

    private final MeterRegistry registry;

    public DatasetExportMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 소요시간 측정 시작. 종결 시점의 outcome 태그로 {@link #durationTimer(String)} 에 stop 한다. */
    public Timer.Sample startSample() {
        return Timer.start(registry);
    }

    /** outcome 별 소요시간 Timer(조회 시점 register — 기존 ExternalApiMetrics 관례). */
    public Timer durationTimer(String outcome) {
        return Timer.builder(DURATION)
                .tag(TAG_OUTCOME, outcome)
                .description("학습데이터 산출 1건 소요 시간(outcome 별)")
                .register(registry);
    }

    /** 산출 종결 결과 counter — 각 export() 호출당 outcome 하나로 정확히 1회 기록. */
    public void recordResult(String outcome) {
        Counter.builder(RESULT)
                .tag(TAG_OUTCOME, outcome)
                .description("학습데이터 산출 종결 건수(outcome 별)")
                .register(registry)
                .increment();
    }

    /** 산출 시 건너뛴 프레임 총량(부재 프레임 관측). 완전성공은 0 증가로 카운터만 등록. */
    public void incrementSkippedFrames(int count) {
        Counter.builder(SKIPPED_FRAMES)
                .description("산출 시 원천 이미지 부재 등으로 건너뛴 프레임 총량")
                .register(registry)
                .increment(count);
    }
}
