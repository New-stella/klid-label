package kr.co.cudo.authoring.sysconfig;

import java.util.Map;
import java.util.Set;

/**
 * 시스템 설정 키 화이트리스트 (V1.4 §5A.4).
 * <p>
 * 운영 UI(ManageConfig)에서 편집 가능한 5개 키만 등록한다.
 * 알 수 없는 키 PUT 시 INVALID_INPUT 으로 거부한다.
 */
public final class ConfigKeys {

    public static final String BATCH_INTERVAL_SEC = "BATCH_INTERVAL_SEC";
    public static final String BATCH_CONCURRENCY  = "BATCH_CONCURRENCY";

    /**
     * Phase 1 (YOLO 정확도 개선) — 운영 UI 에서 조정 가능한 YOLO 추론 파라미터.
     * <ul>
     *   <li>{@code YOLO_CONF_THRESHOLD} : 정수 25~80 (사용 시 /100.0 → 0.25~0.80)</li>
     *   <li>{@code YOLO_IMGSZ} : 추론 입력 해상도 320~1920 px</li>
     *   <li>{@code YOLO_IOU} : 정수 30~80 (사용 시 /100.0 → 0.30~0.80)</li>
     * </ul>
     */
    public static final String YOLO_CONF_THRESHOLD = "YOLO_CONF_THRESHOLD";
    public static final String YOLO_IMGSZ          = "YOLO_IMGSZ";
    public static final String YOLO_IOU            = "YOLO_IOU";

    /** 화이트리스트 — Service.update / getInt 진입 검증에 사용. */
    public static final Set<String> ALLOWED = Set.of(
            BATCH_INTERVAL_SEC, BATCH_CONCURRENCY,
            YOLO_CONF_THRESHOLD, YOLO_IMGSZ, YOLO_IOU
    );

    /** NUMBER 키별 허용 범위 [min, max] (DB설계서 §5A.4 정책). */
    public static final Map<String, int[]> NUMBER_RANGE = Map.of(
            BATCH_INTERVAL_SEC,  new int[]{10, 3600},
            BATCH_CONCURRENCY,   new int[]{1, 10},
            YOLO_CONF_THRESHOLD, new int[]{25, 80},
            YOLO_IMGSZ,          new int[]{320, 1920},
            YOLO_IOU,            new int[]{30, 80}
    );

    private ConfigKeys() {}
}
