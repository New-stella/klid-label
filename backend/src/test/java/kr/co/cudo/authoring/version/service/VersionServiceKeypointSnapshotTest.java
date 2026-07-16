package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 1 (키포인트) — SKELETON 라벨의 검수 승인/신고 스냅샷이 삼중값(v)을 유실 없이 보존하는지 검증.
 *
 * <p>스냅샷 페이로드는 LabelResponse 를 직렬화하므로, LabelResponse.Item.from 의 SKELETON type-route 가
 * 삼중값을 만들어 payload 에 [[x,y,v],...] 로 그대로 담겨야 한다.
 */
class VersionServiceKeypointSnapshotTest {

    private LsLabelVersionRepository labelVersionRepository;
    private LsDataLblRepository labelRepository;
    private ObjectMapper objectMapper;
    private VersionService service;
    private TokenClaims actor;

    @BeforeEach
    void setUp() {
        labelVersionRepository = mock(LsLabelVersionRepository.class);
        LabelAccessGuard accessGuard = mock(LabelAccessGuard.class);
        VideoRepository videoRepository = mock(VideoRepository.class);
        WorkLockService workLockService = mock(WorkLockService.class);
        LsDataSrcRepository srcRepository = mock(LsDataSrcRepository.class);
        labelRepository = mock(LsDataLblRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        LsRawDataStatusRepository rawDataStatusRepository = mock(LsRawDataStatusRepository.class);
        objectMapper = new ObjectMapper();
        service = new VersionService(labelVersionRepository, accessGuard, videoRepository,
                workLockService, srcRepository, labelRepository, objectMapper,
                eventPublisher, rawDataStatusRepository);
        actor = new TokenClaims("100", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

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
        LsDataLbl l = LsDataLbl.createManual(srcSn, LsDataLbl.TYPE_SKELETON, null, "person", sb.toString(), 100L);
        setField(l, "lblSn", lblSn);
        return l;
    }

    @Test
    @DisplayName("VersionService_스냅샷_후_SKELETON_v_보존")
    void snapshotPreservesVisibility() throws Exception {
        when(labelRepository.findAllByRawSn(9000L)).thenReturn(List.of(skeletonLabel(1L, 10L)));
        when(labelVersionRepository.findFirstByDataRawSnOrderByVersionNoDesc(9000L))
                .thenReturn(Optional.empty());

        boolean result = service.snapshotDeidentReport(9000L, actor);

        assertThat(result).isTrue();
        ArgumentCaptor<LsLabelVersion> cap = ArgumentCaptor.forClass(LsLabelVersion.class);
        verify(labelVersionRepository).save(cap.capture());
        String payload = cap.getValue().getLabelPayload();

        // payload 의 items[0].points 가 17개 삼중값이며 v 가 보존됐는지 파싱 검증.
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
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
