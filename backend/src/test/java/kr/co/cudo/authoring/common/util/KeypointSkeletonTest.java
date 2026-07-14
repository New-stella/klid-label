package kr.co.cudo.authoring.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 (키포인트 라벨마스터) — COCO-17 스켈레톤 상수({@link KeypointSkeleton}) 검증.
 *
 * <p>이 상수는 관제 데이터마트가 COCO category.keypoints / category.skeleton 을 조립할 때
 * 참조하는 원천(export category 메타)이다. 개수·순서·엣지 값이 표준과 정확히 일치해야 하며,
 * 외부 노출 컬렉션은 불변(immutable)이어야 한다.
 */
class KeypointSkeletonTest {

    @Test
    @DisplayName("KeypointSkeleton_이름_17개_엣지_19쌍_정확")
    void names17_edges19_exact() {
        // then — 관절명 17개, 표준 순서(1~17: nose ... right_ankle)
        assertThat(KeypointSkeleton.KEYPOINT_NAMES).containsExactly(
                "nose", "left_eye", "right_eye", "left_ear", "right_ear",
                "left_shoulder", "right_shoulder", "left_elbow", "right_elbow",
                "left_wrist", "right_wrist", "left_hip", "right_hip",
                "left_knee", "right_knee", "left_ankle", "right_ankle");

        // then — 스켈레톤 엣지 19쌍(1-indexed), COCO 표준 토폴로지
        assertThat(KeypointSkeleton.SKELETON_EDGES).containsExactly(
                List.of(16, 14), List.of(14, 12), List.of(17, 15), List.of(15, 13),
                List.of(12, 13), List.of(6, 12), List.of(7, 13), List.of(6, 7),
                List.of(6, 8), List.of(7, 9), List.of(8, 10), List.of(9, 11),
                List.of(2, 3), List.of(1, 2), List.of(1, 3), List.of(2, 4),
                List.of(3, 5), List.of(4, 6), List.of(5, 7));

        assertThat(KeypointSkeleton.KEYPOINT_NAMES).hasSize(17);
        assertThat(KeypointSkeleton.SKELETON_EDGES).hasSize(19);
    }

    @Test
    @DisplayName("KeypointSkeleton_엣지_인덱스는_1부터_17_범위")
    void edges_indexInBounds() {
        // then — 모든 엣지의 양 끝 인덱스가 1~17 범위(관절 개수)를 벗어나지 않음
        for (List<Integer> edge : KeypointSkeleton.SKELETON_EDGES) {
            assertThat(edge).hasSize(2);
            assertThat(edge.get(0)).isBetween(1, 17);
            assertThat(edge.get(1)).isBetween(1, 17);
        }
    }

    @Test
    @DisplayName("KeypointSkeleton_이름_컬렉션은_불변")
    void names_immutable() {
        assertThatThrownBy(() -> KeypointSkeleton.KEYPOINT_NAMES.add("extra"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("KeypointSkeleton_엣지_컬렉션은_불변")
    void edges_immutable() {
        assertThatThrownBy(() -> KeypointSkeleton.SKELETON_EDGES.add(List.of(1, 2)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> KeypointSkeleton.SKELETON_EDGES.get(0).add(99))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
