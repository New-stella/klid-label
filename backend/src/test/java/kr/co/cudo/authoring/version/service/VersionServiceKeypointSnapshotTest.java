package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.label.dto.LabelResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 (키포인트) — SKELETON 라벨의 <b>검수 승인 스냅샷 페이로드</b>가 삼중값(v)을 유실 없이 보존하는지 검증.
 *
 * <p>스냅샷 페이로드는 {@code VersionService.snapshotFrameOnApprove} 가 만드는 {@link LabelResponse}
 * 직렬화 결과다. 따라서 검증 대상은 {@code LabelResponse.Item.from} 의 SKELETON type-route 가 삼중값을
 * {@code [[x,y,v],...]} 로 그대로 담는지이며, 본 테스트는 승인 경로와 <b>동일한 LabelResponse.of 오버로드</b>
 * (frame + siblings + labels + frameImageType + lockSttsCd + aiInfoMap + objectMapper)를 직접 호출한다.
 *
 * <p>D-25(2026-07-27 정책 반전) 이전에는 이 검증을 {@code snapshotDeidentReport}(비식별 신고 스냅샷)로
 * 수행했으나, 해당 메서드가 제거되면서 살아 있는 유일한 스냅샷 직렬화 경로(승인)로 재배치했다.
 */
class VersionServiceKeypointSnapshotTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LsDataLbl skeletonLabel(long lblSn, long srcSn) {
        // 17개 삼중값 — v 는 0/1/2 순환하여 유실 여부를 명확히 구분.
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 17; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(i * 2.0).append(',').append(i * 3.0).append(',').append(i % 3).append(']');
        }
        sb.append(']');
        LsDataLbl l = LsDataLbl.createManual(srcSn, LsDataLbl.TYPE_SKELETON, null, "person", sb.toString(), "100");
        setField(l, "lblSn", lblSn);
        return l;
    }

    @Test
    @DisplayName("승인_스냅샷_페이로드에_SKELETON_v_보존")
    void snapshotPayloadPreservesVisibility() throws Exception {
        // given — 프레임 1건 + SKELETON 라벨 1건 (승인 스냅샷과 동일 입력 구성).
        LsDataSrc frame = LsDataSrc.create(9000L, 0, "/raw/0.jpg", LocalDateTime.now());
        setField(frame, "srcSn", 10L);
        List<LsDataLbl> labels = List.of(skeletonLabel(1L, 10L));

        // when — 승인 스냅샷이 사용하는 LabelResponse 직렬화 경로.
        LabelResponse snapshot = LabelResponse.of(frame, List.of(frame), labels, "DEID", null,
                Map.of(), objectMapper);
        String payload = objectMapper.writeValueAsString(snapshot);

        // then — payload 의 items[0].points 가 17개 삼중값이며 v 가 보존됐는지 파싱 검증.
        JsonNode points = objectMapper.readTree(payload).path("items").get(0).path("points");
        assertThat(points.isArray()).isTrue();
        assertThat(points.size()).isEqualTo(17);
        for (int i = 0; i < 17; i++) {
            JsonNode triplet = points.get(i);
            assertThat(triplet.size()).isEqualTo(3);
            assertThat(triplet.get(0).asDouble()).isEqualTo(i * 2.0);
            assertThat(triplet.get(1).asDouble()).isEqualTo(i * 3.0);
            assertThat(triplet.get(2).asInt()).isEqualTo(i % 3);
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
