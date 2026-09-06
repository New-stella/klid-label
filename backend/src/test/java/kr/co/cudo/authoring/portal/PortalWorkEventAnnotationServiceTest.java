package kr.co.cudo.authoring.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnno;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoRepository;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.portal.dto.PortalEventAnnotationResponse;
import kr.co.cudo.authoring.portal.dto.PortalEventAnnotationUpdateRequest;
import kr.co.cudo.authoring.portal.entity.LsPortalUserEvntAnno;
import kr.co.cudo.authoring.portal.repository.LsPortalUserEvntAnnoRepository;
import kr.co.cudo.authoring.portal.service.PortalWorkEventAnnotationService;
import kr.co.cudo.authoring.portal.service.PortalWorkTargetResolver;
import kr.co.cudo.authoring.portal.service.PortalWorkTargetResolver.Origin;
import kr.co.cudo.authoring.portal.service.PortalWorkTargetResolver.Target;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 포털 이벤트 어노테이션 Load·저장 — 저장처 갈림과 원본 불변을 고정한다.
 *
 * @design API-236, API-237, ERD-018
 */
class PortalWorkEventAnnotationServiceTest {

    private static final String ALICE = "alice";
    private static final long RAW_SN = 10L;

    private PortalWorkTargetResolver targetResolver;
    private LsEvntAnnoRepository evntAnnoRepository;
    private LsPortalUserEvntAnnoRepository overlayRepository;
    private PortalUploadAssetRepository assetRepository;
    private LabelAccessGuard accessGuard;
    private PortalWorkEventAnnotationService service;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        targetResolver = mock(PortalWorkTargetResolver.class);
        evntAnnoRepository = mock(LsEvntAnnoRepository.class);
        overlayRepository = mock(LsPortalUserEvntAnnoRepository.class);
        assetRepository = mock(PortalUploadAssetRepository.class);
        accessGuard = mock(LabelAccessGuard.class);
        service = new PortalWorkEventAnnotationService(targetResolver, evntAnnoRepository,
                overlayRepository, assetRepository, accessGuard, objectMapper);
        // 원장 저장 창구는 「반영된 행 수」를 돌려준다 — 0 은 <판별자가 막았다>는 뜻이라 서비스가
        //   거부한다. 정상 경로 시험은 1 을 기본값으로 둔다(0 을 겨누는 시험만 따로 덮어쓴다).
        when(assetRepository.upsertOwnedEventAnnotation(anyLong(), anyString(), anyString()))
                .thenReturn(1);
    }

    private void givenOrigin(Origin origin) {
        when(targetResolver.resolveByVideo(RAW_SN, ALICE)).thenReturn(new Target(RAW_SN, null, origin));
    }

    private static LsEvntAnno ledgerAnno(String json) {
        return LsEvntAnno.create(RAW_SN, json, null);
    }

    private static LsPortalUserEvntAnno overlayAnno(String json) {
        LsPortalUserEvntAnno mine = newInstance(LsPortalUserEvntAnno.class);
        set(mine, "portalUserNo", ALICE);
        set(mine, "srcRawSn", RAW_SN);
        set(mine, "annoCn", json);
        return mine;
    }

    private static PortalEventAnnotationUpdateRequest request(Map<String, Object> body) {
        return new PortalEventAnnotationUpdateRequest(body);
    }

    private static Map<String, Object> body() {
        Map<String, Object> cot = new LinkedHashMap<>();
        cot.put("1단계", "상황 서술");
        Map<String, Object> c1 = new LinkedHashMap<>();
        c1.put("cot", cot);
        c1.put("caption_text", "캡션");
        Map<String, Object> caption = new LinkedHashMap<>();
        caption.put("c1", c1);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("caption", caption);
        return root;
    }

    // ================================================================ Load

    @Test
    @DisplayName("데이터마트_자산은_본인_오버레이가_원본을_가리고_가림_표시가_선다")
    void overlayWinsAndIsMarkedOverridden() {
        givenOrigin(Origin.DATAMART);
        when(evntAnnoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.of(ledgerAnno("{\"a\":1}")));
        when(overlayRepository.findByPortalUserNoAndSrcRawSn(ALICE, RAW_SN))
                .thenReturn(Optional.of(overlayAnno("{\"a\":2}")));

        PortalEventAnnotationResponse response = service.load(RAW_SN, ALICE);

        assertThat(response.annotation()).containsEntry("a", 2);
        assertThat(response.overridden()).isTrue();
    }

    @Test
    @DisplayName("본인_저장분이_없으면_원본을_그대로_내려주고_가림_표시가_서지_않는다")
    void sourceIsReturnedWhenNoOverlay() {
        givenOrigin(Origin.DATAMART);
        when(evntAnnoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.of(ledgerAnno("{\"a\":1}")));
        when(overlayRepository.findByPortalUserNoAndSrcRawSn(ALICE, RAW_SN)).thenReturn(Optional.empty());

        PortalEventAnnotationResponse response = service.load(RAW_SN, ALICE);

        assertThat(response.annotation()).containsEntry("a", 1);
        assertThat(response.overridden()).isFalse();
    }

    @Test
    @DisplayName("원본이_없던_자리에_새로_쓴_값에는_가림_표시가_서지_않는다")
    void addedAnnotationIsNotOverridden() {
        givenOrigin(Origin.DATAMART);
        when(evntAnnoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.empty());
        when(overlayRepository.findByPortalUserNoAndSrcRawSn(ALICE, RAW_SN))
                .thenReturn(Optional.of(overlayAnno("{\"a\":2}")));

        assertThat(service.load(RAW_SN, ALICE).overridden()).isFalse();
    }

    @Test
    @DisplayName("★본인_업로드_자산은_오버레이_저장소를_아예_건드리지_않는다")
    void uploadOriginNeverTouchesOverlayOnRead() {
        givenOrigin(Origin.PORTAL_UPLOAD);
        when(evntAnnoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.of(ledgerAnno("{\"a\":1}")));

        PortalEventAnnotationResponse response = service.load(RAW_SN, ALICE);

        verifyNoInteractions(overlayRepository);
        assertThat(response.annotation()).containsEntry("a", 1);
        assertThat(response.overridden()).isFalse();
    }

    @Test
    @DisplayName("값이_없으면_빈_객체가_아니라_null로_내려간다")
    void absentAnnotationIsNull() {
        givenOrigin(Origin.DATAMART);
        when(evntAnnoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.empty());
        when(overlayRepository.findByPortalUserNoAndSrcRawSn(ALICE, RAW_SN)).thenReturn(Optional.empty());

        assertThat(service.load(RAW_SN, ALICE).annotation()).isNull();
    }

    @Test
    @DisplayName("보관된_원문이_객체가_아니어도_조회는_열린다_값_없음으로_내린다")
    void corruptedStoredAnnotationDoesNotBreakLoad() {
        givenOrigin(Origin.DATAMART);
        when(evntAnnoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.of(ledgerAnno("[1,2,3]")));
        when(overlayRepository.findByPortalUserNoAndSrcRawSn(ALICE, RAW_SN)).thenReturn(Optional.empty());

        assertThat(service.load(RAW_SN, ALICE).annotation()).isNull();
    }

    // ================================================================ 저장

    /** ★★ 원본 원장에 쓰게 만들면 죽는다 — 그것이 곧 확정 학습데이터를 조용히 바꾸는 지점이다. */
    @Test
    @DisplayName("★데이터마트_자산은_원본_원장에_쓰지_않고_오버레이에만_적재된다")
    void datamartWritesOverlayOnly() {
        givenOrigin(Origin.DATAMART);
        when(evntAnnoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.empty());
        when(overlayRepository.findByPortalUserNoAndSrcRawSn(ALICE, RAW_SN)).thenReturn(Optional.empty());

        service.save(RAW_SN, ALICE, request(body()));

        verify(overlayRepository).upsertAnnotation(eq(ALICE), eq(RAW_SN), anyString());
        verify(evntAnnoRepository, never()).save(any());
        verify(assetRepository, never()).upsertOwnedEventAnnotation(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("★본인_업로드_자산은_오버레이가_아니라_그_자산의_원장에_적재된다")
    void uploadWritesLedger() {
        givenOrigin(Origin.PORTAL_UPLOAD);
        when(evntAnnoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.empty());

        service.save(RAW_SN, ALICE, request(body()));

        verify(assetRepository).upsertOwnedEventAnnotation(eq(RAW_SN), eq(ALICE), anyString());
        verify(overlayRepository, never()).upsertAnnotation(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("본문_구조체는_중첩을_유지한_채_그대로_직렬화된다_키별로_펴지_않는다")
    void nestedStructureIsPreserved() {
        givenOrigin(Origin.DATAMART);
        when(evntAnnoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.empty());
        when(overlayRepository.findByPortalUserNoAndSrcRawSn(ALICE, RAW_SN)).thenReturn(Optional.empty());

        service.save(RAW_SN, ALICE, request(body()));

        org.mockito.ArgumentCaptor<String> json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(overlayRepository).upsertAnnotation(anyString(), anyLong(), json.capture());
        assertThat(json.getValue()).contains("\"caption\"").contains("\"c1\"").contains("\"1단계\"");
    }

    @Test
    @DisplayName("본문이_없으면_400이다")
    void nullAnnotationIsRejected() {
        givenOrigin(Origin.DATAMART);

        assertThatThrownBy(() -> service.save(RAW_SN, ALICE, request(null)))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

        verify(overlayRepository, never()).upsertAnnotation(anyString(), anyLong(), anyString());
    }

    /**
     * ★ 포털에는 검수가 없다. 검토행을 만들면 검수 큐와 데이터마트 뷰에 포털 값이 흘러들고,
     * 이벤트를 발행하면 관제 통지·산출물 재생성이 걸린다.
     */
    @Test
    @DisplayName("★이_서비스는_검토_저장소도_이벤트_발행자도_갖지_않는다")
    void serviceHasNoReviewOrEventDependency() {
        assertThat(PortalWorkEventAnnotationService.class.getDeclaredFields())
                .extracting(f -> f.getType().getName())
                .noneMatch(n -> n.contains("ApplicationEventPublisher") || n.contains("EvntAnnoReview"));
    }


    // ================================================================ 비식별 누락 신고 게이트

    /** 형제 창구와 <b>같은 판정기·같은 코드</b>를 쓴다 — 다르면 응답이 영상 상태를 알려 주는 단서가 된다. */
    @Test
    @DisplayName("★신고_구간_데이터마트_자산은_저장이_412로_거부된다_저장만_막고_우회가_성립하지_않는다")
    void saveIsBlockedWhileDeidentReportIsOpen() {
        givenOrigin(Origin.DATAMART);
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.PRECONDITION_FAILED, "blocked"))
                .when(accessGuard).requireNotUnderDeidentReport(RAW_SN);

        assertThatThrownBy(() -> service.save(RAW_SN, ALICE, request(body())))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRECONDITION_FAILED);

        verify(overlayRepository, never()).upsertAnnotation(anyString(), anyLong(), anyString());
        verify(assetRepository, never()).upsertOwnedEventAnnotation(anyLong(), anyString(), anyString());
    }

    /**
     * ★ 게이트는 <b>직렬화(400)보다 먼저</b> 평가된다 — 순서가 뒤집히면 본문이 잘못된 요청이
     * 신고 구간에서도 400 을 받아 <b>응답 코드가 영상 상태를 알려 주는 오라클</b>이 된다.
     */
    @Test
    @DisplayName("★신고_구간에서는_본문이_없어도_400이_아니라_412다_게이트가_직렬화보다_먼저다")
    void gateIsEvaluatedBeforeBodyValidation() {
        givenOrigin(Origin.DATAMART);
        org.mockito.Mockito.doThrow(new CustomException(ErrorCode.PRECONDITION_FAILED, "blocked"))
                .when(accessGuard).requireNotUnderDeidentReport(RAW_SN);

        assertThatThrownBy(() -> service.save(RAW_SN, ALICE, request(null)))
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PRECONDITION_FAILED);
    }

    @Test
    @DisplayName("★조회는_신고_구간에서도_열린다_조회_차단_범위는_개인정보_위치를_특정하는_산출물뿐이다")
    void loadIsNotGated() {
        givenOrigin(Origin.DATAMART);
        when(evntAnnoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.of(ledgerAnno("{\"a\":1}")));
        when(overlayRepository.findByPortalUserNoAndSrcRawSn(ALICE, RAW_SN)).thenReturn(Optional.empty());

        assertThat(service.load(RAW_SN, ALICE).annotation()).containsEntry("a", 1);

        verifyNoInteractions(accessGuard);
    }

    @Test
    @DisplayName("★본인_업로드_자산은_신고_게이트의_대상이_아니다_비식별_라이프사이클이_없다")
    void uploadAssetIsNotGated() {
        givenOrigin(Origin.PORTAL_UPLOAD);
        when(evntAnnoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.empty());

        service.save(RAW_SN, ALICE, request(body()));

        verifyNoInteractions(accessGuard);
        verify(assetRepository).upsertOwnedEventAnnotation(eq(RAW_SN), eq(ALICE), anyString());
    }

    // ================================================================ 무변경 저장

    /**
     * ★ 무변경 저장이 오버레이를 만들면 그 영상이 <b>저작물을 보유한 행</b>이 되어 본인 작업 목록에
     * 등재되고 보존기간 기산점까지 선다 — 열어 보기만 한 영상에 만료 시계가 도는 것은 틀린 동작이다.
     */
    @Test
    @DisplayName("★원본과_같은_본문을_저장하면_오버레이를_만들지_않는다_등재도_기산도_일어나지_않는다")
    void unchangedSaveDoesNotCreateOverlay() {
        givenOrigin(Origin.DATAMART);
        String source = serialized(body());
        when(evntAnnoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.of(ledgerAnno(source)));
        when(overlayRepository.findByPortalUserNoAndSrcRawSn(ALICE, RAW_SN)).thenReturn(Optional.empty());

        service.save(RAW_SN, ALICE, request(body()));

        verify(overlayRepository, never()).upsertAnnotation(anyString(), anyLong(), anyString());
        verify(overlayRepository).deleteOverlay(ALICE, RAW_SN);
    }

    /**
     * ★ 키 순서만 다른 <b>같은 내용</b>도 같은 것으로 판정한다 — 문자열로 견주면 화면이 순서를 바꿔
     * 되돌려 보내는 것만으로 오버레이가 생겨 만료 시계가 돈다.
     */
    @Test
    @DisplayName("★키_순서만_다른_같은_내용도_무변경으로_판정된다_문자열_비교_금지")
    void keyOrderDoesNotMakeItLookChanged() {
        givenOrigin(Origin.DATAMART);
        // 원본은 b,a 순 — 받은 본문은 a,b 순. 내용은 같다.
        when(evntAnnoRepository.findByRawSn(RAW_SN))
                .thenReturn(Optional.of(ledgerAnno("{\"b\":2,\"a\":1}")));
        when(overlayRepository.findByPortalUserNoAndSrcRawSn(ALICE, RAW_SN)).thenReturn(Optional.empty());
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("a", 1);
        reordered.put("b", 2);

        service.save(RAW_SN, ALICE, request(reordered));

        verify(overlayRepository, never()).upsertAnnotation(anyString(), anyLong(), anyString());
    }

    @Test
    @DisplayName("★이미_쌓인_오버레이는_원본과_같은_본문을_저장하면_지워져_원본으로_되돌아간다")
    void unchangedSaveClearsExistingOverlay() {
        givenOrigin(Origin.DATAMART);
        String source = serialized(body());
        when(evntAnnoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.of(ledgerAnno(source)));
        // 저장 직후 재조회는 <지워진 뒤>의 상태다 — mock 이 실제로 지우지 않으므로 그 상태를 준다.
        when(overlayRepository.findByPortalUserNoAndSrcRawSn(ALICE, RAW_SN)).thenReturn(Optional.empty());
        when(overlayRepository.deleteOverlay(ALICE, RAW_SN)).thenReturn(1);

        PortalEventAnnotationResponse response = service.save(RAW_SN, ALICE, request(body()));

        // 지우는 문장을 실제로 부르는가가 이 시험의 요지다(원본으로 되돌린다).
        verify(overlayRepository).deleteOverlay(ALICE, RAW_SN);
        verify(overlayRepository, never()).upsertAnnotation(anyString(), anyLong(), anyString());
        assertThat(response.overridden()).isFalse();
        assertThat(response.annotation()).containsKey("caption");
    }

    @Test
    @DisplayName("내용이_한_칸이라도_다르면_오버레이가_만들어진다_무변경_판정이_과하게_넓지_않다")
    void changedSaveStillCreatesOverlay() {
        givenOrigin(Origin.DATAMART);
        when(evntAnnoRepository.findByRawSn(RAW_SN))
                .thenReturn(Optional.of(ledgerAnno("{\"a\":1}")));
        when(overlayRepository.findByPortalUserNoAndSrcRawSn(ALICE, RAW_SN)).thenReturn(Optional.empty());
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("a", 2);

        service.save(RAW_SN, ALICE, request(changed));

        verify(overlayRepository).upsertAnnotation(eq(ALICE), eq(RAW_SN), anyString());
        verify(overlayRepository, never()).deleteOverlay(anyString(), anyLong());
    }

    /**
     * ★ 원본이 손상돼 비교가 성립하지 않으면 <b>같다고 단정하지 않는다</b> — 같다고 보면 사용자가
     * 쓴 값을 저장하지 않고 이미 있던 오버레이까지 지운다.
     */
    @Test
    @DisplayName("★원본이_손상돼_비교가_안_되면_저장하는_쪽으로_기운다_사용자_값을_잃지_않는다")
    void corruptedSourceFallsBackToSaving() {
        givenOrigin(Origin.DATAMART);
        when(evntAnnoRepository.findByRawSn(RAW_SN)).thenReturn(Optional.of(ledgerAnno("{not json")));
        when(overlayRepository.findByPortalUserNoAndSrcRawSn(ALICE, RAW_SN)).thenReturn(Optional.empty());

        service.save(RAW_SN, ALICE, request(body()));

        verify(overlayRepository).upsertAnnotation(eq(ALICE), eq(RAW_SN), anyString());
        verify(overlayRepository, never()).deleteOverlay(anyString(), anyLong());
    }

    /**
     * ★ 본인 업로드 자산에는 오버레이가 없다 — 무변경 판정으로 <b>저장을 건너뛰지 않는다</b>.
     * 건너뛰면 그 자산의 원장(= 원본 그 자체)이 갱신되지 않아 저장 시각이 서지 않는다.
     */
    @Test
    @DisplayName("★본인_업로드_자산은_무변경이어도_그_자산의_원장에_그대로_저장된다")
    void uploadAssetIsNotSubjectToUnchangedSkip() {
        givenOrigin(Origin.PORTAL_UPLOAD);
        when(evntAnnoRepository.findByRawSn(RAW_SN))
                .thenReturn(Optional.of(ledgerAnno(serialized(body()))));

        service.save(RAW_SN, ALICE, request(body()));

        verify(assetRepository).upsertOwnedEventAnnotation(eq(RAW_SN), eq(ALICE), anyString());
        verifyNoInteractions(overlayRepository);
    }

    private String serialized(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
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

    /**
     * ★★ 판별자가 막아도 <b>조용히 성공하지 않는다</b>.
     *
     * <p>원장 저장 창구가 돌려주는 0 은 <b>「이 사용자의 포털 자산이 아니다」</b>라는 뜻이며, 실행문에
     * 걸어 둔 <b>두 번째 방어선</b>이 발동한 신호다. 그 값을 버리면 아무것도 쓰지 않고 200 이 나가
     * 방어선이 물었다는 사실을 아무도 모른다. 도달 경로는 앞선 대상 판정이 막고 있으나, 이 시험이
     * 고정하는 것은 <b>발동했을 때의 관측 가능성</b>이다.
     */
    @Test
    @DisplayName("★원장_어노테이션_적재가_0행이면_조용한_성공이_아니라_거부다")
    void zeroRowOwnedAnnotationWriteIsRejectedNotSilentlyAccepted() {
        givenOrigin(Origin.PORTAL_UPLOAD);
        when(assetRepository.upsertOwnedEventAnnotation(anyLong(), anyString(), anyString()))
                .thenReturn(0);

        assertThatThrownBy(() -> service.save(RAW_SN, ALICE, request(body())))
                .isInstanceOf(CustomException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.FORBIDDEN);
    }
}
