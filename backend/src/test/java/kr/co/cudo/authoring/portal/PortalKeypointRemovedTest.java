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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 포털(외부 채널) 키포인트(SKELETON) <b>서버 기능 완전 제거</b> 회귀 테스트.
 *
 * <p><b>정책 근거</b>: CLAUDE.md 포털 절 — 포털 라벨링은 <b>BBOX/POLYGON 만</b>이다(ADR-013 예외).
 * 구 "Phase 9 — 포털 키포인트 허용" 정책은 폐기됐다. FE 도구바에서 키포인트 버튼을 숨긴 것만으로는
 * 정책이 강제되지 않는다(devtools 로 우회 가능) — <b>서버가 저장을 거부</b>해야 한다.
 *
 * <p><b>이 테스트가 잡는 것</b>:
 * <ul>
 *   <li>{@code lblTypeCd} allowlist 부재 — 16자 이하 임의 문자열이 그대로 DB 에 적재되던 결함
 *       (형제 {@code PortalUploadLabelService} 는 이미 BBOX|POLYGON fail-closed allowlist 보유).</li>
 *   <li>SKELETON 저장→로드 round-trip 성립 — 화면에서만 숨겨졌을 뿐 서버 기능이 살아있던 결함.</li>
 *   <li>정책 도입 이전에 적재된 <b>레거시 SKELETON row</b> 가 있어도 조회가 500 으로 깨지지 않을 것
 *       (삭제 마이그레이션은 별건 — 조회는 무시/스킵으로 안전해야 한다).</li>
 * </ul>
 */
class PortalKeypointRemovedTest {

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
        // 비식별 신고 게이트는 배선 검증용 IT(DeidentReportGateCoverageIT)가 실 경로로 확인한다.
        var deidentGate = mock(kr.co.cudo.authoring.label.service.LabelAccessGuard.class);
        service = new PortalLabelService(lblRepository, srcRepository, userLabelRepository,
                rawDataStatusRepository, videoRepository, deidentGate,
                new kr.co.cudo.authoring.portal.service.PortalRetentionPolicy(
                        org.mockito.Mockito.mock(kr.co.cudo.authoring.sysconfig.service.SystemConfigService.class)),
                new ObjectMapper(), null);

