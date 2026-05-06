package kr.co.cudo.authoring.sysconfig;

import java.util.Map;
import java.util.Set;

/**
 * 시스템 설정 키 화이트리스트 (V1.4 §5A.4).
 * <p>
 * 운영 UI(ManageConfig)에서 편집 가능한 4개 키만 등록한다.
 * 알 수 없는 키 PUT 시 INVALID_INPUT 으로 거부한다.
 */
public final class ConfigKeys {

    public static final String FFMPEG_THREADS     = "FFMPEG_THREADS";
    public static final String FFMPEG_OUTPUT_FPS  = "FFMPEG_OUTPUT_FPS";
    public static final String BATCH_INTERVAL_SEC = "BATCH_INTERVAL_SEC";
    public static final String BATCH_CONCURRENCY  = "BATCH_CONCURRENCY";

    /** 화이트리스트 — Service.update / getInt 진입 검증에 사용. */
    public static final Set<String> ALLOWED = Set.of(
            FFMPEG_THREADS, FFMPEG_OUTPUT_FPS, BATCH_INTERVAL_SEC, BATCH_CONCURRENCY
    );

    /** NUMBER 키별 허용 범위 [min, max] (DB설계서 §5A.4 정책). */
    public static final Map<String, int[]> NUMBER_RANGE = Map.of(
            FFMPEG_THREADS,     new int[]{1, 16},
            FFMPEG_OUTPUT_FPS,  new int[]{1, 30},
            BATCH_INTERVAL_SEC, new int[]{10, 3600},
            BATCH_CONCURRENCY,  new int[]{1, 10}
    );

    private ConfigKeys() {}
}
