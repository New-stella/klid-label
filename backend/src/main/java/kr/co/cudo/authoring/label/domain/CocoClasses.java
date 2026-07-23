package kr.co.cudo.authoring.label.domain;

import java.util.List;
import java.util.Set;

/**
 * COCO 80 검출 클래스 allowlist — 라벨 마스터 검출유형(DTCT_TYPE_CD) 매핑의 진실원 계약.
 *
 * <p>ai-server {@code app/models/detector_backend.py} 의 {@code COCO_ID2LABEL}(80종, id 0~79)와
 * <b>동일 순서·동일 문자열</b>이어야 한다. 값이 불일치하면 매핑된 COCO 클래스를 ai-server 로 보내도
 * 검출되지 않는 드리프트 결함이 발생하므로, {@code CocoClassesDriftTest} 가 개수·순서를 계약 검증한다.
 *
 * <p>용도:
 * <ul>
 *   <li>라벨 마스터 create/update 시 {@code dtctTypeCd} 입력을 이 allowlist 로 검증(자유텍스트 금지, CWE-20).</li>
 *   <li>AI 탐지 온라인/배치 경로가 매핑된 라벨의 COCO 클래스만 검출 대상으로 재구성(HIGH#1).</li>
 * </ul>
 */
public final class CocoClasses {

    private CocoClasses() {
    }

    /** COCO 80 클래스명 — ai-server COCO_ID2LABEL id 0~79 순서 그대로. */
    public static final List<String> LABELS = List.of(
            "person", "bicycle", "car", "motorcycle", "airplane",
            "bus", "train", "truck", "boat", "traffic light",
            "fire hydrant", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow",
            "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite",
            "baseball bat", "baseball glove", "skateboard", "surfboard",
            "tennis racket", "bottle", "wine glass", "cup", "fork",
            "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog",
            "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv",
            "laptop", "mouse", "remote", "keyboard", "cell phone",
            "microwave", "oven", "toaster", "sink", "refrigerator",
            "book", "clock", "vase", "scissors", "teddy bear",
            "hair drier", "toothbrush"
    );

    /** 빠른 검증용 집합(대소문자 구분 — COCO 정규 소문자). */
    private static final Set<String> LABEL_SET = Set.copyOf(LABELS);

    /** 매핑 허용 여부 — null/blank/미지원 값은 false. 입력은 trim 후 정규 문자열과 정확 비교. */
    public static boolean isValid(String cocoLabel) {
        if (cocoLabel == null) {
            return false;
        }
        return LABEL_SET.contains(cocoLabel.trim());
    }
}
