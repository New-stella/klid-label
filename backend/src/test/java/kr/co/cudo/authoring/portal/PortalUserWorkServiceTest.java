package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.portal.dto.PortalUserWorkResponse;
import kr.co.cudo.authoring.portal.dto.PortalWorkAssetSource;
import kr.co.cudo.authoring.portal.repository.PortalUserWorkListRepository;
import kr.co.cudo.authoring.portal.repository.PortalUserWorkListRepository.UserWorkRow;
import kr.co.cudo.authoring.portal.repository.PortalUserWorkRepository;
import kr.co.cudo.authoring.portal.service.PortalRetentionPolicy;
import kr.co.cudo.authoring.portal.service.PortalUserWorkService;
import kr.co.cudo.authoring.portal.service.PortalWorkTargetResolver;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「내 작업」 목록의 <b>행마다 다른 만료 규칙</b>을 결정적으로 고정한다. @design API-225, DFEAT-055
 *
 * <p>이 시험이 겨누는 결함은 「한 목록이니 한 규칙으로 계산하자」다 — 두 축은 기산점이 다르고,
 * 통일하면 한쪽이 반드시 틀린다. 실 DB 없이도 갈리는 지점이라 여기서 잡는다.
 */
@SuppressWarnings("unchecked")
class PortalUserWorkServiceTest {

    private static final String ALICE = "alice";
    private static final Pageable PAGE = PageRequest.of(0, 20);

    private PortalUserWorkListRepository workListRepository;
    private PortalUserWorkRepository userWorkRepository;
    private PortalUploadAssetRepository assetRepository;
    private SystemConfigService systemConfigService;
    private PortalWorkTargetResolver targetResolver;
    private PortalUserWorkService service;

