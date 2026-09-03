package kr.co.cudo.authoring.portal.augment;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
import kr.co.cudo.authoring.augment.integration.AugmentPrompts;
import kr.co.cudo.authoring.augment.service.AugmentCallbackUrlResolver;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.PortalAugmentCreatedResponse;
import kr.co.cudo.authoring.portal.dto.PortalAugmentDetailResponse;
import kr.co.cudo.authoring.portal.dto.PortalAugmentRequest;
import kr.co.cudo.authoring.portal.dto.PortalAugmentSummaryResponse;
import kr.co.cudo.authoring.portal.service.PortalAugmentFailureReason;
import kr.co.cudo.authoring.portal.service.PortalAugmentService;
import kr.co.cudo.authoring.portal.upload.PortalAugmentRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadFrameRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 포털 증강 — 접수 게이트 · 위탁 배선 · 조회 스코프.
 *
 * <p>이 시험이 지키는 축:
 * <ol>
 *   <li><b>생성 조건은 다섯 항목 전부 필수</b>이며 하나라도 비면 접수 자체가 서지 않는다.</li>
 *   <li><b>요청이 실제로 외부 위탁으로 이어진다</b> — 접수만 하고 멈추지 않는다.</li>
 *   <li><b>요청자가 이벤트 유형·세부 유형·증강 종류를 고르지 않는다</b> — 본문에 자리가 없고
 *       위탁 이벤트도 그 값을 나르지 않는다.</li>
 *   <li><b>실패 사유는 사용자에게 보여 줄 수 있는 문장</b>이며 내부 표현이 섞이지 않는다.</li>
 *   <li>데이터마트 로드분은 요청 대상에 뜨지 않고, 채택·반려 결정 단계가 없으며,
 *       결과물 식별자는 본인 포털 자산으로 확인된 것만 실린다.</li>
 * </ol>
 */
class PortalAugmentServiceTest {

    private static final long ULD_SN = 501L;
    private static final long SRC_SN = 9101L;
    private static final String OWNER = "portal-user-1";
    private static final String CALLBACK_URL = "http://localhost:8080/api/v1/genai/callback";

    private PortalUploadAssetRepository assetRepository;
    private PortalUploadFrameRepository frameRepository;
    private PortalAugmentRepository augmentRepository;
    private ApplicationEventPublisher eventPublisher;
    private PortalAugmentService service;

    @BeforeEach
    void setUp() {
        assetRepository = mock(PortalUploadAssetRepository.class);
        frameRepository = mock(PortalUploadFrameRepository.class);
        augmentRepository = mock(PortalAugmentRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        AugmentCallbackUrlResolver callbackUrlResolver = mock(AugmentCallbackUrlResolver.class);
        when(callbackUrlResolver.resolve()).thenReturn(CALLBACK_URL);
        service = new PortalAugmentService(assetRepository, frameRepository, augmentRepository,
                new ObjectMapper(), eventPublisher, callbackUrlResolver);

        givenAsset(PortalUploadLedger.STATUS_READY, PortalUploadLedger.TYPE_VIDEO);
        when(frameRepository.findFirstByRawSnOrderByFrameNoAscSrcSnAsc(ULD_SN))
                .thenReturn(Optional.of(frame(SRC_SN, ULD_SN)));
        when(augmentRepository.save(any(LsDataAug.class))).thenAnswer(inv -> {
            LsDataAug saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "dataAugSn", 9001L);
            return saved;
        });
    }

    private void givenAsset(String status, String type) {
        PortalUploadAsset asset = new PortalUploadAsset(ULD_SN, OWNER, type, "street.mp4",
                "/p/street.mp4", 1024L, "video/mp4", status, 60.0, 30.0, 3, null,
                LocalDateTime.now(), LocalDateTime.now());
        when(assetRepository.findByOwner(ULD_SN, OWNER)).thenReturn(Optional.of(asset));
    }

    private static LsDataSrc frame(long srcSn, long rawSn) {
        LsDataSrc f = LsDataSrc.create(rawSn, 0L, 0L, "/p/frames/0.jpg", null);
        ReflectionTestUtils.setField(f, "srcSn", srcSn);
        return f;
    }

