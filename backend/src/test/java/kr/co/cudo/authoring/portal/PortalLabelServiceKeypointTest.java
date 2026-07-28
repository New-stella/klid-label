package kr.co.cudo.authoring.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.PortalFrameLabelsResponse;
import kr.co.cudo.authoring.portal.dto.PortalUserLabelRequest;
import kr.co.cudo.authoring.portal.entity.LsPortalUserLabel;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 9 (키포인트) — 포털 라벨 Load 가 SKELETON(삼중값, 17×[x,y,v]) 라벨을 type-route 로
 * 정상 반환(round-trip)하는지 검증. 구 ADR-013 'SKELETON skip' 정책 폐지 — 포털 키포인트 제공.
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
        // DEV_FIX-A(S7) — 비식별 신고 게이트(LabelAccessGuard)는 배선 검증용 IT
        //   (DeidentReportGateCoverageIT) 에서 실 경로로 확인한다. 여기서는 no-op mock 으로 기존 검증에 집중.
        var deidentGate = org.mockito.Mockito.mock(
                kr.co.cudo.authoring.label.service.LabelAccessGuard.class);
        service = new PortalLabelService(lblRepository, srcRepository, userLabelRepository,
                rawDataStatusRepository, videoRepository, deidentGate, new ObjectMapper());

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
    @DisplayName("PortalLabel_datamart_SKELETON과_BBOX_모두_반환")
    void skeletonAndBboxBothReturned() {
        when(lblRepository.findBySrcSn(SRC_SN)).thenReturn(List.of(skeleton(), bbox()));

        PortalFrameLabelsResponse[] holder = new PortalFrameLabelsResponse[1];
        assertThatCode(() -> holder[0] = service.loadFrameLabels(SRC_SN, portalUser()))
                .doesNotThrowAnyException();

        // Phase 9 — SKELETON skip 폐지: SKELETON + BBOX 모두 반환.
        List<PortalFrameLabelsResponse.Item> items = holder[0].labels();
        assertThat(items).hasSize(2);
        PortalFrameLabelsResponse.Item skeletonItem = items.stream()
                .filter(i -> LsDataLbl.TYPE_SKELETON.equals(i.lblTypeCd())).findFirst().orElseThrow();
        // 17×[x,y,v] 삼중값 무손실 반환.
        assertThat(skeletonItem.points()).hasSize(17);
        assertThat(skeletonItem.points().get(0)).containsExactly(0.0, 0.0, 2.0);
    }

    @Test
    @DisplayName("포털_키포인트_저장후_loadFrameLabels로_다시_조회되어_사라지지않는다")
    void keypointSavedThenLoadedRoundTrip() {
        // 본인 user-label 로 SKELETON 저장분이 존재 → user-label 우선 경로.
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 17; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(i).append(',').append(i).append(",2]");
        }
        sb.append(']');
        LsPortalUserLabel mine = LsPortalUserLabel.create(
                USER, RAW_SN, SRC_SN, LsDataLbl.TYPE_SKELETON, "person", sb.toString());
        when(userLabelRepository.findByPortalUserNoAndSrcDataSrcSnOrderByRegDtDesc(USER, SRC_SN))
                .thenReturn(List.of(mine));

        PortalFrameLabelsResponse res = service.loadFrameLabels(SRC_SN, portalUser());

        // 저장한 SKELETON 이 skip 되지 않고 17점으로 그대로 반환된다(round-trip).
        List<PortalFrameLabelsResponse.Item> items = res.labels();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).lblTypeCd()).isEqualTo(LsDataLbl.TYPE_SKELETON);
        assertThat(items.get(0).points()).hasSize(17);
        assertThat(items.get(0).points().get(16)).containsExactly(16.0, 16.0, 2.0);
    }

    @Test
    @DisplayName("포털_비APPROVED_영상_사용자라벨_저장_거부_403")
    void saveUserLabelNotApprovedForbidden() {
        // 이슈4 — 로드/이미지 서빙과 동일 인가(APPROVED 게이트). 비APPROVED sourceRawSn 저장은 403.
        Long pendingRaw = 999L;
        when(rawDataStatusRepository.findById(pendingRaw)).thenReturn(Optional.empty());
        PortalUserLabelRequest req = new PortalUserLabelRequest(
                pendingRaw, SRC_SN, LsDataLbl.TYPE_BBOX, "car", "[[0,0],[10,10]]");

        assertThatThrownBy(() -> service.saveUserLabel(req, portalUser()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