        LsDataSrc frame = LsDataSrc.create(RAW_SN, 0, "/f.jpg", null);
        when(srcRepository.findById(SRC_SN)).thenReturn(Optional.of(frame));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame));

        LsRawDataStatus approved = mock(LsRawDataStatus.class);
        when(approved.getDataSttsCd()).thenReturn(LsRawDataStatus.STTS_APPROVED);
        when(rawDataStatusRepository.findById(RAW_SN)).thenReturn(Optional.of(approved));

        when(userLabelRepository.findByPortalUserNoAndSrcDataSrcSnOrderByRegDtDesc(USER, SRC_SN))
                .thenReturn(List.of());
        when(userLabelRepository.save(any(LsPortalUserLabel.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private TokenClaims portalUser() {
        return new TokenClaims(USER, Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(600));
    }

    /** 17×[x,y,v] 삼중값 키포인트 JSON. */
    private static String skeletonJson() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 17; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(i).append(',').append(i).append(",2]");
        }
        return sb.append(']').toString();
    }

    // ─── 저장 allowlist (이슈1) ───

    @Test
    @DisplayName("포털_라벨_저장시_lblTypeCd가_SKELETON이면_400을_반환한다")
    void saveSkeleton_rejected() {
        PortalUserLabelRequest req = new PortalUserLabelRequest(
                RAW_SN, SRC_SN, LsDataLbl.TYPE_SKELETON, "person", skeletonJson(), null, null);

        assertThatThrownBy(() -> service.saveUserLabel(req, portalUser()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(userLabelRepository, never()).save(any());
    }

    @Test
    @DisplayName("포털_라벨_저장시_lblTypeCd가_BBOX_POLYGON_외의_임의문자열이면_400을_반환한다")
    void saveArbitraryType_rejected() {
        // allowlist 부재 시 16자 이하 임의 문자열이 그대로 적재됐다(fail-open).
        for (String type : List.of("SEGMENT", "TRACK", "'; DROP TABLE--", "<script>", "bbox ", "")) {
            PortalUserLabelRequest req = new PortalUserLabelRequest(
                    RAW_SN, SRC_SN, type, "person", "[[0,0],[10,10]]", null, null);
            assertThatThrownBy(() -> service.saveUserLabel(req, portalUser()))
                    .as("lblTypeCd=%s", type)
                    .isInstanceOf(CustomException.class)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }
        verify(userLabelRepository, never()).save(any());
    }

    @Test
    @DisplayName("포털_라벨_저장시_lblTypeCd가_BBOX면_정상_저장된다")
    void saveBbox_ok() {
        PortalUserLabelRequest req = new PortalUserLabelRequest(
                RAW_SN, SRC_SN, LsDataLbl.TYPE_BBOX, "car", "[[0,0],[10,10]]", null, null);

        assertThat(service.saveUserLabel(req, portalUser()).lblTypeCd()).isEqualTo(LsDataLbl.TYPE_BBOX);
        verify(userLabelRepository).save(any());
    }

    @Test
    @DisplayName("포털_라벨_저장시_lblTypeCd가_POLYGON이면_정상_저장된다")
    void savePolygon_ok() {
        PortalUserLabelRequest req = new PortalUserLabelRequest(
                RAW_SN, SRC_SN, LsDataLbl.TYPE_POLYGON, "car", "[[0,0],[10,0],[10,10]]", null, null);

        assertThat(service.saveUserLabel(req, portalUser()).lblTypeCd()).isEqualTo(LsDataLbl.TYPE_POLYGON);
        verify(userLabelRepository).save(any());
    }

    // ─── 조회 (레거시 row 안전 처리) ───

    @Test
    @DisplayName("레거시_SKELETON_row가_존재해도_조회시_예외없이_스킵된다")
    void legacySkeletonRow_skippedOnLoad() {
        // given: 정책 도입 이전에 적재된 SKELETON user-label + BBOX user-label
        LsPortalUserLabel legacy = LsPortalUserLabel.create(
                USER, RAW_SN, SRC_SN, LsDataLbl.TYPE_SKELETON, "person", skeletonJson());
        LsPortalUserLabel bbox = LsPortalUserLabel.create(
                USER, RAW_SN, SRC_SN, LsDataLbl.TYPE_BBOX, "car", "[[0,0],[10,10]]");
        when(userLabelRepository.findByPortalUserNoAndSrcDataSrcSnOrderByRegDtDesc(USER, SRC_SN))
                .thenReturn(List.of(legacy, bbox));

        PortalFrameLabelsResponse[] holder = new PortalFrameLabelsResponse[1];
        assertThatCode(() -> holder[0] = service.loadFrameLabels(SRC_SN, portalUser()))
                .doesNotThrowAnyException();

        // then: SKELETON 은 노출되지 않고(포털은 BBOX/POLYGON 전용) BBOX 만 반환된다.
        List<PortalFrameLabelsResponse.Item> items = holder[0].labels();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).lblTypeCd()).isEqualTo(LsDataLbl.TYPE_BBOX);
    }

    @Test
    @DisplayName("데이터마트_원본에_SKELETON이_있어도_포털_조회에는_노출되지_않는다")
    void datamartSkeleton_notExposed() {
        LsDataLbl skeleton = LsDataLbl.createManual(
                SRC_SN, LsDataLbl.TYPE_SKELETON, null, "person", skeletonJson(), "1");
        LsDataLbl bbox = LsDataLbl.createManual(
                SRC_SN, LsDataLbl.TYPE_BBOX, null, "car", "[[0,0],[10,10]]", "1");
        when(lblRepository.findBySrcSn(SRC_SN)).thenReturn(List.of(skeleton, bbox));

        PortalFrameLabelsResponse res = service.loadFrameLabels(SRC_SN, portalUser());

        assertThat(res.labels()).hasSize(1);
        assertThat(res.labels().get(0).lblTypeCd()).isEqualTo(LsDataLbl.TYPE_BBOX);
    }

    // ─── 기존 인가 게이트 회귀 (구 PortalLabelServiceKeypointTest 이관) ───

    @Test
    @DisplayName("포털_비APPROVED_영상_사용자라벨_저장_거부_403")
    void saveUserLabelNotApprovedForbidden() {
        Long pendingRaw = 999L;
        when(rawDataStatusRepository.findById(pendingRaw)).thenReturn(Optional.empty());
        PortalUserLabelRequest req = new PortalUserLabelRequest(
                pendingRaw, SRC_SN, LsDataLbl.TYPE_BBOX, "car", "[[0,0],[10,10]]", null, null);

        assertThatThrownBy(() -> service.saveUserLabel(req, portalUser()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }
}
