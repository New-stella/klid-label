package kr.co.cudo.authoring.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.DatamartVideoResponse;
import kr.co.cudo.authoring.portal.repository.LsPortalUserLabelRepository;
import kr.co.cudo.authoring.portal.repository.PortalUserWorkRepository;
import kr.co.cudo.authoring.portal.service.PortalLabelService;
import kr.co.cudo.authoring.portal.service.PortalRetentionPolicy;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase B — 포털 홈 데이터마트 영상 목록 (PortalLabelService.listDatamartVideos).
 *
 * <p>정책: 데이터마트 노출(검수 완료 = APPROVED) 영상만 노출, 프레임 0건 영상 제외(진입 불가).
 * <p>방어: HIGH(미승인 영상 게이트) — APPROVED 만 통과, MED(프레임 0건) — firstSrcSn 부재 제외.
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class PortalDatamartVideosServiceTest {

    @Mock LsDataLblRepository lblRepository;
    @Mock LsDataSrcRepository srcRepository;
    @Mock LsPortalUserLabelRepository userLabelRepository;
    @Mock PortalUserWorkRepository userWorkRepository;
    @Mock LsRawDataStatusRepository rawDataStatusRepository;
    @Mock VideoRepository videoRepository;
    @Mock SystemConfigService systemConfigService;

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
                rawDataStatusRepository, videoRepository, deidentGate,
                new PortalRetentionPolicy(systemConfigService), new ObjectMapper(), null,
                userWorkRepository,
                new kr.co.cudo.authoring.portal.service.PortalWorkableVideoPolicy(rawDataStatusRepository, videoRepository));
    }

    private LsDataRaw raw(Long rawSn, String clipId, String evnt) {
        LsDataRaw r = LsDataRaw.createFromIngest(clipId, "cctv-1", evnt, "lgv",
                LsDataRaw.PRVC_TYPE_ANONY, "/raw/v.mp4", LocalDateTime.now(), 30);
        setField(r, "rawSn", rawSn);
        return r;
    }

    private LsRawDataStatus approved(Long rawSn) {
        LsRawDataStatus st = LsRawDataStatus.initial(rawSn);
        setField(st, "dataSttsCd", LsRawDataStatus.STTS_APPROVED);
        setField(st, "updDt", LocalDateTime.of(2026, 6, 1, 10, 0));
        return st;
    }

    @Test
    @DisplayName("포털_데이터마트_목록_APPROVED_영상만_노출_프레임수_첫srcSn_이벤트명_enrich")
    void listDatamartVideos_approvedOnly_enriched() {
        // given: APPROVED 영상 1건 (프레임 5건, 첫 srcSn=100)
        Pageable pageable = PageRequest.of(0, 20);
        LsDataRaw v1 = raw(10L, "CLIP-10", "FALL");
        Page<LsDataRaw> page = new PageImpl<>(List.of(v1), pageable, 1);
        when(videoRepository.findAllWithReviewStatus(isNull(), eq(LsRawDataStatus.STTS_APPROVED), any(Pageable.class)))
                .thenReturn(page);
        when(srcRepository.findFirstSrcSnGroupedByRawSn(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 100L}));
        when(srcRepository.countByRawSnsGrouped(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 5L}));
        when(rawDataStatusRepository.findAllById(anyCollection()))
                .thenReturn(List.of(approved(10L)));

        // when
        Page<DatamartVideoResponse> result = service.listDatamartVideos(alice, pageable);

        // then
        assertThat(result.getContent()).hasSize(1);
        DatamartVideoResponse item = result.getContent().get(0);
        assertThat(item.rawSn()).isEqualTo(10L);
        assertThat(item.firstSrcSn()).isEqualTo(100L);
        assertThat(item.frameCount()).isEqualTo(5L);
        assertThat(item.eventName()).isEqualTo("FALL");
        assertThat(item.title()).isEqualTo("CLIP-10");
        assertThat(item.lastUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 6, 1, 10, 0));
    }

    @Test
    @DisplayName("포털_데이터마트_목록_미승인_영상은_쿼리_게이트로_미포함_HIGH방어")
    void listDatamartVideos_pendingExcludedByGate() {
        // given: BE 쿼리가 APPROVED 필터를 강제 — 미승인 영상은 page 자체에 없음
        Pageable pageable = PageRequest.of(0, 20);
        when(videoRepository.findAllWithReviewStatus(isNull(), eq(LsRawDataStatus.STTS_APPROVED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        // when
        Page<DatamartVideoResponse> result = service.listDatamartVideos(alice, pageable);

        // then: APPROVED 필터로 빈 결과 (미승인 영상은 노출되지 않음)
        assertThat(result.getContent()).isEmpty();
        // 게이트가 APPROVED 상수로 호출되었음을 시그니처로 보장 (다른 상태로 호출 시 stub 미스 → NPE)
    }

    @Test
    @DisplayName("포털_데이터마트_목록_프레임_0건_영상은_제외_MED방어")
    void listDatamartVideos_zeroFrameExcluded() {
        // given: APPROVED 영상 2건 — v1(프레임 3건), v2(프레임 0건 → firstSrcSn 없음)
        Pageable pageable = PageRequest.of(0, 20);
        LsDataRaw v1 = raw(10L, "CLIP-10", "FALL");
        LsDataRaw v2 = raw(20L, "CLIP-20", "FIRE");
        Page<LsDataRaw> page = new PageImpl<>(List.of(v1, v2), pageable, 2);
        when(videoRepository.findAllWithReviewStatus(isNull(), eq(LsRawDataStatus.STTS_APPROVED), any(Pageable.class)))
                .thenReturn(page);
        // v2 는 첫 srcSn / count 결과에 미포함 (프레임 0건)
        when(srcRepository.findFirstSrcSnGroupedByRawSn(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 100L}));
        when(srcRepository.countByRawSnsGrouped(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 3L}));
        when(rawDataStatusRepository.findAllById(anyCollection()))
                .thenReturn(List.of(approved(10L), approved(20L)));

        // when
        Page<DatamartVideoResponse> result = service.listDatamartVideos(alice, pageable);

        // then: 프레임 0건 영상(v2) 은 진입 불가하므로 제외 — v1 만 노출
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).rawSn()).isEqualTo(10L);
    }

    // ======================== 보존기간 만료 예정 시각 (AC-033 / DFEAT-055) ========================

    private static final LocalDateTime SAVED_AT = LocalDateTime.of(2026, 8, 1, 9, 0);

    /** APPROVED 영상 1건 + 프레임 enrich stub — 만료 예정 시각 축만 남기기 위한 공통 배경. */
    private void givenOneApprovedVideo() {
        when(videoRepository.findAllWithReviewStatus(isNull(), eq(LsRawDataStatus.STTS_APPROVED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(raw(10L, "CLIP-10", "FALL")), PageRequest.of(0, 20), 1));
        when(srcRepository.findFirstSrcSnGroupedByRawSn(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 100L}));
        when(srcRepository.countByRawSnsGrouped(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 5L}));
        when(rawDataStatusRepository.findAllById(anyCollection())).thenReturn(List.of(approved(10L)));
    }

    private DatamartVideoResponse firstRow() {
        return service.listDatamartVideos(alice, PageRequest.of(0, 20)).getContent().get(0);
    }

    @Test
    @DisplayName("포털_데이터마트_목록_본인_저작_최초저장일_기준으로_만료예정시각이_계산된다")
    void myLabelExpiresAtFromFirstSavedAt() {
        // given: 본인 저장 라벨 최초 저장일(MIN) + 보존기간 7일 — 마지막 저장일이 아니다(DFEAT-055)
        givenOneApprovedVideo();
        when(userWorkRepository.findEarliestAuthoredAtByVideo(eq("alice"), anyCollection()))
                .thenReturn(java.util.Map.of(10L, SAVED_AT));
        when(systemConfigService.getInt(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS)).thenReturn(7);

        // when / then
        assertThat(firstRow().myLabelExpiresAt()).isEqualTo(SAVED_AT.plusDays(7));
    }

    @Test
    @DisplayName("포털_데이터마트_목록_세_저작물이_하나도_없으면_만료예정시각은_null")
    void myLabelExpiresAtNullWhenNoSavedLabel() {
        // given: 그 영상에 본인 저장 라벨이 없다(집계 결과 행 자체가 없음)
        givenOneApprovedVideo();
        when(userWorkRepository.findEarliestAuthoredAtByVideo(eq("alice"), anyCollection()))
                .thenReturn(java.util.Map.of());
        when(systemConfigService.getInt(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS)).thenReturn(7);

        // when / then: 기준점이 없으면 값을 지어내지 않는다
        assertThat(firstRow().myLabelExpiresAt()).isNull();
    }

    @Test
    @DisplayName("포털_데이터마트_목록_보존기간을_7에서_14로_바꾸고_재조회하면_만료예정시각이_갱신된다_AC033")
    void myLabelExpiresAtRecomputedOnSettingChange() {
        // given: 7일로 한 번 조회
        givenOneApprovedVideo();
        when(userWorkRepository.findEarliestAuthoredAtByVideo(eq("alice"), anyCollection()))
                .thenReturn(java.util.Map.of(10L, SAVED_AT));
        when(systemConfigService.getInt(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS)).thenReturn(7);
        assertThat(firstRow().myLabelExpiresAt()).isEqualTo(SAVED_AT.plusDays(7));

        // when: 설정만 14일로 변경 후 재조회 (DB 컬럼으로 고정된 값이 아니어야 한다)
        when(systemConfigService.getInt(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS)).thenReturn(14);

        // then
        assertThat(firstRow().myLabelExpiresAt()).isEqualTo(SAVED_AT.plusDays(14));
    }

    @Test
    @DisplayName("포털_데이터마트_목록_보존기간_설정이_없으면_만료예정시각만_null이고_목록은_정상_반환")
    void missingRetentionConfigNullsOnlyTheField() {
        // given: 설정 행 부재 — getInt 가 예외를 던진다(이 키는 폴백하지 않는다)
        givenOneApprovedVideo();
        when(userWorkRepository.findEarliestAuthoredAtByVideo(eq("alice"), anyCollection()))
                .thenReturn(java.util.Map.of(10L, SAVED_AT));
        when(systemConfigService.getInt(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS))
                .thenThrow(new CustomException(
                        kr.co.cudo.authoring.common.exception.ErrorCode.NOT_FOUND, "설정 없음"));

        // when
        DatamartVideoResponse row = firstRow();

        // then: 목록 자체는 500 으로 깨지지 않는다
        assertThat(row.rawSn()).isEqualTo(10L);
        assertThat(row.myLabelExpiresAt()).isNull();
    }

    @Test
    @DisplayName("포털_데이터마트_목록_저작_기산점_조회와_설정조회는_페이지당_각_1회다_N플러스1_부재")
    void expiryLookupsAreBatchedPerPage() {
        // given: APPROVED 영상 2건
        Pageable pageable = PageRequest.of(0, 20);
        when(videoRepository.findAllWithReviewStatus(isNull(), eq(LsRawDataStatus.STTS_APPROVED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(raw(10L, "CLIP-10", "FALL"), raw(20L, "CLIP-20", "FIRE")),
                        pageable, 2));
        when(srcRepository.findFirstSrcSnGroupedByRawSn(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 100L}, new Object[]{20L, 200L}));
        when(srcRepository.countByRawSnsGrouped(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 3L}, new Object[]{20L, 4L}));
        when(rawDataStatusRepository.findAllById(anyCollection()))
                .thenReturn(List.of(approved(10L), approved(20L)));
        when(userWorkRepository.findEarliestAuthoredAtByVideo(eq("alice"), anyCollection()))
                .thenReturn(java.util.Map.of(10L, SAVED_AT));
        when(systemConfigService.getInt(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS)).thenReturn(7);

        // when
        Page<DatamartVideoResponse> result = service.listDatamartVideos(alice, pageable);

        // then: 행 수와 무관하게 집계 1회 + 설정 1회
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).myLabelExpiresAt()).isEqualTo(SAVED_AT.plusDays(7));
        assertThat(result.getContent().get(1).myLabelExpiresAt()).isNull();
        verify(userWorkRepository, times(1)).findEarliestAuthoredAtByVideo(eq("alice"), anyCollection());
        verify(systemConfigService, times(1)).getInt(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS);
    }

    @Test
    @DisplayName("포털_데이터마트_목록_null_actor_401")
    void listDatamartVideos_nullActor_unauthorized() {
        assertThatThrownBy(() -> service.listDatamartVideos(null, PageRequest.of(0, 20)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("UNAUTHORIZED");
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
}
