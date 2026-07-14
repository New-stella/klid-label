package kr.co.cudo.authoring.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.PortalFrameLabelsResponse;
import kr.co.cudo.authoring.portal.repository.LsPortalUserLabelRepository;
import kr.co.cudo.authoring.portal.service.PortalLabelService;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 1 (키포인트) — 포털 라벨 Load 가 SKELETON(삼중값) 라벨이 섞여 있어도 500 없이 처리하고,
 * SKELETON 은 skip 하는지 검증. 포털은 키포인트 미제공(ADR-013).
 */
class PortalLabelServiceKeypointTest {

    private static final Long SRC_SN = 300L;
    private static final Long RAW_SN = 400L;
    private static final String USER = "portal-1";

    private LsDataLblRepository lblRepository;
    private LsDataSrcRepository srcRepository;
    private LsPortalUserLabelRepository userLabelRepository;
    private LsRawDataStatusRepository rawDataStatusRepository;
    private PortalLabelService service;

    @BeforeEach
    void setUp() {
        lblRepository = mock(LsDataLblRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        userLabelRepository = mock(LsPortalUserLabelRepository.class);
        rawDataStatusRepository = mock(LsRawDataStatusRepository.class);
        VideoRepository videoRepository = mock(VideoRepository.class);
        service = new PortalLabelService(lblRepository, srcRepository, userLabelRepository,
                rawDataStatusRepository, videoRepository, new ObjectMapper());

        LsDataSrc frame = LsDataSrc.create(RAW_SN, 0, "/f.jpg", null);
        when(srcRepository.findById(SRC_SN)).thenReturn(Optional.of(frame));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame));

        LsRawDataStatus approved = mock(LsRawDataStatus.class);
        when(approved.getDataSttsCd()).thenReturn(LsRawDataStatus.STTS_APPROVED);
        when(rawDataStatusRepository.findById(RAW_SN)).thenReturn(Optional.of(approved));

        // 본인 user-label 없음 → 데이터마트(control) 원본 라벨 경로.
        when(userLabelRepository.findByPortalUserNoAndSrcDataSrcSnOrderByRegDtDesc(USER, SRC_SN))
                .thenReturn(List.of());
    }

    private TokenClaims portalUser() {
        return new TokenClaims(USER, Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(600));
    }

    private LsDataLbl skeleton() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 17; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(i).append(',').append(i).append(",2]");
        }
        sb.append(']');
        return LsDataLbl.createManual(SRC_SN, LsDataLbl.TYPE_SKELETON, null, "person", sb.toString(), 1L);
    }

    private LsDataLbl bbox() {
        return LsDataLbl.createManual(SRC_SN, LsDataLbl.TYPE_BBOX, null, "car", "[[0,0],[10,10]]", 1L);
    }

    @Test
    @DisplayName("PortalLabel_SKELETON_섞여도_예외없이_처리")
    void skeletonMixedNoException() {
        when(lblRepository.findBySrcSn(SRC_SN)).thenReturn(List.of(skeleton(), bbox()));

        PortalFrameLabelsResponse[] holder = new PortalFrameLabelsResponse[1];
        assertThatCode(() -> holder[0] = service.loadFrameLabels(SRC_SN, portalUser()))
                .doesNotThrowAnyException();

        // SKELETON 은 skip 되어 BBOX 라벨만 응답에 포함.
        List<PortalFrameLabelsResponse.Item> items = holder[0].labels();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).lblTypeCd()).isEqualTo(LsDataLbl.TYPE_BBOX);
    }
}
