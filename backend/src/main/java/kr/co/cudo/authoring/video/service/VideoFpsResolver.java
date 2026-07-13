package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 영상 프레임레이트(fps) 해석기 — NIA export Phase 3 (M-3 결함 수정).
 *
 * <p>자동 마킹({@code MarkingService})과 프레임 추출({@code FfmpegFrameExtractor})이 공유하는
 * <b>단일 fps 소스</b>다. Phase 2 에서 적재된 {@code LS_DATA_META} 의 {@code video.fps} 값을 조회해
 * 실제 프레임레이트를 사용하고, 미상/파싱불가/비양수면 {@link #DEFAULT_FPS}(30.0)로 폴백한다.
 *
 * <p><b>정합성(Critical):</b> 두 사용처가 모두 이 컴포넌트의 {@code resolveFps(rawSn)} 를 통해
 * 동일 rawSn 의 동일 저장값을 읽으므로, 마킹이 frameIndex 를 산출할 때의 fps 와 추출이 seekMillis 를
 * 계산할 때의 fps 가 항상 일치한다. 폴백(30.0)도 단일 상수라 두 경로가 동일하게 폴백한다.
 *
 * <p><b>무회귀(fail-safe):</b> {@code video.fps} 가 아직 적재되지 않았거나(Phase 2 async 미완/실패)
 * 값이 비정상이어도 예외를 던지지 않고 30.0 으로 폴백한다 — 30fps 를 고정 가정하던 기존 동작과
 * 정확히 동일해진다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoFpsResolver {

    /**
     * 폴백 프레임레이트 — {@code video.fps} 미상/파싱불가/≤0 시 사용하는 단일 상수.
     * M-3 이전의 30fps 고정 가정과 동일 값이라 미상 상황에서 회귀가 0이다.
     */
    public static final double DEFAULT_FPS = 30.0;

    /** {@code LS_DATA_META.META_KEY} — Phase 2 VideoMetaService 가 적재하는 fps 키. */
    static final String KEY_FPS = "video.fps";

    private final LsDataMetaRepository metaRepository;

    /**
     * rawSn 영상의 실제 fps 를 해석한다.
     *
     * @param rawSn 영상 PK (LS_DATA_RAW.RAW_SN). null 이면 폴백.
     * @return 저장된 {@code video.fps} 가 파싱 가능하고 &gt;0 이면 그 값, 그 외 전부 {@link #DEFAULT_FPS}.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public double resolveFps(Long rawSn) {
        if (rawSn == null) {
            return DEFAULT_FPS;
        }
        return metaRepository.findByRawSnAndMetaKey(rawSn, KEY_FPS)
                .map(LsDataMeta::getMetaVl)
                .map(raw -> parsePositive(rawSn, raw))
                .orElse(DEFAULT_FPS);
    }

    /**
     * 저장 문자열을 양수 double 로 파싱한다. blank/파싱불가/≤0/비유한수는 전부 {@link #DEFAULT_FPS}.
     * (fail-safe — 외부 ffprobe 유래 값이라 신뢰불가 문자열이 들어올 수 있어 예외를 던지지 않는다.)
     */
    private double parsePositive(Long rawSn, String stored) {
        if (stored == null || stored.isBlank()) {
            return DEFAULT_FPS;
        }
        try {
            double fps = Double.parseDouble(stored.trim());
            if (fps > 0 && Double.isFinite(fps)) {
                return fps;
            }
            log.warn("[VideoFps] non-positive fps rawSn={} value={} — fallback {}", rawSn, stored, DEFAULT_FPS);
            return DEFAULT_FPS;
        } catch (NumberFormatException e) {
            log.warn("[VideoFps] unparsable fps rawSn={} — fallback {}", rawSn, DEFAULT_FPS);
            return DEFAULT_FPS;
        }
    }
}
