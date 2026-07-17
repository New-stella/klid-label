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

    /**
     * FEAT-007 (SFR-08-03) 라벨링 정밀도 — 경계 세밀함.
     * <p>
     * Douglas-Peucker 단순화 epsilon(px). DECIMAL 타입(0.0~50.0, 기본 1.0).
     * 값이 클수록 폴리곤 점이 더 많이 제거되어 경계가 거칠어진다(=세밀함 낮춤).
     * 인식 민감도는 기존 {@link #YOLO_CONF_THRESHOLD} 가 담당.
     */
    public static final String POLYGON_SIMPLIFY_TOLERANCE = "POLYGON_SIMPLIFY_TOLERANCE";

    /**
     * V107 (포털 전용 업로드) — 업로드 영상 프레임 추출 간격(초).
     * NUMBER, 기본 5, 범위 1~600. 값이 클수록 추출 프레임이 줄어든다.
     */
    public static final String PORTAL_UPLOAD_FRAME_INTERVAL_SEC = "portal.upload.frame-interval-sec";

    /** 화이트리스트 — Service.update / getInt 진입 검증에 사용. */
    public static final Set<String> ALLOWED = Set.of(
            BATCH_INTERVAL_SEC, BATCH_CONCURRENCY,
            YOLO_CONF_THRESHOLD, YOLO_IMGSZ, YOLO_IOU,
            POLYGON_SIMPLIFY_TOLERANCE,
            PORTAL_UPLOAD_FRAME_INTERVAL_SEC
    );

    /** NUMBER(정수) 키별 허용 범위 [min, max] (DB설계서 §5A.4 정책). */
    public static final Map<String, int[]> NUMBER_RANGE = Map.of(
            BATCH_INTERVAL_SEC,  new int[]{10, 3600},
            BATCH_CONCURRENCY,   new int[]{1, 10},
            YOLO_CONF_THRESHOLD, new int[]{25, 80},
            YOLO_IMGSZ,          new int[]{320, 1920},
            YOLO_IOU,            new int[]{30, 80},
            PORTAL_UPLOAD_FRAME_INTERVAL_SEC, new int[]{1, 600}
    );

    /** DECIMAL(소수) 키별 허용 범위 [min, max]. */
    public static final Map<String, double[]> DECIMAL_RANGE = Map.of(
            POLYGON_SIMPLIFY_TOLERANCE, new double[]{0.0, 50.0}
    );

    private ConfigKeys() {}
}