    private static LsDataAug aug(long augSn, long srcSn, String promptCn, Long newRawSn) {
        LsDataAug a = LsDataAug.createRequested(srcSn, LsDataAug.AUG_AUGMENT, OWNER,
                "AUG-" + augSn, null, promptCn);
        ReflectionTestUtils.setField(a, "dataAugSn", augSn);
        if (newRawSn != null) {
            a.assignDerivativeRawSn(newRawSn);
        }
        return a;
    }

    private TokenClaims owner() {
        return new TokenClaims(OWNER, Role.PORTAL_USER, Channel.PORTAL, Instant.now().plusSeconds(60));
    }

    /** 다섯 항목을 전부 채운 정상 요청. */
    private static PortalAugmentRequest body() {
        return body(null);
    }

    private static PortalAugmentRequest body(String prompt) {
        return new PortalAugmentRequest(new PortalAugmentRequest.GenerationCondition(
                AugmentPrompts.Time.NIGHT, AugmentPrompts.Season.WINTER,
                AugmentPrompts.Weather.SNOW, AugmentPrompts.Terrain.ROAD,
                AugmentPrompts.Severity.HIGH), prompt);
    }

    private AugmentRequestedItemEvent captureSubmitEvent() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(AugmentRequestedItemEvent.class);
        return (AugmentRequestedItemEvent) captor.getValue();
    }

    private LsDataAug captureSaved() {
        ArgumentCaptor<LsDataAug> captor = ArgumentCaptor.forClass(LsDataAug.class);
        verify(augmentRepository).save(captor.capture());
        return captor.getValue();
    }

    // ======================== 접수 ========================

    @Test
    @DisplayName("본인_준비완료_영상에_요청하면_접수된다")
    void acceptsRequestOnOwnReadyVideo() {
        PortalAugmentCreatedResponse res = service.request(ULD_SN, body(), owner());

        assertThat(res.uldSn()).isEqualTo(ULD_SN);
        assertThat(res.requestedAt()).isNotNull();

        LsDataAug saved = captureSaved();
        assertThat(saved.getSrcSn()).isEqualTo(SRC_SN);
        assertThat(saved.getRegUserNo()).isEqualTo(OWNER);
        assertThat(saved.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_PENDING);
    }

    /**
     * ★ 가드 — 접수만 하고 멈추면 화면이 <b>영원히 「결과 대기 중」</b>이다. 위탁 개시 이벤트가
     * 발행되는지를 고정한다(수신자는 관제 채널과 같은 브리지다).
     */
    @Test
    @DisplayName("★요청은_실제로_외부_위탁으로_이어진다 — 위탁_개시_이벤트가_발행된다")
    void publishesSubmitEvent() {
        service.request(ULD_SN, body("눈 내리는 밤으로"), owner());

        AugmentRequestedItemEvent event = captureSubmitEvent();
        assertThat(event.originAugSn()).isEqualTo(captureSaved().getDataAugSn());
        assertThat(event.rawSn()).isEqualTo(ULD_SN);
        assertThat(event.requestUserNo()).isEqualTo(OWNER);
        assertThat(event.callbackUrl()).isEqualTo(CALLBACK_URL);
        assertThat(event.idempotencyKey()).isEqualTo(captureSaved().getIdempotencyKey());
        assertThat(event.promptText()).isEqualTo("눈 내리는 밤으로");
    }

    /**
     * ★ 가드 — 위탁으로 나가는 생성 조건은 요청자가 고른 다섯 항목 <b>그대로</b>이며 그 이상도
     * 이하도 아니다. 이벤트 유형·세부 유형은 <b>이 이벤트가 나르지 않는다</b>(위탁 클라이언트가
     * 중립값을 고정 송신한다) — 되살리면 요청자 입력이 위탁으로 흘러드는 경로가 열린다.
     */
    @Test
    @DisplayName("★위탁에_실리는_생성_조건은_다섯_항목_그대로이고_이벤트유형은_실리지_않는다")
    void submitEventCarriesOnlyTheFiveConditionItems() {
        service.request(ULD_SN, body(), owner());

        AugmentRequestedItemEvent event = captureSubmitEvent();
        assertThat(event.mtdt()).containsExactly(
                Map.entry(AugmentPrompts.KEY_TIME, "NIGHT"),
                Map.entry(AugmentPrompts.KEY_SEASON, "WINTER"),
                Map.entry(AugmentPrompts.KEY_WEATHER, "SNOW"),
                Map.entry(AugmentPrompts.KEY_TERRAIN, "ROAD"),
                Map.entry(AugmentPrompts.KEY_SEVERITY, "HIGH"));

        Set<String> carried = componentNames(AugmentRequestedItemEvent.class);
        assertThat(carried).doesNotContain("evntType", "evntSubtype");
    }

    /**
     * ★ 가드 — 증강 종류는 서버가 <b>단일 상수</b>로 고정한다(요청자가 고르지 않는다). 채널 판별자를
     * 담던 구 값으로 되돌리면 회수 스윕의 후보 목록에서 빠져 위탁 실패가 「기다리는 중」에 고착한다.
     */
    @Test
    @DisplayName("★증강_종류는_서버가_단일값으로_고정한다 — 채널_판별자를_담지_않는다")
    void augTypeIsTheServerFixedSingleValue() {
        service.request(ULD_SN, body(), owner());

        assertThat(captureSaved().getAugTypeCd()).isEqualTo(LsDataAug.AUG_AUGMENT);
        assertThat(captureSubmitEvent().augType()).isEqualTo(LsDataAug.AUG_AUGMENT);
        assertThat(AugmentPrompts.isExternalAugType(LsDataAug.AUG_AUGMENT))
                .as("회수 스윕이 후보로 집을 수 있어야 실패가 화면에 드러난다").isTrue();
    }

    /**
     * ★ 가드 — 요청 본문에 <b>자리 자체가 없어야</b> 그 키가 위탁으로 실릴 경로가 구조적으로 없다.
     */
    @Test
    @DisplayName("★요청_본문에는_이벤트유형·세부유형·증강종류_자리가_없다")
    void requestBodyHasNoEventTypeOrAugTypeSlot() {
        assertThat(componentNames(PortalAugmentRequest.class))
                .containsExactly("generationCondition", "prompt");
        assertThat(componentNames(PortalAugmentRequest.GenerationCondition.class))
                .containsExactly("time", "season", "weather", "terrain", "severity");
    }

    private static Set<String> componentNames(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    @DisplayName("보관은_감싼_형태이고_되읽으면_다섯_항목이다 — 나간_값과_보관값이_갈라지지_않는다")
    void storesConditionInTheSameShapeAsTheOutgoingBody() {
        service.request(ULD_SN, body("지시문"), owner());

        assertThat(captureSaved().getPromptCn()).isEqualTo(
                "{\"mtdt\":{\"time\":\"NIGHT\",\"season\":\"WINTER\",\"weather\":\"SNOW\","
                        + "\"terrain\":\"ROAD\",\"severity\":\"HIGH\"},\"prompt\":\"지시문\"}");
    }

    @Test
    @DisplayName("지시문은_선택이며_보이지_않는_문자만_남으면_보내지도_보관하지도_않는다")
    void promptIsOptional() {
        service.request(ULD_SN, body("\u200B  \u00A0"), owner());

        assertThat(captureSaved().getPromptCn()).doesNotContain("prompt");
        assertThat(captureSubmitEvent().promptText()).isNull();
    }

    @Test
    @DisplayName("지시문이_상한을_넘으면_400이다 — 잘라내지_않는다")
    void oversizedPromptIsRejectedAtTheDoor() {
        String tooLong = "가".repeat(AugmentPrompts.MAX_PROMPT_LENGTH + 1);

        assertThatThrownBy(() -> service.request(ULD_SN, body(tooLong), owner()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    /**
     * ★ 가드 — 항목이 하나라도 비면 위탁받는 쪽이 그 자리를 어떤 기본값으로 채울지 알 수 없어
     * 같은 요청의 결과가 비결정적이 된다. 그래서 <b>부분 입력을 허용하지 않는다</b>.
     */
    @Test
    @DisplayName("★생성_조건_항목이_하나라도_비면_400이며_자산_조회도_위탁도_없다")
    void partialConditionIsRejectedBeforeAnyLookup() {
        PortalAugmentRequest partial = new PortalAugmentRequest(
                new PortalAugmentRequest.GenerationCondition(
                        AugmentPrompts.Time.NIGHT, AugmentPrompts.Season.WINTER,
                        AugmentPrompts.Weather.SNOW, AugmentPrompts.Terrain.ROAD, null), null);

        assertThatThrownBy(() -> service.request(ULD_SN, partial, owner()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(assetRepository, never()).findByOwner(any(), anyString());
        verify(augmentRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("생성_조건이_통째로_없으면_400이며_자산_조회조차_하지_않는다")
    void missingConditionIsRejectedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.request(ULD_SN, new PortalAugmentRequest(null, null), owner()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(assetRepository, never()).findByOwner(any(), anyString());
    }

    /**
     * ★ 가드 — 데이터마트에서 불러온 영상은 포털 업로드 자산 원장에 없다. 그래서 조회가 비고,
     * 남의 자산·없는 자산과 <b>같은 코드</b>로 거절된다(존재 오라클 차단).
     */
    @Test
    @DisplayName("★데이터마트_로드분은_요청_대상이_아니다 — 남의_자산_없는_자산과_같은_403")
    void datamartVideoIsNotARequestTarget() {
        long datamartRawSn = 777L;
        when(assetRepository.findByOwner(datamartRawSn, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.request(datamartRawSn, body(), owner()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
        verify(augmentRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("남의_자산도_같은_403이며_증강_행을_만들지_않는다")
    void foreignAssetIsForbidden() {
        when(assetRepository.findByOwner(ULD_SN, "someone-else")).thenReturn(Optional.empty());
        TokenClaims other = new TokenClaims("someone-else", Role.PORTAL_USER, Channel.PORTAL,
                Instant.now().plusSeconds(60));

        assertThatThrownBy(() -> service.request(ULD_SN, body(), other))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("영상이_아닌_자산은_400이다 — 기다려도_달라지지_않는_영구_조건")
    void nonVideoAssetIsBadRequest() {
        givenAsset(PortalUploadLedger.STATUS_READY, PortalUploadLedger.TYPE_IMAGE);

        assertThatThrownBy(() -> service.request(ULD_SN, body(), owner()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("준비가_끝나지_않은_영상은_409다 — 기다리면_풀리는_일시_조건")
    void notReadyAssetIsConflict() {
        givenAsset(PortalUploadLedger.STATUS_PROCESSING, PortalUploadLedger.TYPE_VIDEO);

        assertThatThrownBy(() -> service.request(ULD_SN, body(), owner()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("기준_프레임이_없으면_409다 — 생성_0건을_성공으로_회신하지_않는다")
    void missingRepresentativeFrameIsConflict() {
        when(frameRepository.findFirstByRawSnOrderByFrameNoAscSrcSnAsc(ULD_SN))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.request(ULD_SN, body(), owner()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
        verify(augmentRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("토큰_주체가_없으면_401이다")
    void missingActorIsUnauthorized() {
        assertThatThrownBy(() -> service.request(ULD_SN, body(), null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    // ======================== 조회 ========================

    @Test
    @DisplayName("현황_목록은_보관한_생성_조건을_그대로_되돌려준다")
    void listEchoesStoredCondition() {
        givenPage(aug(9001L, SRC_SN, "{\"mtdt\":{\"season\":\"WINTER\"},\"prompt\":\"x\"}", null));

        Page<PortalAugmentSummaryResponse> page = service.list(owner(), PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        PortalAugmentSummaryResponse row = page.getContent().get(0);
        assertThat(row.augSn()).isEqualTo(9001L);
        assertThat(row.uldSn()).isEqualTo(ULD_SN);
        assertThat(row.orgnlFileNm()).isEqualTo("street.mp4");
        assertThat(row.generationCondition()).isEqualTo(Map.of("season", "WINTER"));
        assertThat(row.resultReady()).isFalse();
        assertThat(row.resultArrivedAt()).isNull();
    }

    @Test
    @DisplayName("감싸지_않은_보관값은_이_변경_이전_행이므로_그대로_되돌려준다")
    void listEchoesLegacyFlatCondition() {
        givenPage(aug(9005L, SRC_SN, "{\"season\":\"WINTER\"}", null));

        assertThat(service.list(owner(), PageRequest.of(0, 20)).getContent().get(0)
                .generationCondition()).isEqualTo(Map.of("season", "WINTER"));
    }

    @Test
    @DisplayName("요청이_하나도_없으면_빈_목록이며_오류가_아니다")
    void emptyListIsSuccess() {
        when(augmentRepository.findPageByOwner(anyString(), anyString(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        assertThat(service.list(owner(), PageRequest.of(0, 20)).getContent()).isEmpty();
    }

    @Test
    @DisplayName("보관값이_읽히지_않으면_그_행만_빈_조건이며_페이지_전체가_무너지지_않는다")
    void unreadableConditionDegradesToEmptyObject() {
        givenPage(aug(9002L, SRC_SN, "not-json", null));

        assertThat(service.list(owner(), PageRequest.of(0, 20)).getContent().get(0)
                .generationCondition()).isEmpty();
    }

    @Test
    @DisplayName("결과물이_본인_포털_자산으로_확인되면_식별자와_도착_일시를_싣는다")
    void exposesResultWhenItIsAnOwnedPortalAsset() {
        long resultUldSn = 802L;
        LsDataAug a = aug(9003L, SRC_SN, "{}", resultUldSn);
        givenSingle(a);
        LocalDateTime arrivedAt = LocalDateTime.now().minusMinutes(5);
        when(assetRepository.findRegDtByOwner(OWNER, List.of(resultUldSn)))
                .thenReturn(Map.of(resultUldSn, arrivedAt));

        PortalAugmentDetailResponse res = service.get(9003L, owner());

        assertThat(res.resultReady()).isTrue();
        assertThat(res.resultUldSn()).isEqualTo(resultUldSn);
        assertThat(res.resultArrivedAt()).isEqualTo(arrivedAt);
        assertThat(res.failRsnCn()).isNull();
    }

    /**
     * ★ 원장에 값이 있어도 그 자산이 이 사용자가 열 수 없는 것이면 식별자를 주지 않는다 —
     * 화면이 그 값으로 후속 작업·내려받기를 부르는데, 열 수 없는 식별자는 거절될 뿐 아니라
     * 존재 자체를 드러낸다.
     */
    @Test
    @DisplayName("★결과물이_본인_포털_자산이_아니면_식별자를_싣지_않는다")
    void hidesResultIdentifierWhenNotAnOwnedPortalAsset() {
        givenSingle(aug(9004L, SRC_SN, "{}", 803L));
        when(assetRepository.findRegDtByOwner(OWNER, List.of(803L))).thenReturn(Map.of());

        PortalAugmentDetailResponse res = service.get(9004L, owner());

        assertThat(res.resultReady()).isFalse();
        assertThat(res.resultUldSn()).isNull();
        assertThat(res.resultArrivedAt()).isNull();
    }

    @Test
    @DisplayName("남의_요청과_없는_요청은_같은_403이다")
    void foreignOrMissingRequestIsForbidden() {
        when(augmentRepository.findByOwner(any(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(9999L, owner()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FORBIDDEN);
    }

    // ======================== 실패 축 (3구분) ========================

    /**
     * ★ 가드 — 결과 도착 여부 하나로는 <b>기다리는 중과 실패가 갈리지 않는다</b>. 목록 <b>하나로</b>
     * 세 구분이 서야 화면이 실패를 가려내려고 행마다 단건 조회를 부르지 않는다.
     */
    @Test
    @DisplayName("★기다리는_중인_요청은_목록에서_실패_사유가_비어_있다")
    void pendingRowCarriesNoFailureReason() {
        givenPage(aug(9010L, SRC_SN, "{}", null));

        PortalAugmentSummaryResponse row = service.list(owner(), PageRequest.of(0, 20))
                .getContent().get(0);
        assertThat(row.resultReady()).isFalse();
        assertThat(row.failRsnCn()).isNull();
    }

    @Test
    @DisplayName("★실패한_요청은_목록_행_자체에서_가려진다 — 도착도_대기도_아니다")
    void failedRowCarriesFailureReasonInTheListItself() {
        LsDataAug failed = aug(9011L, SRC_SN, "{}", null);
        failed.applyGenerationResult(LsDataAug.STTS_REJECTED);
        givenPage(failed);

        PortalAugmentSummaryResponse row = service.list(owner(), PageRequest.of(0, 20))
                .getContent().get(0);
        assertThat(row.resultReady()).isFalse();
        assertThat(row.failRsnCn()).isEqualTo(PortalAugmentFailureReason.GENERIC_FAILURE);
    }

    /**
     * ★ 가드 — 결과 반입이 영구 실패하면 상태는 {@code ACCEPTED} 로 남는다. 상태만 보면 그 요청이
     * <b>영원히 「기다리는 중」</b>으로 보인다.
     */
    @Test
    @DisplayName("★상태가_수락인데_확정이_영구_실패한_요청도_실패로_드러난다")
    void deadLetteredRowIsAlsoAFailure() {
        LsDataAug stuck = aug(9012L, SRC_SN, "{}", null);
        stuck.applyGenerationResult(LsDataAug.STTS_ACCEPTED);
        ReflectionTestUtils.setField(stuck, "deadLetterAt", LocalDateTime.now());
        givenPage(stuck);

        assertThat(service.list(owner(), PageRequest.of(0, 20)).getContent().get(0).failRsnCn())
                .isEqualTo(PortalAugmentFailureReason.GENERIC_FAILURE);
    }

    /**
     * ★★ 가드 — 실패 사유는 <b>외부 채널로 그대로 나가는 값</b>이다. 같은 저장소의 다른 실패사유
     * 필드는 예외 클래스명을 그대로 싣는데({@code "…실패: RuntimeException"}) <b>그 방식을 복사하면
     * 안 된다</b>. 보관 원문에 경로·예외 이름·제약 이름을 심어 두고, 그 어느 것도 응답으로 새지
     * 않음을 확인한다.
     */
    @Test
    @DisplayName("★★실패_사유에_예외_클래스명·내부_경로·제약_이름이_섞이지_않는다")
    void failureReasonNeverLeaksInternals() {
        String poisoned = "{\"mtdt\":{},\"prompt\":\"/nas-storage/deid/501/frame-0.jpg "
                + "RuntimeException UK_LS_DATA_AUG_ACTVTN ERR_DEID_PATH_MISSING\"}";
        LsDataAug failed = aug(9013L, SRC_SN, poisoned, null);
        failed.applyGenerationResult(LsDataAug.STTS_REJECTED);
        givenPage(failed);

        String reason = service.list(owner(), PageRequest.of(0, 20)).getContent().get(0).failRsnCn();

        assertThat(reason).isNotBlank();
        assertThat(reason).doesNotContain("Exception", "Error", "/", "\\", "ERR_", "UK_",
                "LS_DATA", "SQL", ".java", ".jpg", "nas-storage");
    }

    /**
     * ★ 가드 — 어떤 상태 조합에서도 이 창구가 낼 수 있는 문장은 <b>하나뿐</b>이다. 사유를 원장에서
     * 끌어오도록 바꾸면 값이 상태마다 갈려 이 시험이 깨진다.
     */
    @Test
    @DisplayName("★실패_사유는_상태와_무관하게_고정_문장_하나뿐이다")
    void failureReasonIsASingleFixedSentence() {
        Set<String> produced = new LinkedHashSet<>();
        for (String status : List.of(LsDataAug.STTS_PENDING, LsDataAug.STTS_ACCEPTED,
                LsDataAug.STTS_REJECTED, LsDataAug.STTS_CANCELED)) {
            for (boolean deadLetter : new boolean[]{false, true}) {
                LsDataAug a = aug(9014L, SRC_SN, "{}", null);
                ReflectionTestUtils.setField(a, "augProcSttsCd", status);
                if (deadLetter) {
                    ReflectionTestUtils.setField(a, "deadLetterAt", LocalDateTime.now());
                }
                String reason = PortalAugmentFailureReason.of(a);
                if (reason != null) {
                    produced.add(reason);
                }
            }
        }
        assertThat(produced).containsExactly(PortalAugmentFailureReason.GENERIC_FAILURE);
    }

    // ======================== 픽스처 ========================

    private void givenPage(LsDataAug a) {
        when(augmentRepository.findPageByOwner(anyString(), anyString(), any()))
                .thenReturn(new PageImpl<>(List.of(a), PageRequest.of(0, 20), 1));
        stubView(a);
    }

    private void givenSingle(LsDataAug a) {
        when(augmentRepository.findByOwner(a.getDataAugSn(), OWNER, PortalUploadLedger.SRC_TYPE))
                .thenReturn(Optional.of(a));
        stubView(a);
    }

    private void stubView(LsDataAug a) {
        when(frameRepository.findAllById(List.of(a.getSrcSn())))
                .thenReturn(List.of(frame(a.getSrcSn(), ULD_SN)));
        when(assetRepository.findOriginalFileNames(anyString(), anyList()))
                .thenReturn(Map.of(ULD_SN, "street.mp4"));
    }
}
