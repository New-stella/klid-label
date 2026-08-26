package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.DatamartLabelResponse;
import kr.co.cudo.authoring.portal.dto.PortalUserLabelRequest;
import kr.co.cudo.authoring.portal.dto.PortalUserLabelResponse;
import kr.co.cudo.authoring.portal.entity.LsPortalUserLabel;
import kr.co.cudo.authoring.portal.repository.LsPortalUserLabelRepository;
import kr.co.cudo.authoring.portal.service.PortalLabelService;
import kr.co.cudo.authoring.portal.service.PortalUploadLabelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class PortalUserLabelServiceTest {

    @Mock LsDataLblRepository lblRepository;
    @Mock LsDataSrcRepository srcRepository;
    @Mock LsPortalUserLabelRepository userLabelRepository;
    @Mock kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository rawDataStatusRepository;
    @Mock kr.co.cudo.authoring.label.repository.LsLabelRepository labelMasterRepository;

    private PortalLabelService service;

    /**
     * 비식별 누락 신고 게이트. 기본은 no-op(통과)이며, 신고 구간 검증 케이스에서만 412 를 던지도록 스텁한다.
     * (실 경로 배선은 {@code DeidentReportGateCoverageIT} 가 확인한다.)
     */
    private kr.co.cudo.authoring.label.service.LabelAccessGuard deidentGate;

    private final TokenClaims alice = new TokenClaims("alice", Role.PORTAL_USER, Channel.PORTAL,
            Instant.now().plusSeconds(3600));
    private final TokenClaims bob = new TokenClaims("bob", Role.PORTAL_USER, Channel.PORTAL,
            Instant.now().plusSeconds(3600));

    @BeforeEach
    void setUp() {
        // DEV_FIX-A(S7) — 비식별 신고 게이트(LabelAccessGuard)는 배선 검증용 IT
        //   (DeidentReportGateCoverageIT) 에서 실 경로로 확인한다. 여기서는 no-op mock 으로 기존 검증에 집중.
        deidentGate = org.mockito.Mockito.mock(
                kr.co.cudo.authoring.label.service.LabelAccessGuard.class);
        service = new PortalLabelService(lblRepository, srcRepository, userLabelRepository,
                rawDataStatusRepository, null, deidentGate,
                new kr.co.cudo.authoring.portal.service.PortalRetentionPolicy(
                        org.mockito.Mockito.mock(kr.co.cudo.authoring.sysconfig.service.SystemConfigService.class)),
                new com.fasterxml.jackson.databind.ObjectMapper(), labelMasterRepository);

        when(userLabelRepository.save(any(LsPortalUserLabel.class))).thenAnswer(inv -> {
            LsPortalUserLabel e = inv.getArgument(0);
            setField(e, "userLblSn", 1L);
            return e;
        });

        // Phase 9 이슈4 — saveUserLabel 이 APPROVED 게이트를 거치므로 저장 대상 rawSn(100L)을 APPROVED 로 스텁.
        // (content 검증 케이스가 게이트가 아닌 검증 경로에 도달하도록.)
        kr.co.cudo.authoring.assignment.entity.LsRawDataStatus approved =
                kr.co.cudo.authoring.assignment.entity.LsRawDataStatus.initial(100L);
        approved.transitionTo(kr.co.cudo.authoring.assignment.entity.LsRawDataStatus.STTS_APPROVED);
        when(rawDataStatusRepository.findById(100L)).thenReturn(java.util.Optional.of(approved));
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    // ─── 데이터마트 라벨 Load ───

    @Test
    @DisplayName("APPROVED_비신고_영상의_datamart_라벨조회는_기존과_동일하게_200이다")
    void loadDatamartLabels_returnsDtoList() {
        LsDataLbl lbl = LsDataLbl.createAutoBbox(10L, null, "person", "[1,2,3,4]",
                BigDecimal.valueOf(0.9), null);
        when(lblRepository.findAllByRawSn(100L)).thenReturn(List.of(lbl));

        List<DatamartLabelResponse> result = service.loadDatamartLabels(100L, 0, 100, alice);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).label()).isEqualTo("person");
        assertThat(result.get(0).srcSn()).isEqualTo(10L);
    }

    @Test
    @DisplayName("V2_데이터마트_rawSn_null_시_INVALID_INPUT")
    void loadDatamartLabels_nullRawSn_rejected() {
        assertThatThrownBy(() -> service.loadDatamartLabels(null, 0, 100, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    // ─── 2차 QA (CWE-862/639/359) — datamart 라벨 Load 게이트 ───

    @Test
    @DisplayName("미승인_PENDING_영상의_datamart_라벨조회는_403이다")
    void loadDatamartLabels_notApproved_forbidden() {
        // given: rawSn=200 은 APPROVED 스텁이 없다(= PENDING/반려 등 미노출 상태)
        assertThatThrownBy(() -> service.loadDatamartLabels(200L, 0, 100, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("FORBIDDEN");

        // then: 게이트가 DB 풀조회 이전에 평가되어 라벨 조회 자체가 일어나지 않는다.
        verify(lblRepository, never()).findAllByRawSn(any());
    }

    @Test
    @DisplayName("비식별신고구간_영상의_datamart_라벨조회는_412이다")
    void loadDatamartLabels_underDeidentReport_preconditionFailed() {
        // given: APPROVED(=데이터마트 노출) 이지만 비식별 누락 신고가 열린 영상
        org.mockito.Mockito.doThrow(new CustomException(
                        kr.co.cudo.authoring.common.exception.ErrorCode.PRECONDITION_FAILED,
                        "비식별 재처리 대기 중인 영상은 라벨을 조회할 수 없습니다."))
                .when(deidentGate).requireNotUnderDeidentReport(100L);

        assertThatThrownBy(() -> service.loadDatamartLabels(100L, 0, 100, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("PRECONDITION_FAILED");

        verify(lblRepository, never()).findAllByRawSn(any());
    }

    @Test
    @DisplayName("신고_해제_후_datamart_라벨조회는_다시_200으로_복원된다")
    void loadDatamartLabels_afterResolve_reopened() {
        LsDataLbl lbl = LsDataLbl.createAutoBbox(10L, null, "person", "[1,2,3,4]",
                BigDecimal.valueOf(0.9), null);
        when(lblRepository.findAllByRawSn(100L)).thenReturn(List.of(lbl));

        // given: 신고 구간 — 412
        org.mockito.Mockito.doThrow(new CustomException(
                        kr.co.cudo.authoring.common.exception.ErrorCode.PRECONDITION_FAILED, "blocked"))
                .when(deidentGate).requireNotUnderDeidentReport(100L);
        assertThatThrownBy(() -> service.loadDatamartLabels(100L, 0, 100, alice))
                .isInstanceOf(CustomException.class);

        // when: resolve('F'→'Y') 로 게이트가 열린다(별도 복원 절차 없음)
        org.mockito.Mockito.doNothing().when(deidentGate).requireNotUnderDeidentReport(100L);

        // then: 보존된 라벨이 그대로 반환된다
        assertThat(service.loadDatamartLabels(100L, 0, 100, alice)).hasSize(1);
    }

    @Test
    @DisplayName("rawSn이_존재하지_않으면_예외없이_403이다")
    void loadDatamartLabels_unknownRawSn_forbidden() {
        // 미존재와 미승인을 구분하지 않는다(존재 여부 오라클 차단 — CWE-209).
        assertThatThrownBy(() -> service.loadDatamartLabels(999_999L, 0, 100, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("FORBIDDEN");
        verify(lblRepository, never()).findAllByRawSn(any());
    }

    @Test
    @DisplayName("actor가_null이면_401이다")
    void loadDatamartLabels_noToken_unauthorized() {
        assertThatThrownBy(() -> service.loadDatamartLabels(100L, 0, 100, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("UNAUTHORIZED");
        verify(lblRepository, never()).findAllByRawSn(any());
    }

    // ─── 3차 QA (CWE-190/129) — 페이징 산술 오버플로 · clamp 경계 ───

    /** rawSn=100(APPROVED) 라벨 n 건 스텁. */
    private void stubLabels(int count) {
        List<LsDataLbl> labels = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            labels.add(LsDataLbl.createAutoBbox(10L + i, null, "person", "[1,2,3,4]",
                    BigDecimal.valueOf(0.9), null));
        }
        when(lblRepository.findAllByRawSn(100L)).thenReturn(labels);
    }

    @Test
    @DisplayName("page가_Integer_MAX_근처여도_오버플로_없이_빈리스트를_반환한다")
    void loadDatamartLabels_pageOverflow_returnsEmpty() {
        // given: 라벨 1건 — page * size 가 int 범위를 넘으면 fromIndex 가 음수로 접혀
        //   subList(음수, 음수) → IndexOutOfBoundsException → 500 이 나던 회귀(CWE-190/129).
        //   인증된 PORTAL_USER 누구나 APPROVED rawSn 하나만 알면 트리거 가능했다.
        stubLabels(1);

        // when/then: 예외 없이 빈 리스트(범위 밖 페이지의 정상 응답)
        assertThat(service.loadDatamartLabels(100L, Integer.MAX_VALUE, 100, alice)).isEmpty();
        assertThat(service.loadDatamartLabels(100L, Integer.MAX_VALUE, 1, alice)).isEmpty();
        assertThat(service.loadDatamartLabels(100L, Integer.MAX_VALUE - 1, 100, alice)).isEmpty();
    }

    @Test
    @DisplayName("size가_1미만이면_1로_clamp된다")
    void loadDatamartLabels_sizeBelowOne_clampedToOne() {
        stubLabels(3);

        assertThat(service.loadDatamartLabels(100L, 0, 0, alice)).hasSize(1);
        assertThat(service.loadDatamartLabels(100L, 0, -10, alice)).hasSize(1);
    }

    @Test
    @DisplayName("size가_100초과면_100으로_clamp된다")
    void loadDatamartLabels_sizeAboveMax_clampedTo100() {
        stubLabels(150);

        assertThat(service.loadDatamartLabels(100L, 0, 99_999, alice)).hasSize(100);
        assertThat(service.loadDatamartLabels(100L, 0, Integer.MAX_VALUE, alice)).hasSize(100);
    }

    @Test
    @DisplayName("page가_음수면_0으로_clamp된다")
    void loadDatamartLabels_negativePage_clampedToZero() {
        stubLabels(3);

        // page=-5 → 0 페이지(선두 2건). 음수 page 가 그대로 곱해지면 음수 인덱스 예외가 난다.
        assertThat(service.loadDatamartLabels(100L, -5, 2, alice)).hasSize(2);
        assertThat(service.loadDatamartLabels(100L, Integer.MIN_VALUE, 2, alice)).hasSize(2);
    }

    // ─── 사용자 라벨 저장 ───

    @Test
    @DisplayName("V2_사용자_작업_데이터_별도_적재_원본_미수정")
    void saveUserLabel_savesToPortalTable() {
        PortalUserLabelRequest req = new PortalUserLabelRequest(100L, 10L, "BBOX", "person", "[1,2,3,4]", null, null);

        PortalUserLabelResponse resp = service.saveUserLabel(req, alice);

        assertThat(resp.sourceRawSn()).isEqualTo(100L);
        assertThat(resp.sourceSrcSn()).isEqualTo(10L);
        assertThat(resp.lblTypeCd()).isEqualTo("BBOX");
        // R16 — 저장 응답에 points 직렬화 누락 회귀 방지: 좌표가 응답에 채워져야 한다.
        //   단 적재·응답값은 요청 원문 echo 가 아니라 <b>정규형 재직렬화</b> 결과다(저장 크기 상한 실효화).
        //   레거시 평탄 입력([1,2,3,4])도 정규형([[1.0,2.0],[3.0,4.0]])으로 통일 적재된다.
        assertThat(resp.points()).isEqualTo("[[1.0,2.0],[3.0,4.0]]");
        assertThat(resp.label()).isEqualTo("person");

        ArgumentCaptor<LsPortalUserLabel> captor = ArgumentCaptor.forClass(LsPortalUserLabel.class);
        verify(userLabelRepository).save(captor.capture());
        assertThat(captor.getValue().getPortalUserNo()).isEqualTo("alice");
        verify(lblRepository, never()).save(any());
    }

    @Test
    @DisplayName("R17_사용자_라벨_저장_빈_좌표_JSON_시_INVALID_INPUT_빈라벨row_차단")
    void saveUserLabel_emptyPoints_rejected() {
        // given: points 가 빈 좌표 배열('[]') — 검증 우회로 빈 라벨 row 가 생기던 회귀
        PortalUserLabelRequest req = new PortalUserLabelRequest(100L, 10L, "BBOX", "person", "[]", null, null);

        // when/then: INVALID_INPUT 으로 거부, 저장 미수행 (fail-closed)
        assertThatThrownBy(() -> service.saveUserLabel(req, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
        verify(userLabelRepository, never()).save(any());
    }

    // ─── 마스터 연결·트랙 연결 왕복 보존 (API-082 / ERD-018) ───

    @Test
    @DisplayName("활성_마스터에_실재하는_labelId와_trackId는_그대로_적재된다")
    void saveUserLabel_persistsMasterAndTrackLink() {
        givenActiveLabelMaster(12L);
        PortalUserLabelRequest req = new PortalUserLabelRequest(
                100L, 10L, "BBOX", "person", "[[1,2],[3,4]]", 12L, "trk-0007");

        PortalUserLabelResponse resp = service.saveUserLabel(req, alice);

        ArgumentCaptor<LsPortalUserLabel> captor = ArgumentCaptor.forClass(LsPortalUserLabel.class);
        verify(userLabelRepository).save(captor.capture());
        assertThat(captor.getValue().getLabelId()).isEqualTo(12L);
        assertThat(captor.getValue().getTrackId()).isEqualTo("trk-0007");
        // 응답도 <적재된 값>을 담는다 — 저장 응답과 조회 계열 응답의 형태가 갈리지 않게.
        assertThat(resp.labelId()).isEqualTo(12L);
        assertThat(resp.trackId()).isEqualTo("trk-0007");
    }

    @Test
    @DisplayName("활성_마스터에_없는_labelId는_그_값만_비우고_저장은_성공한다")
    void saveUserLabel_unknownLabelId_droppedButSaved() {
        // given: 요청이 실어 보낸 마스터 참조가 활성 마스터에 없다(비활성화됐거나 애초에 없는 값).
        when(labelMasterRepository.findByLabelIdInAndUseYn(List.of(999L), "Y")).thenReturn(List.of());
        PortalUserLabelRequest req = new PortalUserLabelRequest(
                100L, 10L, "BBOX", "person", "[[1,2],[3,4]]", 999L, null);

        PortalUserLabelResponse resp = service.saveUserLabel(req, alice);

        // 요청 자체는 거부하지 않는다 — 마스터 비활성화 때문에 사용자의 작업 저장이 막히면 안 된다.
        ArgumentCaptor<LsPortalUserLabel> captor = ArgumentCaptor.forClass(LsPortalUserLabel.class);
        verify(userLabelRepository).save(captor.capture());
        assertThat(captor.getValue().getLabelId()).isNull();
        assertThat(resp.labelId()).isNull();
        assertThat(resp.points()).isEqualTo("[[1.0,2.0],[3.0,4.0]]");
    }

    @Test
    @DisplayName("labelId_미지정이면_마스터를_조회하지_않고_비운_채_저장한다")
    void saveUserLabel_noLabelId_skipsMasterLookup() {
        PortalUserLabelRequest req = new PortalUserLabelRequest(
                100L, 10L, "BBOX", "person", "[[1,2],[3,4]]", null, null);

        service.saveUserLabel(req, alice);

        // 라벨명으로 유추해 채우지 않는다(동명이인·비활성 마스터 오매칭 방지).
        verify(labelMasterRepository, never()).findByLabelIdInAndUseYn(any(), any());
        ArgumentCaptor<LsPortalUserLabel> captor = ArgumentCaptor.forClass(LsPortalUserLabel.class);
        verify(userLabelRepository).save(captor.capture());
        assertThat(captor.getValue().getLabelId()).isNull();
        assertThat(captor.getValue().getTrackId()).isNull();
    }

    @Test
    @DisplayName("컬럼_폭을_넘는_trackId는_400이다_적재시점_DB오류로_새지_않는다")
    void saveUserLabel_tooLongTrackId_rejected() {
        String tooLong = "t".repeat(LsPortalUserLabel.TRACK_ID_MAX_LENGTH + 1);
        PortalUserLabelRequest req = new PortalUserLabelRequest(
                100L, 10L, "BBOX", "person", "[[1,2],[3,4]]", null, tooLong);

        assertThatThrownBy(() -> service.saveUserLabel(req, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
        verify(userLabelRepository, never()).save(any());
    }

    @Test
    @DisplayName("상한_길이의_trackId는_통과한다_경계")
    void saveUserLabel_trackIdAtLimit_accepted() {
        String atLimit = "t".repeat(LsPortalUserLabel.TRACK_ID_MAX_LENGTH);
        PortalUserLabelRequest req = new PortalUserLabelRequest(
                100L, 10L, "BBOX", "person", "[[1,2],[3,4]]", null, atLimit);

        assertThat(service.saveUserLabel(req, alice).trackId()).isEqualTo(atLimit);
    }

    private void givenActiveLabelMaster(Long labelId) {
        kr.co.cudo.authoring.label.entity.LsLabel master =
                org.mockito.Mockito.mock(kr.co.cudo.authoring.label.entity.LsLabel.class);
        when(master.getLabelId()).thenReturn(labelId);
        when(labelMasterRepository.findByLabelIdInAndUseYn(List.of(labelId), "Y"))
                .thenReturn(List.of(master));
    }

    // ─── 자원 상한 (CWE-770) — 좌표 개수 캡. 형제 PortalUploadLabelService 상수 재사용 ───

    /** 점 count 개짜리 정규형 좌표 JSON([[x,y], ...]). */
    private static String polygonJson(int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(',');
            sb.append('[').append(i).append(',').append(i).append(']');
        }
        return sb.append(']').toString();
    }

    @Test
    @DisplayName("POLYGON_좌표가_상한을_초과하면_400을_반환한다")
    void saveUserLabel_polygonAboveMaxPoints_rejected() {
        PortalUserLabelRequest req = new PortalUserLabelRequest(100L, 10L, "POLYGON", "person",
                polygonJson(PortalUploadLabelService.POLYGON_MAX_POINTS + 1), null, null);

        assertThatThrownBy(() -> service.saveUserLabel(req, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
        verify(userLabelRepository, never()).save(any());
    }

    @Test
    @DisplayName("POLYGON_좌표가_상한_이내면_정상_저장된다")
    void saveUserLabel_polygonAtMaxPoints_saved() {
        PortalUserLabelRequest req = new PortalUserLabelRequest(100L, 10L, "POLYGON", "person",
                polygonJson(PortalUploadLabelService.POLYGON_MAX_POINTS), null, null);

        assertThat(service.saveUserLabel(req, alice).lblTypeCd()).isEqualTo("POLYGON");
        verify(userLabelRepository).save(any());
    }

    @Test
    @DisplayName("POLYGON_좌표가_3점_미만이면_400을_반환한다")
    void saveUserLabel_polygonBelowMinPoints_rejected() {
        PortalUserLabelRequest req = new PortalUserLabelRequest(100L, 10L, "POLYGON", "person",
                polygonJson(PortalUploadLabelService.POLYGON_MIN_POINTS - 1), null, null);

        assertThatThrownBy(() -> service.saveUserLabel(req, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
        verify(userLabelRepository, never()).save(any());
    }

    @Test
    @DisplayName("BBOX_좌표가_2점이_아니면_400을_반환한다")
    void saveUserLabel_bboxWrongPointCount_rejected() {
        PortalUserLabelRequest req = new PortalUserLabelRequest(100L, 10L, "BBOX", "person",
                polygonJson(3), null, null);

        assertThatThrownBy(() -> service.saveUserLabel(req, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
        verify(userLabelRepository, never()).save(any());
    }

    // ─── 저장값 정규화 (CWE-770 자원 상한 실효화) ───
    //   좌표 개수 캡(validatePointCount)은 <b>파싱된 개수</b>만 보는데 적재값은 요청 원문 문자열
    //   그대로였다 — 유효 JSON 공백 패딩·고정밀 소수로 개수 캡을 통과한 채 64KB row 를 적재할 수
    //   있어 개수 캡이 저장 자원 방어로 성립하지 않았다. 형제 경로
    //   (PortalUploadLabelService.validateAndPrepare)와 동일하게 <b>정규화 재직렬화</b>한 값을 적재한다.

    /** 저장된 엔티티의 POINT_CN(적재 원문)을 캡처한다. */
    private String capturedPointCn() {
        ArgumentCaptor<LsPortalUserLabel> captor = ArgumentCaptor.forClass(LsPortalUserLabel.class);
        verify(userLabelRepository).save(captor.capture());
        return captor.getValue().getPointCn();
    }

    @Test
    @DisplayName("좌표에_공백을_과도하게_포함해도_저장_크기가_상한_이내로_정규화된다")
    void saveUserLabel_whitespacePaddedPoints_normalizedOnSave() {
        // given: 유효 JSON 이지만 60KB 공백이 낀 BBOX 2점 — 파싱하면 2점이라 개수 캡을 통과한다.
        String padding = " ".repeat(60_000);
        String bloated = "[[1," + padding + "2],[3,4]]";
        assertThat(bloated.length()).isGreaterThan(60_000);
        PortalUserLabelRequest req = new PortalUserLabelRequest(100L, 10L, "BBOX", "person", bloated, null, null);

        // when
        PortalUserLabelResponse resp = service.saveUserLabel(req, alice);

        // then: 적재값은 원문이 아니라 정규화 재직렬화 결과 — 원문 크기와 무관하게 작다.
        //   실측: 요청 원문 60,013자 → 적재 21자(약 2,858배 축소). 구현 이전에는 60,013자가 그대로
        //   POINT_CN(TEXT) 에 적재됐고, 좌표 개수 캡(BBOX 2점)은 이를 전혀 막지 못했다.
        String stored = capturedPointCn();
        assertThat(bloated).hasSize(60_013);
        assertThat(stored).isEqualTo("[[1.0,2.0],[3.0,4.0]]").hasSize(21);
        assertThat(resp.points()).isEqualTo(stored);
        // 좌표값 자체는 보존된다.
        assertThat(parse(stored)).containsExactly(List.of(1.0, 2.0), List.of(3.0, 4.0));
    }

    @Test
    @DisplayName("좌표값이_Infinity면_400을_반환한다")
    void saveUserLabel_infiniteCoordinate_rejected() {
        // given: 1e400 은 유효 JSON 숫자 리터럴이지만 double 로는 Infinity 다.
        //   형제 경로는 Double.isFinite 로 거부하는데 이 경로만 그대로 적재됐다.
        PortalUserLabelRequest req = new PortalUserLabelRequest(100L, 10L, "BBOX", "person",
                "[[1e400,2],[3,4]]", null, null);

        assertThatThrownBy(() -> service.saveUserLabel(req, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
        verify(userLabelRepository, never()).save(any());
    }

    @Test
    @DisplayName("좌표값이_음의_Infinity면_400을_반환한다")
    void saveUserLabel_negativeInfiniteCoordinate_rejected() {
        PortalUserLabelRequest req = new PortalUserLabelRequest(100L, 10L, "BBOX", "person",
                "[[1,2],[3,-1e400]]", null, null);

        assertThatThrownBy(() -> service.saveUserLabel(req, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
        verify(userLabelRepository, never()).save(any());
    }

    @Test
    @DisplayName("좌표값이_NaN이면_400을_반환한다")
    void saveUserLabel_nanCoordinate_rejected() {
        // NaN 리터럴은 표준 JSON 이 아니라 파싱 단계에서 걸러지고, 파싱을 통과하더라도
        // 유한성 검증이 뒤를 받친다 — 어느 층에서 막히든 응답은 400 이어야 한다.
        PortalUserLabelRequest req = new PortalUserLabelRequest(100L, 10L, "BBOX", "person",
                "[[NaN,2],[3,4]]", null, null);

        assertThatThrownBy(() -> service.saveUserLabel(req, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
        verify(userLabelRepository, never()).save(any());
    }

    @Test
    @DisplayName("정상_좌표는_기존과_동일하게_저장되고_조회시_동일한_값을_반환한다")
    void saveUserLabel_normalPoints_roundTripPreserved() {
        // given: 소수점 좌표 — 재직렬화가 값을 왜곡하지 않아야 한다(회귀).
        PortalUserLabelRequest req = new PortalUserLabelRequest(100L, 10L, "POLYGON", "person",
                "[[1.5,2.25],[3,4],[10.125,20.5]]", null, null);

        PortalUserLabelResponse resp = service.saveUserLabel(req, alice);

        assertThat(resp.lblTypeCd()).isEqualTo("POLYGON");
        assertThat(parse(capturedPointCn()))
                .containsExactly(List.of(1.5, 2.25), List.of(3.0, 4.0), List.of(10.125, 20.5));
    }

    /** 적재된 좌표 JSON → [[x,y], ...] (조회 경로와 동일 파서). */
    private static List<List<Double>> parse(String pointCn) {
        return kr.co.cudo.authoring.common.util.LabelPointSerializer
                .fromJson(pointCn, new com.fasterxml.jackson.databind.ObjectMapper()).stream()
                .map(p -> List.of(p.x(), p.y()))
                .toList();
    }

    // ─── 비식별 누락 신고 게이트 (저장 경로) ───

    @Test
    @DisplayName("비식별신고구간_영상의_본인라벨_저장은_412이다")
    void saveUserLabel_underDeidentReport_preconditionFailed() {
        // 조회 4경로(loadDatamartLabels/listMyLabels/loadFrameLabels/serveFrameImage)는 모두 신고
        // 게이트를 갖는데 저장 경로만 누락돼 있었다 — 신고 구간에도 좌표 적재가 계속 열려 있었다.
        org.mockito.Mockito.doThrow(new CustomException(
                        kr.co.cudo.authoring.common.exception.ErrorCode.PRECONDITION_FAILED,
                        "비식별 재처리 대기 중인 영상입니다."))
                .when(deidentGate).requireNotUnderDeidentReport(100L);

        PortalUserLabelRequest req = new PortalUserLabelRequest(100L, 10L, "BBOX", "person", "[1,2,3,4]", null, null);

        assertThatThrownBy(() -> service.saveUserLabel(req, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("PRECONDITION_FAILED");
        verify(userLabelRepository, never()).save(any());
    }

    @Test
    @DisplayName("V2_사용자_라벨_저장_토큰_없으면_401")
    void saveUserLabel_noToken_rejected() {
        PortalUserLabelRequest req = new PortalUserLabelRequest(100L, 10L, "BBOX", "person", "[1,2,3,4]", null, null);

        assertThatThrownBy(() -> service.saveUserLabel(req, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("UNAUTHORIZED");
    }

    // ─── 본인 라벨 조회 (IDOR) ───

    @Test
    @DisplayName("V2_본인_작업_라벨_조회_IDOR_본인만")
    void listMyLabels_returnsOnlyOwnLabels() {
        LsPortalUserLabel aliceLabel = LsPortalUserLabel.create("alice", 100L, 10L, "BBOX", "person", "[1,2]");
        setField(aliceLabel, "userLblSn", 1L);
        when(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc("alice", 100L))
                .thenReturn(List.of(aliceLabel));

        List<PortalUserLabelResponse> result = service.listMyLabels(100L, alice);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).lblTypeCd()).isEqualTo("BBOX");
    }

    @Test
    @DisplayName("V2_다른_사용자_데이터_접근_불가_IDOR")
    void listMyLabels_differentUser_returnsEmpty() {
        when(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc("bob", 100L))
                .thenReturn(List.of());

        List<PortalUserLabelResponse> result = service.listMyLabels(100L, bob);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("V2_본인_라벨_조회_rawSn_null_시_INVALID_INPUT")
    void listMyLabels_nullRawSn_rejected() {
        assertThatThrownBy(() -> service.listMyLabels(null, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    // ─── 3차 QA (CWE-862/359) — 본인 라벨 조회 게이트 대칭 ───
    //   loadDatamartLabels / loadFrameLabels 는 APPROVED + 신고 게이트를 갖는데 listMyLabels 만
    //   무게이트였다. 본인이 저장한 사본이라도 좌표는 원본과 같은 PII 위치정보라, 같은 영상의
    //   라벨이 한쪽 URL 에서는 412 인데 다른 URL 로는 200 으로 계속 나가는 비대칭 결함이었다.

    @Test
    @DisplayName("미승인_영상의_본인라벨_조회는_403이다")
    void listMyLabels_notApproved_forbidden() {
        // given: rawSn=200 은 APPROVED 스텁이 없다(= 데이터마트 미노출)
        assertThatThrownBy(() -> service.listMyLabels(200L, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("FORBIDDEN");

        // 게이트는 조회 이전에 평가된다.
        verify(userLabelRepository, never())
                .findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(any(), any());
    }

    @Test
    @DisplayName("비식별신고구간_영상의_본인라벨_조회는_412이다")
    void listMyLabels_underDeidentReport_preconditionFailed() {
        org.mockito.Mockito.doThrow(new CustomException(
                        kr.co.cudo.authoring.common.exception.ErrorCode.PRECONDITION_FAILED,
                        "비식별 재처리 대기 중인 영상은 라벨을 조회할 수 없습니다."))
                .when(deidentGate).requireNotUnderDeidentReport(100L);

        assertThatThrownBy(() -> service.listMyLabels(100L, alice))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("PRECONDITION_FAILED");

        verify(userLabelRepository, never())
                .findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(any(), any());
    }

    @Test
    @DisplayName("APPROVED_비신고_영상의_본인라벨_조회는_기존과_동일하게_200이다")
    void listMyLabels_approvedNotReported_unchanged() {
        LsPortalUserLabel aliceLabel = LsPortalUserLabel.create("alice", 100L, 10L, "BBOX", "person", "[1,2]");
        setField(aliceLabel, "userLblSn", 1L);
        when(userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc("alice", 100L))
                .thenReturn(List.of(aliceLabel));

        assertThat(service.listMyLabels(100L, alice)).hasSize(1);
    }

    @Test
    @DisplayName("본인라벨_조회_actor가_null이면_401이다")
    void listMyLabels_noToken_unauthorized() {
        assertThatThrownBy(() -> service.listMyLabels(100L, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("UNAUTHORIZED");
        verify(userLabelRepository, never())
                .findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(any(), any());
    }
}
