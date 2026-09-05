package kr.co.cudo.authoring.portal;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.dataset.export.ExportPrivacyPolicy;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.portal.dto.PortalMetaResponse;
import kr.co.cudo.authoring.portal.dto.PortalMetaScope;
import kr.co.cudo.authoring.portal.dto.PortalMetaSource;
import kr.co.cudo.authoring.portal.dto.PortalMetaUpdateRequest;
import kr.co.cudo.authoring.portal.entity.LsPortalUserMeta;
import kr.co.cudo.authoring.portal.repository.LsPortalUserMetaRepository;
import kr.co.cudo.authoring.portal.service.PortalColumnMetaField;
import kr.co.cudo.authoring.portal.service.PortalWorkMetaService;
import kr.co.cudo.authoring.portal.service.PortalWorkTargetResolver;
import kr.co.cudo.authoring.portal.service.PortalWorkTargetResolver.Origin;
import kr.co.cudo.authoring.portal.service.PortalWorkTargetResolver.Target;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.transfer.ImportMetaKeys;
import kr.co.cudo.authoring.webhook.service.VlmResultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

/**
 * 포털 작업 화면 메타 Load·저장 — <b>자산 출처가 저장처를 가른다</b>는 축을 중심으로 고정한다.
 *
 * @design API-234, API-235, ERD-018, UC-024, AC-1068
 */
class PortalWorkMetaServiceTest {

    private static final String ALICE = "alice";
    private static final long RAW_SN = 10L;
    private static final long SRC_SN = 100L;

    private PortalWorkTargetResolver targetResolver;
    private LsDataMetaRepository metaRepository;
    private LsPortalUserMetaRepository overlayRepository;
    private PortalUploadAssetRepository assetRepository;
    private VideoRepository videoRepository;
    private LsDataSrcRepository srcRepository;
    private LabelAccessGuard accessGuard;
    private PortalWorkMetaService service;

    private LsDataRaw raw;
    private LsDataSrc frame;

