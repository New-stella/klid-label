package kr.co.cudo.authoring.portal.augment;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
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
import kr.co.cudo.authoring.portal.service.PortalAugmentService;
import kr.co.cudo.authoring.portal.upload.PortalAugmentRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadFrameRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * 포털 증강 — 접수 게이트와 조회 스코프.
 *
 * <p>이 시험이 지키는 축 셋:
 * <ol>
 *   <li><b>데이터마트 로드분은 요청 대상에 뜨지 않는다</b> — 포털 업로드 자산 원장에 없으므로
 *       남의 자산·없는 자산과 같은 코드로 거절된다.</li>
 *   <li><b>채택·반려 결정 단계가 없다</b> — 그 창구도 그 상태 축도 만들지 않는다.</li>
 *   <li><b>결과물 식별자는 본인 포털 자산으로 확인된 것만</b> 응답에 실린다.</li>
 * </ol>
 */
class PortalAugmentServiceTest {

    private static final long ULD_SN = 501L;
    private static final long SRC_SN = 9101L;
    private static final String OWNER = "portal-user-1";
    private static final Map<String, Object> CONDITION = Map.of("season", "WINTER", "time", "NIGHT");

    private PortalUploadAssetRepository assetRepository;
    private PortalUploadFrameRepository frameRepository;
    private PortalAugmentRepository augmentRepository;
    private PortalAugmentService service;

    @BeforeEach
    void setUp() {
        assetRepository = mock(PortalUploadAssetRepository.class);
        frameRepository = mock(PortalUploadFrameRepository.class);
        augmentRepository = mock(PortalAugmentRepository.class);
        service = new PortalAugmentService(assetRepository, frameRepository, augmentRepository,
                new ObjectMapper());

        givenAsset(PortalUploadLedger.STATUS_READY, PortalUploadLedger.TYPE_VIDEO);
        when(frameRepository.findFirstByRawSnOrderByFrameNoAscSrcSnAsc(ULD_SN))
                .thenReturn(Optional.of(frame(SRC_SN, ULD_SN)));
        when(augmentRepository.save(any(LsDataAug.class))).thenAnswer(inv -> inv.getArgument(0));
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
        LsDataAug a = LsDataAug.createRequested(srcSn, LsDataRaw.SRC_TYPE_PORTAL_ULD, OWNER,
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

    private static PortalAugmentRequest body() {
        return new PortalAugmentRequest(CONDITION);
    }

    // ======================== 접수 ========================

    @Test
    @DisplayName("본인_준비완료_영상에_요청하면_접수된다")
    void acceptsRequestOnOwnReadyVideo() {
        PortalAugmentCreatedResponse res = service.request(ULD_SN, body(), owner());

        assertThat(res.uldSn()).isEqualTo(ULD_SN);
        assertThat(res.requestedAt()).isNotNull();

        ArgumentCaptor<LsDataAug> captor = ArgumentCaptor.forClass(LsDataAug.class);
        verify(augmentRepository).save(captor.capture());
        LsDataAug saved = captor.getValue();
        assertThat(saved.getSrcSn()).isEqualTo(SRC_SN);
        assertThat(saved.getRegUserNo()).isEqualTo(OWNER);
        assertThat(saved.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_PENDING);
    }

    @Test
    @DisplayName("생성_조건은_받은_그대로_보관된다 — 항목을_해석하거나_고르지_않는다")
    void storesGenerationConditionVerbatim() {
        service.request(ULD_SN, new PortalAugmentRequest(Map.of("아무거나", "값")), owner());

        ArgumentCaptor<LsDataAug> captor = ArgumentCaptor.forClass(LsDataAug.class);
        verify(augmentRepository).save(captor.capture());
        assertThat(captor.getValue().getPromptCn()).isEqualTo("{\"아무거나\":\"값\"}");
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
    }

    @Test
    @DisplayName("생성_조건이_비면_400이며_자산_조회조차_하지_않는다")
    void emptyConditionIsRejectedBeforeAnyLookup() {
        assertThatThrownBy(() -> service.request(ULD_SN, new PortalAugmentRequest(Map.of()), owner()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(assetRepository, never()).findByOwner(any(), anyString());
    }

    @Test
    @DisplayName("생성_조건이_컬럼_폭을_넘으면_400이다 — 적재_시점_오류로_새지_않는다")
    void oversizedConditionIsRejectedAtTheDoor() {
        Map<String, Object> huge = Map.of("note", "가".repeat(5000));

        assertThatThrownBy(() -> service.request(ULD_SN, new PortalAugmentRequest(huge), owner()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
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
        givenPage(aug(9001L, SRC_SN, "{\"season\":\"WINTER\"}", null));

        Page<PortalAugmentSummaryResponse> page =
                service.list(owner(), PageRequest.of(0, 20));

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
