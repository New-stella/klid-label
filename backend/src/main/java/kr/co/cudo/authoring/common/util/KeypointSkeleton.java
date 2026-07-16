package kr.co.cudo.authoring.common.util;

import java.util.List;

/**
 * COCO-17 휴먼 포즈 스켈레톤 표준 상수 (키포인트 라벨링 export category 메타 원천).
 *
 * <p>SKELETON 타입 라벨의 geometry 는 {@code LS_DATA_LBL.POINT_CN} 에 17×{@code [x,y,v]}
 * 삼중값으로 저장된다(v=0 미표기 / 1 비가시 / 2 가시). 본 클래스는 그 삼중값의 <b>의미</b>를
 * 규정하는 category 메타 — 즉 각 인덱스가 어떤 관절명인지({@link #KEYPOINT_NAMES})와
 * 스켈레톤 엣지 토폴로지({@link #SKELETON_EDGES}) — 를 코드 상수로 노출한다.
 *
 * <p>관제 데이터마트가 COCO {@code category.keypoints} / {@code category.skeleton} 를 조립할 때
 * 이 상수를 참조한다. COCO-pose JSON <b>파일 조립 자체는 외부 위임</b>이며 저작도구 범위 밖이다.
 *
 * <p>모든 컬렉션은 불변(immutable)이다 — 수정 시도 시 {@link UnsupportedOperationException}.
 * BE/FE 가 공유하는 토폴로지이므로 FE {@code COCO_SKELETON} 상수와 값이 일치해야 한다.
 *
 * <p>인덱스 규약: {@link #KEYPOINT_NAMES} 는 0-based 리스트지만, COCO 표준 스켈레톤 엣지는
 * <b>1-based</b> 관절 번호로 표기한다({@link #SKELETON_EDGES}). 두 표기가 섞이지 않도록 주의.
 */
public final class KeypointSkeleton {

    private KeypointSkeleton() {
    }

    /**
     * COCO-17 관절명 (인덱스 순서 = COCO 표준 keypoint 순서, 1~17번 관절).
     * List.of 는 불변 리스트를 반환한다.
     */
    public static final List<String> KEYPOINT_NAMES = List.of(
            "nose",           // 1
            "left_eye",       // 2
            "right_eye",      // 3
            "left_ear",       // 4
            "right_ear",      // 5
            "left_shoulder",  // 6
            "right_shoulder", // 7
            "left_elbow",     // 8
            "right_elbow",    // 9
            "left_wrist",     // 10
            "right_wrist",    // 11
            "left_hip",       // 12
            "right_hip",      // 13
            "left_knee",      // 14
            "right_knee",     // 15
            "left_ankle",     // 16
            "right_ankle"     // 17
    );

    /**
     * COCO-17 스켈레톤 엣지 19쌍 (1-indexed 관절 번호쌍). 각 원소·리스트 모두 불변.
     * 하지→상체→얼굴 순으로 나열된 COCO 표준 토폴로지.
     */
    public static final List<List<Integer>> SKELETON_EDGES = List.of(
            List.of(16, 14), List.of(14, 12), List.of(17, 15), List.of(15, 13),
            List.of(12, 13), List.of(6, 12), List.of(7, 13), List.of(6, 7),
            List.of(6, 8), List.of(7, 9), List.of(8, 10), List.of(9, 11),
            List.of(2, 3), List.of(1, 2), List.of(1, 3), List.of(2, 4),
            List.of(3, 5), List.of(4, 6), List.of(5, 7)
    );
}