    @BeforeEach
    void setUp() {
        targetResolver = mock(PortalWorkTargetResolver.class);
        metaRepository = mock(LsDataMetaRepository.class);
        overlayRepository = mock(LsPortalUserMetaRepository.class);
        assetRepository = mock(PortalUploadAssetRepository.class);
        videoRepository = mock(VideoRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        accessGuard = mock(LabelAccessGuard.class);
        service = new PortalWorkMetaService(targetResolver, metaRepository, overlayRepository,
                assetRepository, videoRepository, srcRepository, accessGuard);
        // 원장 저장 창구는 「반영된 행 수」를 돌려준다 — 0 은 <판별자가 막았다>는 뜻이라 서비스가
        //   거부한다. 정상 경로 시험은 1 을 기본값으로 둔다(0 을 겨누는 시험만 따로 덮어쓴다).
        when(assetRepository.upsertOwnedMeta(anyLong(), anyString(), anyString(), any())).thenReturn(1);
        when(assetRepository.updateOwnedVideoColumn(anyLong(), anyString(), anyString(), any()))
                .thenReturn(1);
        when(assetRepository.updateOwnedFrameColumn(anyLong(), anyString(), anyString(), any()))
                .thenReturn(1);

        raw = newInstance(LsDataRaw.class);
        set(raw, "rawSn", RAW_SN);
        frame = newInstance(LsDataSrc.class);
        set(frame, "srcSn", SRC_SN);
        set(frame, "rawSn", RAW_SN);
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw));
        when(srcRepository.findById(SRC_SN)).thenReturn(Optional.of(frame));
    }

    // ---------------- fixtures ----------------

    private void givenOrigin(Origin origin) {
        when(targetResolver.resolveByFrame(SRC_SN, ALICE))
                .thenReturn(new Target(RAW_SN, SRC_SN, origin));
    }

    /**
     * 컬럼 축(촬영환경·프레임 설명·개인정보 판정)을 걷어낸 <b>키/값 축만</b>.
     *
     * <p>컬럼 축은 원장 행이 있는 한 <b>항상</b> 목록에 담기므로(값이 없으면 없는 값으로), 키/값 축의
     * 병합을 보는 단언은 그 축만 떼어 본다 — 두 축을 섞으면 무엇이 깨졌는지 알 수 없다.
     */
    private static List<PortalMetaResponse.Item> keyValueItems(PortalMetaResponse response) {
        return response.items().stream()
                .filter(i -> !PortalColumnMetaField.isColumnKey(i.metaKey()))
                .toList();
    }

    private static PortalMetaResponse.Item pick(PortalMetaResponse response,
                                                PortalMetaScope scope, String metaKey) {
        return response.items().stream()
                .filter(i -> i.scope() == scope && i.metaKey().equals(metaKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("항목이 없다: " + scope + " " + metaKey));
    }

    private static LsDataMeta ledgerMeta(String key, String value) {
        LsDataMeta meta = newInstance(LsDataMeta.class);
        set(meta, "rawSn", RAW_SN);
        set(meta, "metaKey", key);
        set(meta, "metaVl", value);
        return meta;
    }

    private static LsPortalUserMeta overlayMeta(String key, String value, Long srcDataSrcSn) {
        LsPortalUserMeta mine = newInstance(LsPortalUserMeta.class);
        set(mine, "portalUserNo", ALICE);
        set(mine, "srcRawSn", RAW_SN);
        set(mine, "srcDataSrcSn", srcDataSrcSn);
        set(mine, "metaKey", key);
        set(mine, "metaVl", value);
        return mine;
    }

    private static PortalMetaUpdateRequest request(PortalMetaUpdateRequest.Item... items) {
        return new PortalMetaUpdateRequest(List.of(items));
    }

    private static PortalMetaUpdateRequest.Item item(String key, String value, PortalMetaScope scope) {
        return new PortalMetaUpdateRequest.Item(key, value, scope);
    }

    // ================================================================ Load — 병합

    @Test
    @DisplayName("데이터마트_자산은_원본과_본인_오버레이를_병합하고_가린_값에_표시가_선다")
    void datamartMergesOverlayAndMarksOverridden() {
        givenOrigin(Origin.DATAMART);
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(
                ledgerMeta(VlmResultService.META_KEY_DESCRIPTION, "원본 서술"),
                ledgerMeta("manual-timeseries", "원본 수동")));
        when(overlayRepository.findByPortalUserNoAndSrcRawSnAndSrcDataSrcSnIsNull(ALICE, RAW_SN))
                .thenReturn(List.of(
                        overlayMeta(VlmResultService.META_KEY_DESCRIPTION, "내가 고친 서술", null),
                        overlayMeta("my-new-key", "내가 추가", null)));
        when(overlayRepository.findByPortalUserNoAndSrcDataSrcSn(ALICE, SRC_SN)).thenReturn(List.of());

        PortalMetaResponse response = service.load(SRC_SN, ALICE);

        assertThat(keyValueItems(response))
                .extracting(PortalMetaResponse.Item::metaKey, PortalMetaResponse.Item::metaVl,
                        PortalMetaResponse.Item::overridden)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(VlmResultService.META_KEY_DESCRIPTION, "내가 고친 서술", true),
                        org.assertj.core.groups.Tuple.tuple("manual-timeseries", "원본 수동", false),
                        org.assertj.core.groups.Tuple.tuple("my-new-key", "내가 추가", false));
    }

    @Test
    @DisplayName("원본에_없던_키를_새로_추가한_값에는_가림_표시가_서지_않는다")
    void addedKeyIsNotMarkedOverridden() {
        givenOrigin(Origin.DATAMART);
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of());
        when(overlayRepository.findByPortalUserNoAndSrcRawSnAndSrcDataSrcSnIsNull(ALICE, RAW_SN))
                .thenReturn(List.of(overlayMeta("added", "값", null)));
        when(overlayRepository.findByPortalUserNoAndSrcDataSrcSn(ALICE, SRC_SN)).thenReturn(List.of());

        assertThat(keyValueItems(service.load(SRC_SN, ALICE)))
                .singleElement()
                .extracting(PortalMetaResponse.Item::overridden)
                .isEqualTo(false);
    }

    @Test
    @DisplayName("프레임_축_오버레이는_프레임_축으로_내려간다")
    void frameScopedOverlayKeepsFrameScope() {
        givenOrigin(Origin.DATAMART);
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of());
        when(overlayRepository.findByPortalUserNoAndSrcRawSnAndSrcDataSrcSnIsNull(ALICE, RAW_SN))
                .thenReturn(List.of());
        when(overlayRepository.findByPortalUserNoAndSrcDataSrcSn(ALICE, SRC_SN))
                .thenReturn(List.of(overlayMeta("frame-note", "프레임 메모", SRC_SN)));

        assertThat(keyValueItems(service.load(SRC_SN, ALICE)))
                .singleElement()
                .extracting(PortalMetaResponse.Item::scope)
                .isEqualTo(PortalMetaScope.FRAME);
    }

    @Test
    @DisplayName("같은_키라도_영상_축과_프레임_축은_서로_다른_값으로_공존한다")
    void sameKeyOnBothAxesCoexists() {
        givenOrigin(Origin.DATAMART);
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(ledgerMeta("note", "영상 값")));
        when(overlayRepository.findByPortalUserNoAndSrcRawSnAndSrcDataSrcSnIsNull(ALICE, RAW_SN))
                .thenReturn(List.of());
        when(overlayRepository.findByPortalUserNoAndSrcDataSrcSn(ALICE, SRC_SN))
                .thenReturn(List.of(overlayMeta("note", "프레임 값", SRC_SN)));

        assertThat(keyValueItems(service.load(SRC_SN, ALICE))).hasSize(2);
    }

    /**
     * ★★ 이 시험이 막는 변이 — <b>본인 업로드 자산을 오버레이 경로로 읽게</b> 만들면 죽는다.
     * 오버레이 저장소를 조회 필터로 거르는 것이 아니라 <b>경로 자체가 갈린다</b>.
     */
    @Test
    @DisplayName("★본인_업로드_자산은_오버레이_저장소를_아예_건드리지_않는다")
    void uploadOriginNeverTouchesOverlayOnRead() {
        givenOrigin(Origin.PORTAL_UPLOAD);
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(ledgerMeta("note", "내 값")));

        PortalMetaResponse response = service.load(SRC_SN, ALICE);

        verifyNoInteractions(overlayRepository);
        assertThat(keyValueItems(response))
                .singleElement()
                .extracting(PortalMetaResponse.Item::overridden)
                .as("가려야 할 남의 원본이 없으므로 가림 표시가 서지 않는다")
                .isEqualTo(false);
    }

    @Test
    @DisplayName("표시_전용과_기술_메타는_별도_목록으로_갈라진다")
    void bucketsAreSeparated() {
        givenOrigin(Origin.PORTAL_UPLOAD);
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(
                ledgerMeta(VlmResultService.META_KEY_DESCRIPTION, "서술"),
                ledgerMeta("video.fps", "30"),
                ledgerMeta("vlm.accuracy", "0.9"),
                ledgerMeta(ImportMetaKeys.VIDEO_LOCATION, "어딘가"),
                ledgerMeta("portal.upload_status", "READY")));

        PortalMetaResponse response = service.load(SRC_SN, ALICE);

        assertThat(keyValueItems(response)).extracting(PortalMetaResponse.Item::metaKey)
                .containsExactly(VlmResultService.META_KEY_DESCRIPTION);
        assertThat(response.technicalMeta()).extracting(PortalMetaResponse.Item::metaKey)
                .containsExactly("video.fps");
        assertThat(response.readOnlyMeta()).extracting(PortalMetaResponse.Item::metaKey)
                .as("자동 산출·이관 원문·포털 파이프라인 내부 키가 모두 표시 전용이다")
                .containsExactlyInAnyOrder("vlm.accuracy", ImportMetaKeys.VIDEO_LOCATION, "portal.upload_status");
    }

    // ================================================================ 저장 — 저장처 갈림

    /**
     * ★★ 이 기능에서 가장 틀리기 쉬운 지점. 전부 오버레이에 넣는 구현은 컴파일도 되고 대부분의
     * 시험도 통과하지만, 본인 업로드 자산의 메타가 원장에 남지 않아 원장을 읽는 이후 경로가
     * 그것을 못 본다.
     */
    @Test
    @DisplayName("★본인_업로드_자산의_메타는_오버레이가_아니라_그_자산의_원장에_적재된다")
    void uploadOriginWritesToLedgerNotOverlay() {
        givenOrigin(Origin.PORTAL_UPLOAD);
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of());

        service.save(SRC_SN, ALICE, request(item("note", "값", PortalMetaScope.VIDEO)));

        verify(assetRepository).upsertOwnedMeta(RAW_SN, ALICE, "note", "값");
        verify(overlayRepository, never()).upsertVideoScoped(anyString(), anyLong(), anyString(), anyString());
        verify(overlayRepository, never()).upsertFrameScoped(anyString(), anyLong(), anyLong(), anyString(), anyString());
    }

    /**
     * ★★ 반대 방향 변이 — 데이터마트 자산을 원장에 쓰게 만들면 죽는다. 그것이 곧
     * <b>원본·동결 스냅샷 수정</b>이며 단방향 불변식이 깨지는 지점이다.
     */
    @Test
    @DisplayName("★데이터마트_자산의_메타는_원본_원장에_쓰지_않고_오버레이에만_적재된다")
    void datamartOriginNeverWritesToLedger() {
        givenOrigin(Origin.DATAMART);
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of());
        when(overlayRepository.findByPortalUserNoAndSrcRawSnAndSrcDataSrcSnIsNull(ALICE, RAW_SN))
                .thenReturn(List.of());
        when(overlayRepository.findByPortalUserNoAndSrcDataSrcSn(ALICE, SRC_SN)).thenReturn(List.of());

        service.save(SRC_SN, ALICE, request(item("note", "값", PortalMetaScope.VIDEO)));

        verify(overlayRepository).upsertVideoScoped(ALICE, RAW_SN, "note", "값");
        verify(assetRepository, never()).upsertOwnedMeta(anyLong(), anyString(), anyString(), anyString());
        verify(metaRepository, never()).upsertMeta(anyLong(), anyString(), anyString());
    }

    /**
     * ★ 영상 축 원소를 프레임 참조와 함께 적재하면 <b>없는 프레임을 가리키는 행</b>이 된다.
     * 오류가 나지 않으므로 이 단언 말고는 아무도 잡지 못한다.
     */
    @Test
    @DisplayName("★영상_축_원소는_프레임_참조_없이_적재되고_프레임_축만_프레임에_매달린다")
    void videoScopedItemIsStoredWithoutFrameReference() {
        givenOrigin(Origin.DATAMART);
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of());
        when(overlayRepository.findByPortalUserNoAndSrcRawSnAndSrcDataSrcSnIsNull(ALICE, RAW_SN))
                .thenReturn(List.of());
        when(overlayRepository.findByPortalUserNoAndSrcDataSrcSn(ALICE, SRC_SN)).thenReturn(List.of());

        service.save(SRC_SN, ALICE, request(
                item("video-note", "영상 값", PortalMetaScope.VIDEO),
                item("frame-note", "프레임 값", PortalMetaScope.FRAME)));

        verify(overlayRepository).upsertVideoScoped(ALICE, RAW_SN, "video-note", "영상 값");
        verify(overlayRepository).upsertFrameScoped(ALICE, RAW_SN, SRC_SN, "frame-note", "프레임 값");
        verify(overlayRepository, never()).upsertFrameScoped(ALICE, RAW_SN, SRC_SN, "video-note", "영상 값");
    }

    // ================================================================ 거부

    @Test
    @DisplayName("표시_전용_키_저장_요청은_400이고_한_건이라도_걸리면_아무것도_저장되지_않는다")
    void uneditableKeyRejectsWholeRequest() {
        givenOrigin(Origin.DATAMART);

        assertThatThrownBy(() -> service.save(SRC_SN, ALICE, request(
                item("ok-key", "값", PortalMetaScope.VIDEO),
                item("video.fps", "999", PortalMetaScope.VIDEO))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

        verify(overlayRepository, never()).upsertVideoScoped(anyString(), anyLong(), anyString(), anyString());
    }

    /**
     * ★ 업로드 처리 상태를 고칠 수 있으면 사용자가 자산 상태를 임의로 완료로 바꿔 상태 기계를
     * 통째로 우회한다 — 오류 없이 통과하는 fail-open 이다.
     */
    @Test
    @DisplayName("★포털_파이프라인_내부키_저장_요청은_400이다_상태_우회_차단")
    void portalPipelineKeySaveIsRejected() {
        givenOrigin(Origin.PORTAL_UPLOAD);

        assertThatThrownBy(() -> service.save(SRC_SN, ALICE,
                request(item("portal.upload_status", "READY", PortalMetaScope.VIDEO))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

        verify(assetRepository, never()).upsertOwnedMeta(anyLong(), anyString(), anyString(), anyString());
    }

    /**
     * ★ 전역 예외 처리기가 메시지를 그대로 로깅하므로 개행이 섞인 키를 echo 하면 로그 위조가 된다
     * (CWE-117/209).
     */
    @Test
    @DisplayName("★거부_메시지에_요청받은_키를_되돌려_싣지_않는다")
    void rejectionMessageDoesNotEchoRequestedKey() {
        givenOrigin(Origin.DATAMART);
        String hostileKey = "video.fps\n2026-01-01 INFO 위조된 로그 줄";

        assertThatThrownBy(() -> service.save(SRC_SN, ALICE,
                request(item(hostileKey, "값", PortalMetaScope.VIDEO))))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("위조된 로그 줄"));
    }

    @Test
    @DisplayName("업로드_자산에는_프레임_축_저장을_받지_않는다_조용히_접거나_버리지_않는다")
    void frameScopeOnUploadIsRejected() {
        givenOrigin(Origin.PORTAL_UPLOAD);

        assertThatThrownBy(() -> service.save(SRC_SN, ALICE,
                request(item("frame-note", "값", PortalMetaScope.FRAME))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

        verify(assetRepository, never()).upsertOwnedMeta(anyLong(), anyString(), anyString(), anyString());
    }

    /**
     * ★ 이 서비스가 <b>선언한 필드의 타입</b>에 금지 타입이 없다는 것을 본다.
     *
     * <h4>무엇을 막나</h4>
     * <ul>
     *   <li><b>이벤트 발행자</b> — 내부 메타 창구는 검수 승인 영상에서 {@code TaskModifiedEvent} 를
     *       내지만 포털 저장은 확정 학습데이터를 건드리지 않으므로 그 축에 참여하지 않는다.</li>
     *   <li><b>원장 저장 창구 넷</b>({@code EnvironmentMetaService}·{@code VideoPrivacyMetaService}·
     *       {@code FramePrivacyMetaService}·{@code FrameDescriptionService}) — 저장하면서 원장 컬럼을
     *       고치고, 재검토 표시를 세우고, 관제 통지를 발행하고, 동결본을 다시 굳힌다. 「중복 구현을
     *       피하자」는 가장 자연스러운 판단이 이 창구를 부르게 만들고, 그 순간 최상위 불변 둘이
     *       한 번에 깨진다.</li>
     * </ul>
     *
     * <h4>⚠ 이 단언이 <b>보지 못하는 것</b></h4>
     * <p>판정 축이 <b>선언 필드의 타입 이름</b>이므로, 금지 대상을 <b>다른 서비스에 주입해 그 서비스를
     * 통해 부르는 간접 경로</b>는 보이지 않는다. 그 축은 실 DB 시험(원장 컬럼이 바이트 그대로인지)이
     * 따로 붙잡는다 — 둘 다 필요하며 어느 하나가 다른 하나를 대체하지 않는다.
     */
    @Test
    @DisplayName("★이_서비스는_이벤트_발행자와_원장_저장_창구를_필드로_갖지_않는다")
    void serviceDeclaresNoForbiddenCollaborator() {
        List<String> forbidden = List.of("ApplicationEventPublisher", "EnvironmentMetaService",
                "VideoPrivacyMetaService", "FramePrivacyMetaService", "FrameDescriptionService");

        assertThat(PortalWorkMetaService.class.getDeclaredFields())
                .extracting(Field::getType)
                .extracting(Class::getName)
                .allSatisfy(name -> assertThat(forbidden)
                        .as("금지 협력자를 필드로 주입하면 안 된다: " + name)
                        .noneMatch(name::contains));
    }

    // ================================================================ 컬럼 축 — Load

    /**
     * ★★ 이 시험이 막는 상태 — <b>키/값만 읽는 구현</b>. 그러면 다섯 축 가운데 셋이 화면에 아예
     * 나오지 않는다(값이 비는 것이 아니라 항목 자체가 없다). 컴파일도 되고 기존 시험도 전부 통과한다.
     */
    @Test
    @DisplayName("★컬럼_축_셋이_편집_목록에_키값_모양으로_함께_내려간다")
    void columnAxesAreProjectedAsKeyValues() {
        givenOrigin(Origin.DATAMART);

        PortalMetaResponse response = service.load(SRC_SN, ALICE);

        assertThat(response.items()).extracting(PortalMetaResponse.Item::scope,
                        PortalMetaResponse.Item::metaKey)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(PortalMetaScope.VIDEO,
                                PortalColumnMetaField.Keys.ENV_WEATHER),
                        org.assertj.core.groups.Tuple.tuple(PortalMetaScope.VIDEO,
                                PortalColumnMetaField.Keys.ENV_TIME_OF_DAY),
                        org.assertj.core.groups.Tuple.tuple(PortalMetaScope.VIDEO,
                                PortalColumnMetaField.Keys.ENV_SEASON),
                        org.assertj.core.groups.Tuple.tuple(PortalMetaScope.VIDEO,
                                PortalColumnMetaField.Keys.PRIVACY_ANONYMITY),
                        org.assertj.core.groups.Tuple.tuple(PortalMetaScope.FRAME,
                                PortalColumnMetaField.Keys.FRAME_DESCRIPTION),
                        org.assertj.core.groups.Tuple.tuple(PortalMetaScope.FRAME,
                                PortalColumnMetaField.Keys.PRIVACY_ANONYMITY));
    }

    @Test
    @DisplayName("저장값이_없으면_자동_계산값과_그_출처로_내려간다")
    void unstoredColumnFallsBackToDerived() {
        givenOrigin(Origin.DATAMART);
        set(raw, "shtDt", LocalDateTime.of(2026, 1, 15, 3, 0));

        PortalMetaResponse response = service.load(SRC_SN, ALICE);

        assertThat(pick(response, PortalMetaScope.VIDEO, PortalColumnMetaField.Keys.ENV_TIME_OF_DAY))
                .extracting(PortalMetaResponse.Item::metaVl, PortalMetaResponse.Item::source)
                .containsExactly("NGT", PortalMetaSource.DERIVED);
        assertThat(pick(response, PortalMetaScope.VIDEO, PortalColumnMetaField.Keys.ENV_SEASON))
                .extracting(PortalMetaResponse.Item::metaVl, PortalMetaResponse.Item::source)
                .containsExactly("WINTER", PortalMetaSource.DERIVED);
        assertThat(pick(response, PortalMetaScope.VIDEO, PortalColumnMetaField.Keys.PRIVACY_ANONYMITY))
                .extracting(PortalMetaResponse.Item::metaVl, PortalMetaResponse.Item::source)
                .as("개인정보 판정의 자동값은 비식별 산출물 기본상수다")
                .containsExactly("Y", PortalMetaSource.DERIVED);
    }

    @Test
    @DisplayName("저장값이_있으면_사람이_고른_값으로_표시된다")
    void storedColumnIsManual() {
        givenOrigin(Origin.DATAMART);
        set(raw, "wthrNm", "비");
        set(frame, "frmExpln", "사람이 쓴 설명");

        PortalMetaResponse response = service.load(SRC_SN, ALICE);

        assertThat(pick(response, PortalMetaScope.VIDEO, PortalColumnMetaField.Keys.ENV_WEATHER))
                .extracting(PortalMetaResponse.Item::metaVl, PortalMetaResponse.Item::source)
                .containsExactly("비", PortalMetaSource.MANUAL);
        assertThat(pick(response, PortalMetaScope.FRAME, PortalColumnMetaField.Keys.FRAME_DESCRIPTION))
                .extracting(PortalMetaResponse.Item::metaVl, PortalMetaResponse.Item::source)
                .containsExactly("사람이 쓴 설명", PortalMetaSource.MANUAL);
    }

    /** 자동 출처가 없는 축은 값을 <b>지어내지 않는다</b> — 없음은 없음으로 내려간다. */
    @Test
    @DisplayName("자동_출처가_없는_축은_값도_출처도_없음이다")
    void columnWithoutAutomaticSourceReportsNone() {
        givenOrigin(Origin.DATAMART);

        assertThat(pick(service.load(SRC_SN, ALICE),
                PortalMetaScope.VIDEO, PortalColumnMetaField.Keys.ENV_WEATHER))
                .extracting(PortalMetaResponse.Item::metaVl, PortalMetaResponse.Item::source)
                .containsExactly(null, PortalMetaSource.NONE);
    }

    /**
     * ★★ 개인정보 세 키는 <b>두 축에 같은 이름</b>으로 있다. 키만으로 식별하면 영상 값과 프레임 값이
     * 한 목록에서 섞인다 — 그러면 화면이 프레임 판정 자리에 영상 판정을 보여 준다.
     */
    @Test
    @DisplayName("★같은_개인정보_키가_영상_축과_프레임_축에_각각_자기_값으로_내려간다")
    void privacyKeysAreIdentifiedByScopeAndKeyPair() {
        givenOrigin(Origin.DATAMART);
        set(raw, "prvcInclYn", "Y");
        set(frame, "prvcInclYn", "N");

        PortalMetaResponse response = service.load(SRC_SN, ALICE);

        assertThat(pick(response, PortalMetaScope.VIDEO,
                PortalColumnMetaField.Keys.PRIVACY_PRIVACY_INCLUDED).metaVl()).isEqualTo("Y");
        assertThat(pick(response, PortalMetaScope.FRAME,
                PortalColumnMetaField.Keys.PRIVACY_PRIVACY_INCLUDED).metaVl()).isEqualTo("N");
    }

    @Test
    @DisplayName("오버레이가_컬럼_축을_가리면_가림_표시가_서고_출처는_덮인_쪽을_말한다")
    void overlayOverColumnKeepsOriginSource() {
        givenOrigin(Origin.DATAMART);
        when(overlayRepository.findByPortalUserNoAndSrcRawSnAndSrcDataSrcSnIsNull(ALICE, RAW_SN))
                .thenReturn(List.of(overlayMeta(
                        PortalColumnMetaField.Keys.PRIVACY_ANONYMITY, "N", null)));

        PortalMetaResponse.Item item = pick(service.load(SRC_SN, ALICE),
                PortalMetaScope.VIDEO, PortalColumnMetaField.Keys.PRIVACY_ANONYMITY);

        assertThat(item.metaVl()).isEqualTo("N");
        assertThat(item.overridden()).as("내 값이 덮었다").isTrue();
        assertThat(item.source())
                .as("덮인 쪽은 자동으로 계산된 값이었다 — 두 축을 하나로 합치면 표현되지 않는다")
                .isEqualTo(PortalMetaSource.DERIVED);
    }

    // ================================================================ 컬럼 축 — 저장

    /**
     * ★★★ 이 기능의 최상위 불변 — 데이터마트 자산의 <b>원장 컬럼을 고치지 않는다</b>.
     *
     * <p>같은 컬럼을 고치는 내부 창구를 그대로 부르는 것이 「중복 구현을 피하자」는 가장 자연스러운
     * 판단이고, 그 순간 재검토 표시·관제 통지·동결본 재동결이 함께 일어난다.
     */
    @Test
    @DisplayName("★데이터마트_자산의_컬럼_축_저장은_원장_컬럼에_쓰지_않고_오버레이에만_적재된다")
    void datamartColumnSaveNeverTouchesLedgerColumns() {
        givenOrigin(Origin.DATAMART);

        service.save(SRC_SN, ALICE, request(
                item(PortalColumnMetaField.Keys.ENV_WEATHER, "비", PortalMetaScope.VIDEO),
                item(PortalColumnMetaField.Keys.FRAME_DESCRIPTION, "설명", PortalMetaScope.FRAME)));

        verify(overlayRepository).upsertVideoScoped(ALICE, RAW_SN,
                PortalColumnMetaField.Keys.ENV_WEATHER, "비");
        verify(overlayRepository).upsertFrameScoped(ALICE, RAW_SN, SRC_SN,
                PortalColumnMetaField.Keys.FRAME_DESCRIPTION, "설명");
        verify(assetRepository, never()).updateOwnedVideoColumn(anyLong(), anyString(), anyString(), anyString());
        verify(assetRepository, never()).updateOwnedFrameColumn(anyLong(), anyString(), anyString(), anyString());
        assertThat(raw.getWthrNm()).as("원장 컬럼은 바이트 그대로다").isNull();
        assertThat(frame.getFrmExpln()).as("원장 컬럼은 바이트 그대로다").isNull();
    }

    @Test
    @DisplayName("★본인_업로드_자산의_컬럼_축은_그_자산의_원장_컬럼에_소유자_스코프로_적재된다")
    void uploadColumnSaveWritesOwnedLedgerColumns() {
        givenOrigin(Origin.PORTAL_UPLOAD);

        service.save(SRC_SN, ALICE, request(
                item(PortalColumnMetaField.Keys.ENV_WEATHER, "비", PortalMetaScope.VIDEO),
                item(PortalColumnMetaField.Keys.FRAME_DESCRIPTION, "설명", PortalMetaScope.FRAME)));

        verify(assetRepository).updateOwnedVideoColumn(RAW_SN, ALICE,
                PortalColumnMetaField.ENV_WEATHER.name(), "비");
        verify(assetRepository).updateOwnedFrameColumn(SRC_SN, ALICE,
                PortalColumnMetaField.FRAME_DESCRIPTION.name(), "설명");
        verify(overlayRepository, never()).upsertVideoScoped(anyString(), anyLong(), anyString(), anyString());
        verify(overlayRepository, never()).upsertFrameScoped(anyString(), anyLong(), anyLong(), anyString(), anyString());
    }

    /**
     * ★ 업로드 자산의 프레임 축 거부는 <b>키/값 축에만</b> 걸린다. 프레임 축 전체로 넓히면 프레임
     * 설명·프레임 개인정보 판정 셋이 통째로 막히고, 사용자는 이유 없이 저장이 거부되는 것을 본다.
     */
    @Test
    @DisplayName("★업로드_자산의_프레임_축_컬럼_키는_거부되지_않는다_키값_축만_막는다")
    void uploadFrameScopeGuardCoversKeyValueOnly() {
        givenOrigin(Origin.PORTAL_UPLOAD);

        service.save(SRC_SN, ALICE, request(item(
                PortalColumnMetaField.Keys.PRIVACY_ANONYMITY, "N", PortalMetaScope.FRAME)));

        verify(assetRepository).updateOwnedFrameColumn(SRC_SN, ALICE,
                PortalColumnMetaField.FRAME_ANONYMITY.name(), "N");
    }

    /**
     * ★★ 판별자가 막아도 <b>조용히 성공하지 않는다</b>.
     *
     * <p>원장 저장 창구는 「반영된 행 수」를 돌려주고 0 은 <b>「이 사용자의 포털 자산이 아니다」</b>라는
     * 뜻이다 — 실행문에 걸어 둔 <b>두 번째 방어선</b>이 발동한 신호다. 그 값을 버리면 창구가 아무것도
     * 쓰지 않고 200 을 내며, 방어선이 물었다는 사실을 아무도 모른다.
     *
     * <p>지금은 앞선 대상 판정이 막아 도달 경로가 없다 — 이 시험이 고정하는 것은 <b>그 방어선이
     * 발동했을 때의 관측 가능성</b>이지 도달 경로의 존재가 아니다.
     */
    @Test
    @DisplayName("★원장_컬럼_갱신이_0행이면_조용한_성공이_아니라_거부다")
    void zeroRowLedgerColumnWriteIsRejectedNotSilentlyAccepted() {
        givenOrigin(Origin.PORTAL_UPLOAD);
        when(assetRepository.updateOwnedVideoColumn(anyLong(), anyString(), anyString(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.save(SRC_SN, ALICE, request(
                item(PortalColumnMetaField.Keys.ENV_WEATHER, "비", PortalMetaScope.VIDEO))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    /** 키/값 축도 같다 — 창구가 갈려도 「0행 = 저장 아님」 판정은 하나다. */
    @Test
    @DisplayName("★원장_메타_적재가_0행이면_조용한_성공이_아니라_거부다")
    void zeroRowLedgerMetaWriteIsRejectedNotSilentlyAccepted() {
        givenOrigin(Origin.PORTAL_UPLOAD);
        when(assetRepository.upsertOwnedMeta(anyLong(), anyString(), anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.save(SRC_SN, ALICE, request(
                item("manual-timeseries", "내 값", PortalMetaScope.VIDEO))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    /** 프레임 축 창구도 같다. */
    @Test
    @DisplayName("★원장_프레임_컬럼_갱신이_0행이면_조용한_성공이_아니라_거부다")
    void zeroRowLedgerFrameColumnWriteIsRejectedNotSilentlyAccepted() {
        givenOrigin(Origin.PORTAL_UPLOAD);
        when(assetRepository.updateOwnedFrameColumn(anyLong(), anyString(), anyString(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.save(SRC_SN, ALICE, request(
                item(PortalColumnMetaField.Keys.FRAME_DESCRIPTION, "설명", PortalMetaScope.FRAME))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }

    /**
     * ★★★ 승격 방어는 <b>데이터마트 자산에만</b> 건다 — 업로드 자산은 <b>항상 원장에 쓴다</b>.
     *
     * <p>이 시험이 막는 상태: 본인 업로드 자산에서 원장 컬럼이 비어 있고 사용자가 <b>자동 계산값과
     * 같은 값을 일부러 골라</b> 저장하면 <b>아무 쓰기도 일어나지 않는</b> 것. 화면과 산출물이 같은
     * 파생을 다시 적용해 값이 우연히 일치하므로 <b>오차가 눈에 드러나지 않는다</b> — 그래서 이 경로를
     * 도는 시험이 한 건도 없었다.
     *
     * <p>귀결 셋: ①사용자의 명시 판정이 기록되지 않아 재조회 출처가 사람이 고른 값이 아니라 자동값으로
     * 남는다 ②원장 컬럼을 직접 읽는 이후 경로(내려받기·산출 조립)가 빈 값을 본다 — 업로드 자산을
     * 원장에 앉히라고 한 이유가 그것이다 ③파생 기본값·파생 규칙이 바뀌는 날 그 자산의 값이 조용히
     * 따라 바뀐다.
     *
     * <p>⚠ <b>파생 기본값을 여기에 하드코딩하지 않는다</b> — 소유자 상수를 그대로 참조해야 기본값이
     * 바뀌어도 이 시험이 계속 「자동 계산값과 같은 값을 저장하는 경로」를 겨눈다.
     */
    @Test
    @DisplayName("★업로드_자산은_자동_계산값과_같은_값이어도_원장에_실제로_쓴다_승격방어_비대상")
    void uploadSaveWritesLedgerEvenWhenValueEqualsDerived() {
        givenOrigin(Origin.PORTAL_UPLOAD);
        String derived = ExportPrivacyPolicy.DEID_DEFAULT_ANONYMITY;

        service.save(SRC_SN, ALICE, request(
                item(PortalColumnMetaField.Keys.PRIVACY_ANONYMITY, derived, PortalMetaScope.VIDEO),
                item(PortalColumnMetaField.Keys.PRIVACY_ANONYMITY, derived, PortalMetaScope.FRAME)));

        verify(assetRepository).updateOwnedVideoColumn(RAW_SN, ALICE,
                PortalColumnMetaField.VIDEO_ANONYMITY.name(), derived);
        verify(assetRepository).updateOwnedFrameColumn(SRC_SN, ALICE,
                PortalColumnMetaField.FRAME_ANONYMITY.name(), derived);
    }

    /**
     * ★ 업로드 자산에는 <b>지울 오버레이가 없다</b> — 저장처가 오버레이가 아니라 원장이기 때문이다.
     * 승격 방어를 좁히면서 삭제만 남겨 두면 남의 축을 건드린다.
     */
    @Test
    @DisplayName("★업로드_자산_저장은_오버레이_삭제문도_부르지_않는다")
    void uploadSaveNeverDeletesOverlay() {
        givenOrigin(Origin.PORTAL_UPLOAD);

        service.save(SRC_SN, ALICE, request(item(
                PortalColumnMetaField.Keys.PRIVACY_ANONYMITY,
                ExportPrivacyPolicy.DEID_DEFAULT_ANONYMITY, PortalMetaScope.VIDEO)));

        verify(overlayRepository, never()).deleteVideoScoped(anyString(), anyLong(), anyString());
        verify(overlayRepository, never()).deleteFrameScoped(anyString(), anyLong(), anyLong(), anyString());
    }

    /**
     * ★★ 승격 방어 — 화면이 자동 계산값을 그대로 되돌려 보내도 <b>사람의 판정으로 굳지 않는다</b>.
     * 이 방어는 화면 계약과 무관하게 단독으로 성립한다.
     */
    @Test
    @DisplayName("★자동_계산값을_그대로_되돌려_보내면_오버레이를_만들지_않는다")
    void echoingDerivedValueDoesNotCreateOverlay() {
        givenOrigin(Origin.DATAMART);
        set(raw, "shtDt", LocalDateTime.of(2026, 1, 15, 3, 0));

        service.save(SRC_SN, ALICE, request(
                item(PortalColumnMetaField.Keys.ENV_TIME_OF_DAY, "NGT", PortalMetaScope.VIDEO),
                item(PortalColumnMetaField.Keys.PRIVACY_ANONYMITY, "Y", PortalMetaScope.VIDEO)));

        verify(overlayRepository, never()).upsertVideoScoped(anyString(), anyLong(), anyString(), anyString());
    }

    /** 되돌려 보낸 값이 원본과 같으면 <b>이미 쌓여 있던 오버레이도 지운다</b> — 원본으로 되돌린다. */
    @Test
    @DisplayName("★원본과_같은_값을_저장하면_쌓여_있던_오버레이를_지운다")
    void savingOriginalValueRemovesExistingOverlay() {
        givenOrigin(Origin.DATAMART);
        set(raw, "wthrNm", "비");

        service.save(SRC_SN, ALICE, request(
                item(PortalColumnMetaField.Keys.ENV_WEATHER, "비", PortalMetaScope.VIDEO)));

        verify(overlayRepository).deleteVideoScoped(ALICE, RAW_SN,
                PortalColumnMetaField.Keys.ENV_WEATHER);
        verify(overlayRepository, never()).upsertVideoScoped(anyString(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("원본과_다른_값이면_그대로_오버레이에_적재된다")
    void differentValueStillCreatesOverlay() {
        givenOrigin(Origin.DATAMART);
        set(raw, "wthrNm", "비");

        service.save(SRC_SN, ALICE, request(
                item(PortalColumnMetaField.Keys.ENV_WEATHER, "눈", PortalMetaScope.VIDEO)));

        verify(overlayRepository).upsertVideoScoped(ALICE, RAW_SN,
                PortalColumnMetaField.Keys.ENV_WEATHER, "눈");
        verify(overlayRepository, never()).deleteVideoScoped(anyString(), anyLong(), anyString());
    }

    /**
     * ★ 승격 방어는 <b>컬럼 축에만</b> 건다. 키/값 축에는 자동 계산 프리필이 없어 승격이 성립하지
     * 않고, 거기까지 넓히면 「원본과 같은 값을 일부러 저장했다」가 조용히 사라진다.
     */
    @Test
    @DisplayName("★승격_방어는_컬럼_축에만_건다_키값_축은_원본과_같아도_그대로_쌓인다")
    void promotionGuardIsScopedToColumnAxis() {
        givenOrigin(Origin.DATAMART);
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(ledgerMeta("manual-timeseries", "같은 값")));

        service.save(SRC_SN, ALICE, request(item("manual-timeseries", "같은 값", PortalMetaScope.VIDEO)));

        verify(overlayRepository).upsertVideoScoped(ALICE, RAW_SN, "manual-timeseries", "같은 값");
        verify(overlayRepository, never()).deleteVideoScoped(anyString(), anyLong(), anyString());
    }

    // ================================================================ 컬럼 축 — 거부

    @Test
    @DisplayName("★축이_어긋난_컬럼_키는_400이다_조용히_한쪽으로_접지_않는다")
    void columnKeyWithMismatchedScopeIsRejected() {
        givenOrigin(Origin.DATAMART);

        assertThatThrownBy(() -> service.save(SRC_SN, ALICE, request(
                item(PortalColumnMetaField.Keys.ENV_WEATHER, "비", PortalMetaScope.FRAME))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

        assertThatThrownBy(() -> service.save(SRC_SN, ALICE, request(
                item(PortalColumnMetaField.Keys.FRAME_DESCRIPTION, "설명", PortalMetaScope.VIDEO))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

        verify(overlayRepository, never()).upsertVideoScoped(anyString(), anyLong(), anyString(), anyString());
        verify(overlayRepository, never()).upsertFrameScoped(anyString(), anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("허용_목록_밖의_컬럼_값은_400이다")
    void columnValueOutsideAllowlistIsRejected() {
        givenOrigin(Origin.DATAMART);

        assertThatThrownBy(() -> service.save(SRC_SN, ALICE, request(
                item(PortalColumnMetaField.Keys.ENV_TIME_OF_DAY, "정오", PortalMetaScope.VIDEO))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

        assertThatThrownBy(() -> service.save(SRC_SN, ALICE, request(
                item(PortalColumnMetaField.Keys.PRIVACY_ANONYMITY, "X", PortalMetaScope.VIDEO))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
    }

    /**
     * ★ 폭은 <b>좁은 쪽</b>이다. 오버레이 저장 폭(2000)만 보고 통과시키면 사용자가 자기 데이터를
     * 내려받을 때 원본과 같은 자리에 들어가지 못하는 값이 생긴다.
     */
    @Test
    @DisplayName("★프레임_설명은_원본_폭을_넘으면_400이다_저장_폭이_아니라_좁은_쪽")
    void frameDescriptionUsesNarrowerOriginalWidth() {
        givenOrigin(Origin.DATAMART);
        String atLimit = "가".repeat(PortalColumnMetaField.FRAME_DESCRIPTION_MAX_LENGTH);
        String overLimit = "가".repeat(PortalColumnMetaField.FRAME_DESCRIPTION_MAX_LENGTH + 1);

        service.save(SRC_SN, ALICE, request(
                item(PortalColumnMetaField.Keys.FRAME_DESCRIPTION, atLimit, PortalMetaScope.FRAME)));
        verify(overlayRepository).upsertFrameScoped(ALICE, RAW_SN, SRC_SN,
                PortalColumnMetaField.Keys.FRAME_DESCRIPTION, atLimit);

        assertThatThrownBy(() -> service.save(SRC_SN, ALICE, request(
                item(PortalColumnMetaField.Keys.FRAME_DESCRIPTION, overLimit, PortalMetaScope.FRAME))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
    }

    // ================================================================ 비식별 누락 신고 게이트

    @Test
    @DisplayName("★신고_구간의_데이터마트_자산은_읽기도_쓰기도_412다")
    void deidentReportBlocksBothReadAndWrite() {
        givenOrigin(Origin.DATAMART);
        doThrow(new CustomException(ErrorCode.PRECONDITION_FAILED, "비식별 재처리 대기 중인 영상입니다."))
                .when(accessGuard).requireNotUnderDeidentReport(RAW_SN);

        assertThatThrownBy(() -> service.load(SRC_SN, ALICE))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRECONDITION_FAILED);
        assertThatThrownBy(() -> service.save(SRC_SN, ALICE,
                request(item("note", "값", PortalMetaScope.VIDEO))))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRECONDITION_FAILED);

        verify(overlayRepository, never()).upsertVideoScoped(anyString(), anyLong(), anyString(), anyString());
    }

    /**
     * ★ 본인 업로드 자산은 내부 파이프라인의 비식별 라이프사이클이 <b>없어</b> 그 구간 자체가
     * 존재하지 않는다. 게이트를 씌우면 정상 자산이 막힌다.
     */
    @Test
    @DisplayName("★본인_업로드_자산은_신고_게이트의_대상이_아니다")
    void uploadAssetIsNotUnderDeidentGate() {
        givenOrigin(Origin.PORTAL_UPLOAD);

        service.load(SRC_SN, ALICE);
        service.save(SRC_SN, ALICE, request(item("note", "값", PortalMetaScope.VIDEO)));

        verifyNoInteractions(accessGuard);
    }

    // ---------------- reflection helpers ----------------

    private static <T> T newInstance(Class<T> type) {
        try {
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
