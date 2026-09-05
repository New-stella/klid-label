package kr.co.cudo.authoring.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.PortalFrameLabelsResponse;
import kr.co.cudo.authoring.portal.entity.LsPortalUserLabel;
import kr.co.cudo.authoring.portal.repository.LsPortalUserLabelRepository;
import kr.co.cudo.authoring.portal.service.PortalLabelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * R16 — 포털 프레임 라벨 Load (PortalLabelService.loadFrameLabels).
 * datamart 원본 + 본인 user-label 병합 (user-label 우선).
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class PortalFrameLabelsServiceTest {

    @Mock LsDataLblRepository lblRepository;
    @Mock LsDataSrcRepository srcRepository;
    @Mock LsPortalUserLabelRepository userLabelRepository;
    @Mock LsRawDataStatusRepository rawDataStatusRepository;

    private PortalLabelService service;

    private final TokenClaims alice = new TokenClaims("alice", Role.PORTAL_USER, Channel.PORTAL,
            Instant.now().plusSeconds(3600));

    @BeforeEach
    void setUp() {
        // DEV_FIX-A(S7) — 비식별 신고 게이트(LabelAccessGuard)는 배선 검증용 IT
        //   (DeidentReportGateCoverageIT) 에서 실 경로로 확인한다. 여기서는 no-op mock 으로 기존 검증에 집중.
        var deidentGate = org.mockito.Mockito.mock(
                kr.co.cudo.authoring.label.service.LabelAccessGuard.class);
        service = new PortalLabelService(lblRepository, srcRepository, userLabelRepository,
                rawDataStatusRepository, null, deidentGate,
                new kr.co.cudo.authoring.portal.service.PortalRetentionPolicy(
                        org.mockito.Mockito.mock(kr.co.cudo.authoring.sysconfig.service.SystemConfigService.class)),
                new ObjectMapper(), null);
    }

    private LsDataSrc src(Long srcSn, Long rawSn, int frameNo) {
        LsDataSrc s = LsDataSrc.create(rawSn, frameNo, "f.jpg", LocalDateTime.now());
        setField(s, "srcSn", srcSn);
        setField(s, "rawSn", rawSn);
        return s;
    }

    private static void setField(Object t, String name, Object v) {
        try {
            Field f = findField(t.getClass(), name);
            f.setAccessible(true);
            f.set(t, v);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        while (c != null) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignore) { c = c.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    /** R17 이슈5 — 포털 라벨 Load 는 검수완료(APPROVED) 영상만 허용 → 테스트 픽스처에 승인 상태 mock. */
    private void approve(Long rawSn) {
        kr.co.cudo.authoring.assignment.entity.LsRawDataStatus st =
                kr.co.cudo.authoring.assignment.entity.LsRawDataStatus.initial(rawSn);
        setField(st, "dataSttsCd",
                kr.co.cudo.authoring.assignment.entity.LsRawDataStatus.STTS_APPROVED);
        when(rawDataStatusRepository.findById(rawSn)).thenReturn(java.util.Optional.of(st));
    }

    @Test
    @DisplayName("포털_프레임_라벨_user_label_없으면_datamart_원본_반환")
    void loadFrameLabels_noUserLabel_returnsDatamart() {
        LsDataSrc s = src(10L, 100L, 0);
        when(srcRepository.findById(10L)).thenReturn(Optional.of(s));
        approve(100L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(100L)).thenReturn(List.of(s));
        LsDataLbl lbl = LsDataLbl.createAutoBbox(10L, null, "person", "[[1,2],[3,4]]",
                BigDecimal.valueOf(0.9), null);
        when(lblRepository.findBySrcSn(10L)).thenReturn(List.of(lbl));
        when(userLabelRepository.findByPortalUserNoAndSrcDataSrcSnOrderByRegDtDesc("alice", 10L)).thenReturn(List.of());

        PortalFrameLabelsResponse resp = service.loadFrameLabels(10L, alice);

        assertThat(resp.srcSn()).isEqualTo(10L);
        assertThat(resp.videoId()).isEqualTo(100L);
        assertThat(resp.labels()).hasSize(1);
        assertThat(resp.labels().get(0).label()).isEqualTo("person");
        assertThat(resp.labels().get(0).points()).containsExactly(List.of(1.0, 2.0), List.of(3.0, 4.0));
    }

    @Test
    @DisplayName("포털_프레임_라벨_user_label_있으면_우선")
    void loadFrameLabels_userLabel_takesPrecedence() {
        LsDataSrc s = src(10L, 100L, 0);
        when(srcRepository.findById(10L)).thenReturn(Optional.of(s));
        approve(100L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(100L)).thenReturn(List.of(s));
        LsDataLbl original = LsDataLbl.createAutoBbox(10L, null, "person", "[[1,2],[3,4]]",
                BigDecimal.valueOf(0.9), null);
        when(lblRepository.findBySrcSn(10L)).thenReturn(List.of(original));
        LsPortalUserLabel mine = LsPortalUserLabel.create("alice", 100L, 10L, "BBOX", "car", "[[5,6],[7,8]]");
        when(userLabelRepository.findByPortalUserNoAndSrcDataSrcSnOrderByRegDtDesc("alice", 10L)).thenReturn(List.of(mine));

        PortalFrameLabelsResponse resp = service.loadFrameLabels(10L, alice);

        assertThat(resp.labels()).hasSize(1);
        assertThat(resp.labels().get(0).label()).isEqualTo("car");
        assertThat(resp.labels().get(0).points()).containsExactly(List.of(5.0, 6.0), List.of(7.0, 8.0));
    }

    @Test
    @DisplayName("포털_프레임_라벨_프레임_없으면_404")
    void loadFrameLabels_frameMissing_notFound() {
        when(srcRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadFrameLabels(999L, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("R17_포털_프레임_라벨_빈좌표_user_label은_제외하고_datamart_원본_폴백")
    void loadFrameLabels_emptyPointUserLabel_excluded() {
        // given: 검증 우회로 생성된 stale user-label (point_cn NULL → 빈 좌표) 1건만 존재
        LsDataSrc s = src(10L, 100L, 0);
        when(srcRepository.findById(10L)).thenReturn(Optional.of(s));
        approve(100L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(100L)).thenReturn(List.of(s));
        LsDataLbl datamart = LsDataLbl.createAutoBbox(10L, null, "person", "[[1,2],[3,4]]",
                BigDecimal.valueOf(0.9), null);
        when(lblRepository.findBySrcSn(10L)).thenReturn(List.of(datamart));
        LsPortalUserLabel staleEmpty = LsPortalUserLabel.create("alice", 100L, 10L, "BBOX", "car", null);
        when(userLabelRepository.findByPortalUserNoAndSrcDataSrcSnOrderByRegDtDesc("alice", 10L))
                .thenReturn(List.of(staleEmpty));

        // when
        PortalFrameLabelsResponse resp = service.loadFrameLabels(10L, alice);

        // then: 빈좌표 user-label 은 제외 → datamart 원본으로 폴백 (빈 라벨 1건 반환 회귀 차단)
        assertThat(resp.labels()).hasSize(1);
        assertThat(resp.labels().get(0).label()).isEqualTo("person");
        assertThat(resp.labels().get(0).points()).containsExactly(List.of(1.0, 2.0), List.of(3.0, 4.0));
    }

    // ─── 왕복 보존 (API-110) — 화면은 표시하지 않고 저장 요청에 그대로 되돌려 보낸다 ───

    @Test
    @DisplayName("데이터마트_원본의_마스터연결과_트랙연결이_응답에_실린다")
    void loadFrameLabels_datamartOrigin_carriesMasterAndTrackLink() {
        // ★ 이 응답이 두 값을 버리면 화면이 되돌려 보낼 값 자체가 없어져, 사용자가 그 프레임을
        //   수정해 저장하는 순간 원본이 갖고 있던 연결이 끊긴다.
        LsDataSrc s = src(10L, 100L, 0);
        when(srcRepository.findById(10L)).thenReturn(Optional.of(s));
        approve(100L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(100L)).thenReturn(List.of(s));
        LsDataLbl lbl = LsDataLbl.createAutoBbox(10L, 12L, "person", "[[1,2],[3,4]]",
                BigDecimal.valueOf(0.9), "trk-3");
        when(lblRepository.findBySrcSn(10L)).thenReturn(List.of(lbl));
        when(userLabelRepository.findByPortalUserNoAndSrcDataSrcSnOrderByRegDtDesc("alice", 10L))
                .thenReturn(List.of());

        PortalFrameLabelsResponse resp = service.loadFrameLabels(10L, alice);

        assertThat(resp.labels().get(0).labelId()).isEqualTo(12L);
        assertThat(resp.labels().get(0).trackId()).isEqualTo("trk-3");
    }

    @Test
    @DisplayName("본인_저장분의_마스터연결과_트랙연결도_같은_형태로_실린다")
    void loadFrameLabels_ownLabel_carriesSameShape() {
        // 출처(본인 저장분/데이터마트 원본)에 따라 응답 형태가 갈리지 않아야 화면이 두 갈래로 분기하지 않는다.
        LsDataSrc s = src(10L, 100L, 0);
        when(srcRepository.findById(10L)).thenReturn(Optional.of(s));
        approve(100L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(100L)).thenReturn(List.of(s));
        LsPortalUserLabel mine = LsPortalUserLabel.create(
                "alice", 100L, 10L, "BBOX", "car", "[[5,6],[7,8]]", 12L, "trk-3");
        when(userLabelRepository.findByPortalUserNoAndSrcDataSrcSnOrderByRegDtDesc("alice", 10L))
                .thenReturn(List.of(mine));

        PortalFrameLabelsResponse resp = service.loadFrameLabels(10L, alice);

        assertThat(resp.labels().get(0).labelId()).isEqualTo(12L);
        assertThat(resp.labels().get(0).trackId()).isEqualTo("trk-3");
    }

    @Test
    @DisplayName("컬럼_신설_이전_저장분은_두_값이_비어_있고_오류가_아니다")
    void loadFrameLabels_legacyRow_hasEmptyLinks() {
        // 기존 행은 백필하지 않는다 — null 로 남고 재저장하면 자연 복구된다.
        LsDataSrc s = src(10L, 100L, 0);
        when(srcRepository.findById(10L)).thenReturn(Optional.of(s));
        approve(100L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(100L)).thenReturn(List.of(s));
        LsPortalUserLabel legacy =
                LsPortalUserLabel.create("alice", 100L, 10L, "BBOX", "car", "[[5,6],[7,8]]");
        when(userLabelRepository.findByPortalUserNoAndSrcDataSrcSnOrderByRegDtDesc("alice", 10L))
                .thenReturn(List.of(legacy));

        PortalFrameLabelsResponse resp = service.loadFrameLabels(10L, alice);

        assertThat(resp.labels().get(0).labelId()).isNull();
        assertThat(resp.labels().get(0).trackId()).isNull();
    }

    @Test
    @DisplayName("R17_포털_프레임_라벨_미승인_영상_403_graceful차단")
    void loadFrameLabels_notApproved_forbidden() {
        // given: 검수 미완료(미승인) 영상 프레임
        LsDataSrc s = src(10L, 100L, 0);
        when(srcRepository.findById(10L)).thenReturn(Optional.of(s));
        when(rawDataStatusRepository.findById(100L)).thenReturn(Optional.empty());

        // when/then: 라벨 Load 도 403 (이미지 서빙과 정합) — FE 가 graceful 차단 화면 처리
        assertThatThrownBy(() -> service.loadFrameLabels(10L, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("FORBIDDEN");
    }
}