    @BeforeEach
    void setUp() {
        workListRepository = mock(PortalUserWorkListRepository.class);
        userWorkRepository = mock(PortalUserWorkRepository.class);
        assetRepository = mock(PortalUploadAssetRepository.class);
        systemConfigService = mock(SystemConfigService.class);
        targetResolver = mock(PortalWorkTargetResolver.class);
        service = new PortalUserWorkService(workListRepository, userWorkRepository, assetRepository,
                new PortalRetentionPolicy(systemConfigService), targetResolver);

        // 기본 스텁을 <먼저> 깐다 — 반환값을 판정에 쓰기 시작하면 mock 기본값(빈 집합/빈 맵)이 정상
        // 경로 시험 전량을 「진입 불가」·「만료 없음」으로 뒤집는다. 겨누는 시험만 개별로 덮어쓴다.
        when(targetResolver.workableVideos(any())).thenAnswer(inv -> {
            Collection<?> ids = inv.getArgument(0);
            return ids == null ? Set.of() : new LinkedHashSet<>((Collection<Long>) ids);
        });

        when(systemConfigService.getInt(ConfigKeys.PORTAL_DATAMART_RETENTION_DAYS)).thenReturn(7);
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_RETENTION_DAYS)).thenReturn(7);
        when(systemConfigService.getInt(ConfigKeys.PORTAL_UPLOAD_FAILED_RETENTION_DAYS)).thenReturn(1);
    }

    // ==================================================== 픽스처

    private void givenRows(UserWorkRow... rows) {
        when(workListRepository.findPage(anyString(), any()))
                .thenReturn(new PageImpl<>(List.of(rows), PAGE, rows.length));
    }

    private static UserWorkRow datamartRow(long rawSn, LocalDateTime lastSavedAt, int labelCount) {
        return new UserWorkRow(rawSn, PortalWorkAssetSource.DATAMART, lastSavedAt, labelCount,
                "CLIP-" + rawSn, 900L + rawSn);
    }

    private static UserWorkRow uploadRow(long rawSn, LocalDateTime lastSavedAt, int labelCount) {
        return new UserWorkRow(rawSn, PortalWorkAssetSource.PORTAL_UPLOAD, lastSavedAt, labelCount,
                "PORTAL_ULD_" + rawSn, 700L + rawSn);
    }

    private static PortalUploadAsset asset(long rawSn, String status, LocalDateTime regDt,
                                           LocalDateTime sttsChgDt, String orgnlFileNm) {
        return new PortalUploadAsset(rawSn, ALICE, PortalUploadLedger.TYPE_VIDEO, orgnlFileNm,
                "/portal/" + rawSn + ".mp4", 10L, "video/mp4", status, 30.0d, 30.0d, 3, null,
                regDt, sttsChgDt);
    }

    private void givenAssets(PortalUploadAsset... assets) {
        when(assetRepository.findByOwnerIn(anyString(), any())).thenReturn(
                Arrays.stream(assets).collect(Collectors.toMap(PortalUploadAsset::uldSn, a -> a)));
        when(assetRepository.findLastLabelSavedAt(anyString(), any())).thenReturn(Map.of());
    }

    /** 보존기간(READY 축) 기준점 한쪽 — <b>목록의 마지막 저장 시각과 다른 축</b>이다. */
    private void givenLastLabelSavedAt(Map<Long, LocalDateTime> map) {
        when(assetRepository.findLastLabelSavedAt(anyString(), any())).thenReturn(map);
    }

    private void givenFirstAuthoredAt(Map<Long, LocalDateTime> map) {
        when(userWorkRepository.findEarliestAuthoredAtByVideo(anyString(), any())).thenReturn(map);
    }

    // ==================================================== 두 규칙이 한 목록에 섞인다

    /**
     * ★★ 이 기능의 핵심 — 한 목록의 두 행이 <b>서로 다른 규칙</b>으로 계산된 만료를 보여준다.
     *
     * <p>데이터마트 행은 저작 <b>최초</b> 저장(30일 전) + 7일 = 23일 전이고, 업로드 행은 등록일과
     * 라벨 마지막 저장일 중 <b>늦은 쪽</b>(1일 전) + 7일 = 6일 뒤다. 한 규칙으로 통일하면 둘 중
     * 하나는 반드시 틀린다.
     */
    @Test
    @DisplayName("★★한_목록에_데이터마트_규칙과_업로드_규칙이_섞여_계산된다")
    void twoRetentionRulesCoexistInOneList() {
        LocalDateTime now = LocalDateTime.now();
        givenRows(datamartRow(1L, now.minusDays(1), 3), uploadRow(2L, now.minusDays(1), 5));
        givenFirstAuthoredAt(Map.of(1L, now.minusDays(30)));
        givenAssets(asset(2L, PortalUploadLedger.STATUS_READY, now.minusDays(20), now.minusDays(20), "v.mp4"));
        givenLastLabelSavedAt(Map.of(2L, now.minusDays(1)));

        List<PortalUserWorkResponse> content = service.listUserWorks(ALICE, PAGE).getContent();

        assertThat(content.get(0).expiresOn())
                .as("데이터마트 — 최초 저장(30일 전) 기산")
                .isEqualTo(now.minusDays(30).plusDays(7).toLocalDate());
        assertThat(content.get(1).expiresOn())
                .as("업로드 — 등록일과 라벨 마지막 저장일 중 늦은 쪽(1일 전) 기산")
                .isEqualTo(now.minusDays(1).plusDays(7).toLocalDate());
    }

    /**
     * ★ 데이터마트 행의 만료를 <b>마지막 저장</b>에서 계산하면 저장할 때마다 만료가 뒤로 밀려
     * 자동 삭제가 영영 오지 않는다(2026-09-04 에 고친 결함). 두 시각을 크게 벌려 축을 가른다.
     */
    @Test
    @DisplayName("★데이터마트_만료는_마지막_저장이_아니라_최초_저장에서_계산된다")
    void datamartExpiryUsesFirstSaveNotLastSave() {
        LocalDateTime now = LocalDateTime.now();
        givenRows(datamartRow(1L, now, 3));
        givenFirstAuthoredAt(Map.of(1L, now.minusDays(30)));

        PortalUserWorkResponse row = service.listUserWorks(ALICE, PAGE).getContent().get(0);

        assertThat(row.expiresOn()).isEqualTo(now.minusDays(30).plusDays(7).toLocalDate());
        assertThat(row.expiresOn())
                .as("마지막 저장에서 계산했다면 미래가 된다 — 그러면 만료가 영영 오지 않는다")
                .isNotEqualTo(now.plusDays(7).toLocalDate());
    }

    /** 저장을 반복해도(마지막 저장만 미래로 움직여도) 데이터마트 행의 만료는 그대로다. */
    @Test
    @DisplayName("★저장을_반복해도_데이터마트_행의_만료는_뒤로_밀리지_않는다")
    void repeatedSavesDoNotPushDatamartExpiry() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime firstSave = now.minusDays(5);

        givenRows(datamartRow(1L, now.minusDays(4), 1));
        givenFirstAuthoredAt(Map.of(1L, firstSave));
        var before = service.listUserWorks(ALICE, PAGE).getContent().get(0).expiresOn();

        givenRows(datamartRow(1L, now, 9));
        givenFirstAuthoredAt(Map.of(1L, firstSave));
        var after = service.listUserWorks(ALICE, PAGE).getContent().get(0).expiresOn();

        assertThat(after).isEqualTo(before).isEqualTo(firstSave.plusDays(7).toLocalDate());
    }

    // ==================================================== 업로드 축 상태별 규칙

    @Test
    @DisplayName("업로드_실패_자산은_실패_전이_시각_기준_짧은_보존기간으로_계산된다")
    void failedAssetUsesFailedRetention() {
        LocalDateTime now = LocalDateTime.now();
        givenRows(uploadRow(2L, null, 0));
        givenAssets(asset(2L, PortalUploadLedger.STATUS_FAILED, now.minusDays(3), now.minusDays(2), "v.mp4"));

        assertThat(service.listUserWorks(ALICE, PAGE).getContent().get(0).expiresOn())
                .isEqualTo(now.minusDays(2).plusDays(1).toLocalDate());
    }

    @Test
    @DisplayName("마킹_대기_자산은_등록일_기준으로_계산된다")
    void uploadedAssetUsesRegDt() {
        LocalDateTime now = LocalDateTime.now();
        givenRows(uploadRow(2L, null, 0));
        givenAssets(asset(2L, PortalUploadLedger.STATUS_UPLOADED, now.minusDays(3), now.minusDays(3), "v.mp4"));

        assertThat(service.listUserWorks(ALICE, PAGE).getContent().get(0).expiresOn())
                .isEqualTo(now.minusDays(3).plusDays(7).toLocalDate());
    }

    @Test
    @DisplayName("후처리_중_자산은_고지할_만료가_없다")
    void processingAssetHasNoExpiry() {
        LocalDateTime now = LocalDateTime.now();
        givenRows(uploadRow(2L, null, 0));
        givenAssets(asset(2L, PortalUploadLedger.STATUS_PROCESSING, now.minusDays(3), now.minusDays(1), "v.mp4"));

        assertThat(service.listUserWorks(ALICE, PAGE).getContent().get(0).expiresOn()).isNull();
    }

    /** 보존기간 설정이 없으면 목록이 500 으로 깨지지 않고 그 칸만 비운다(fail-closed). */
    @Test
    @DisplayName("보존기간_설정이_없으면_만료만_비고_목록은_그대로_내려간다")
    void missingRetentionConfigOnlyBlanksExpiry() {
        LocalDateTime now = LocalDateTime.now();
        when(systemConfigService.getInt(anyString())).thenThrow(new IllegalStateException("설정 없음"));
        givenRows(datamartRow(1L, now, 2), uploadRow(2L, now, 1));
        givenFirstAuthoredAt(Map.of(1L, now.minusDays(30)));
        givenAssets(asset(2L, PortalUploadLedger.STATUS_READY, now.minusDays(20), now.minusDays(20), "v.mp4"));

        List<PortalUserWorkResponse> content = service.listUserWorks(ALICE, PAGE).getContent();

        assertThat(content).hasSize(2).allSatisfy(r -> assertThat(r.expiresOn()).isNull());
    }

    /**
     * ★★ 표시축과 보존 입력축이 <b>다른 값</b>이라는 것을 고정한다.
     *
     * <p>라벨 없이 메타만 고친 업로드 자산에서 갈린다 — 목록의 마지막 저장 시각은 그 메타 편집 시각
     * 이지만, 보존 기준점은 <b>라벨 마지막 저장일</b>이라 값이 없어 등록일로 떨어진다. 목록 값을
     * 보존 입력으로 넘기면 기산점이 뒤로 밀려 <b>비가역 삭제 시점이 늦어진다</b>.
     */
    @Test
    @DisplayName("★★목록의_마지막_저장_시각을_보존_기준점으로_넘기지_않는다_두_축은_다른_값이다")
    void listLastSavedAtIsNotFedIntoRetention() {
        LocalDateTime now = LocalDateTime.now();
        // 목록은 메타 편집(1일 전)을 세지만 저장 라벨은 한 건도 없다.
        givenRows(uploadRow(2L, now.minusDays(1), 0));
        givenAssets(asset(2L, PortalUploadLedger.STATUS_READY, now.minusDays(20), now.minusDays(20), "v.mp4"));
        givenLastLabelSavedAt(Map.of());   // 보존 입력축 — 라벨이 없으니 키 자체가 없다

        PortalUserWorkResponse row = service.listUserWorks(ALICE, PAGE).getContent().get(0);

        assertThat(row.lastSavedAt()).as("표시축은 메타 편집을 센다").isEqualTo(now.minusDays(1));
        assertThat(row.expiresOn())
                .as("보존축은 라벨만 보므로 등록일(20일 전) 기산 — 목록 값을 넘겼다면 1일 전 기산이 된다")
                .isEqualTo(now.minusDays(20).plusDays(7).toLocalDate());
    }

    /** 보존 입력은 그 축의 소유자에게서 받는다 — 목록이 자기 값을 대신 넘기지 않는다. */
    @Test
    @DisplayName("업로드_만료_계산은_라벨_마지막_저장일_소유자에게_묻는다")
    void uploadExpiryAsksTheOwnerOfTheLabelAxis() {
        LocalDateTime now = LocalDateTime.now();
        givenRows(uploadRow(2L, now.minusDays(1), 3));
        givenAssets(asset(2L, PortalUploadLedger.STATUS_READY, now.minusDays(20), now.minusDays(20), "v.mp4"));
        givenLastLabelSavedAt(Map.of(2L, now.minusDays(4)));

        assertThat(service.listUserWorks(ALICE, PAGE).getContent().get(0).expiresOn())
                .isEqualTo(now.minusDays(4).plusDays(7).toLocalDate());
        verify(assetRepository).findLastLabelSavedAt(anyString(), any());
    }

    // ==================================================== 진입 대상 프레임

    /**
     * ★★ 과다 노출 축 — 들어갈 수 없는 행에 프레임이 실리면 화면이 <b>활성 링크를 그리고</b>
     * 눌러야 거부된다. 응답이 미리 막을 입력을 줘야 한다.
     */
    @Test
    @DisplayName("★★진입이_허용되지_않는_데이터마트_행은_진입_대상이_빈다")
    void notEnterableDatamartRowHasNoEntryFrame() {
        LocalDateTime now = LocalDateTime.now();
        givenRows(datamartRow(1L, now, 2));
        givenFirstAuthoredAt(Map.of(1L, now.minusDays(1)));
        when(targetResolver.workableVideos(any())).thenReturn(Set.of());   // 노출 조건 불만족

        PortalUserWorkResponse row = service.listUserWorks(ALICE, PAGE).getContent().get(0);

        assertThat(row.entrySrcSn()).isNull();
    }

    /** ★ 그 행을 목록에서 빼지는 않는다 — 빼면 삭제 예고가 함께 사라진다. */
    @Test
    @DisplayName("★진입_불가_행도_목록에_남고_만료가_실린다")
    void notEnterableRowStaysListedWithExpiry() {
        LocalDateTime now = LocalDateTime.now();
        givenRows(datamartRow(1L, now, 2));
        givenFirstAuthoredAt(Map.of(1L, now.minusDays(30)));
        when(targetResolver.workableVideos(any())).thenReturn(Set.of());

        PortalUserWorkResponse row = service.listUserWorks(ALICE, PAGE).getContent().get(0);

        assertThat(row.rawSn()).isEqualTo(1L);
        assertThat(row.expiresOn()).isEqualTo(now.minusDays(30).plusDays(7).toLocalDate());
    }

    /** 업로드 행은 소유자만 보는 축이라 노출 판정과 무관하게 진입 대상이 실린다. */
    @Test
    @DisplayName("업로드_행의_진입_대상은_데이터마트_노출_판정에_영향받지_않는다")
    void uploadEntryFrameIsUnaffectedByDatamartExposure() {
        LocalDateTime now = LocalDateTime.now();
        givenRows(uploadRow(2L, null, 0));
        givenAssets(asset(2L, PortalUploadLedger.STATUS_UPLOADED, now, now, "v.mp4"));
        when(targetResolver.workableVideos(any())).thenReturn(Set.of());

        assertThat(service.listUserWorks(ALICE, PAGE).getContent().get(0).entrySrcSn()).isEqualTo(702L);
    }

    // ==================================================== 응답 필드

    /** 저작물 0건 업로드 행도 실린다 — 라벨 건수 0 · 마지막 저장 없음이 <b>정상</b>이다. */
    @Test
    @DisplayName("★저작물이_하나도_없는_업로드_행도_실리고_라벨_건수는_0이다")
    void uploadWithoutAnyWorkIsStillListed() {
        LocalDateTime now = LocalDateTime.now();
        givenRows(uploadRow(2L, null, 0));
        givenAssets(asset(2L, PortalUploadLedger.STATUS_UPLOADED, now.minusDays(1), now.minusDays(1), "내영상.mp4"));

        PortalUserWorkResponse row = service.listUserWorks(ALICE, PAGE).getContent().get(0);

        assertThat(row.labelCount()).isZero();
        assertThat(row.lastSavedAt()).isNull();
        assertThat(row.assetSource()).isEqualTo(PortalWorkAssetSource.PORTAL_UPLOAD);
        assertThat(row.videoName()).as("표시 이름은 사용자가 올린 원본 파일명이 1순위").isEqualTo("내영상.mp4");
    }

    @Test
    @DisplayName("업로드_행의_원본_파일명이_없으면_클립_식별자를_그대로_남긴다")
    void uploadNameFallsBackToClipId() {
        LocalDateTime now = LocalDateTime.now();
        givenRows(uploadRow(2L, null, 0));
        givenAssets(asset(2L, PortalUploadLedger.STATUS_UPLOADED, now, now, null));

        assertThat(service.listUserWorks(ALICE, PAGE).getContent().get(0).videoName())
                .isEqualTo("PORTAL_ULD_2");
    }

    @Test
    @DisplayName("빈_페이지에는_집계_조회를_태우지_않는다")
    void emptyPageSkipsLookups() {
        when(workListRepository.findPage(anyString(), any()))
                .thenReturn(new PageImpl<>(List.of(), PAGE, 0));

        Page<PortalUserWorkResponse> page = service.listUserWorks(ALICE, PAGE);

        assertThat(page.getContent()).isEmpty();
        verify(userWorkRepository, never()).findEarliestAuthoredAtByVideo(anyString(), any());
        verify(assetRepository, never()).findByOwnerIn(anyString(), any());
    }

    /** 축이 한쪽뿐이면 반대 축의 일괄 조회를 부르지 않는다(빈 IN 절·불필요 왕복 회피). */
    @Test
    @DisplayName("데이터마트_행만_있으면_업로드_자산_조회를_부르지_않는다")
    void datamartOnlyPageSkipsAssetLookup() {
        givenRows(datamartRow(1L, LocalDateTime.now(), 1));
        givenFirstAuthoredAt(Map.of(1L, LocalDateTime.now().minusDays(1)));

        service.listUserWorks(ALICE, PAGE);

        verify(assetRepository, never()).findByOwnerIn(anyString(), any());
    }

    @Test
    @DisplayName("토큰_주체가_없으면_401")
    void missingOwnerIsUnauthorized() {
        assertThatThrownBy(() -> service.listUserWorks(" ", PAGE))
                .isInstanceOf(CustomException.class);
    }

    // ==================================================== 어휘 일치

    /**
     * ★ 자산 출처 어휘가 판정 지점과 갈리면 화면이 「저장은 어디로 갔는가」와 「이 행은 어느 축인가」를
     * 서로 다른 낱말로 받는다. 어느 한쪽에 값을 더하면 이 시험이 죽는다.
     */
    @Test
    @DisplayName("★자산_출처_어휘는_판정_지점의_값_집합과_한_글자도_다르지_않다")
    void assetSourceVocabularyMatchesResolverOrigin() {
        assertThat(Arrays.stream(PortalWorkAssetSource.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(PortalWorkTargetResolver.Origin.values()).map(Enum::name).toList());
    }
}
