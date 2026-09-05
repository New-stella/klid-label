package kr.co.cudo.authoring.meta;

import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.meta.dto.MetaResponse;
import kr.co.cudo.authoring.meta.dto.MetaUpdateRequest;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;
import kr.co.cudo.authoring.meta.repository.LsDataMetaReviewRepository;
import kr.co.cudo.authoring.meta.service.MetaService;
import kr.co.cudo.authoring.transfer.ImportMetaKeys;
import kr.co.cudo.authoring.webhook.service.VlmResultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 이관 원문 메타({@code import.*})를 <b>시계열 메타 목록에서 분리</b>하는 회귀 가드. [design: API-066]
 *
 * <h3>왜 필요한가</h3>
 * <p>외부 산출물 이관은 저작도구 스키마에 착지할 컬럼이 없는 값(좌표·위치·카메라 설치 높이/방위/관리번호·
 * 데이터 출처·이벤트 기록·이벤트 상위 계층 이름·외부 영상 식별자·원천 축 개인정보 판정)을
 * {@code LS_DATA_META} 에 {@code import.} 접두로 원문 보관한다. 분류가 그것을 알지 못해 <b>여집합</b>으로
 * 떨어뜨려 {@code items}(시계열 메타)로 내려보내고 있었다. 그 결과 ①화면이 검토 대상이 아닌 값을 검토
 * 대상처럼 표시하고 ②학습데이터 산출물의 상황묘사 조달이 그 값을 서술로 집을 여지가 생긴다.
 *
 * <h3>계약</h3>
 * <ul>
 *   <li>{@code importedMeta} — 이관 원문만. 이관 영상이 아니면 <b>빈 배열</b>(null 아님).</li>
 *   <li>{@code items} — {@code import.*} 를 <b>담지 않는다</b>.</li>
 *   <li>{@code technicalMeta}·{@code readOnlyMeta} 및 {@code items} 의 나머지 소속은 <b>변경 전과 동일</b>.
 *       신규 분류가 기존 두 계약을 잠식하지 않는다.</li>
 *   <li><b>저장(PUT)</b> — 이관 원문 키는 {@code video.*}·읽기 전용 키와 <b>동일한 방식·에러코드</b>로
 *       거부한다(400). 한 항목이라도 걸리면 같은 요청의 편집 가능 키도 저장되지 않는다(부분 저장 없음).</li>
 * </ul>
 *
 * <p>판정은 {@code MetaService} 가 소유하되 <b>접두 문자열은 쓰는 쪽</b>({@link ImportMetaKeys#PREFIX})의
 * 상수를 참조한다 — 이 시험도 리터럴을 새로 적지 않고 같은 상수를 쓴다. 두 벌이 되면 한쪽만 바뀌었을 때
 * 시험이 결함을 통과시킨다.
 *
 * <p><b>저장 축이 조회 축과 세트인 이유</b>: 편집 불가 판정은 화이트리스트가 아니라 <b>블랙리스트</b>라
 * ({@code video.} 접두 · {@code vlm.} 접두 중 편집 화이트리스트 밖) 새 네임스페이스는 저장 경로에서
 * <b>아무 검사도 받지 않는다</b>. 조회만 고치면 "화면에는 편집 칸이 없는데 임의 payload 로는 덮어써지는"
 * 비대칭이 남고, 그 덮어쓰기는 외부가 준 원문을 지우는 것이라 <b>되돌릴 수단이 없다</b>.
 */
class MetaServiceImportedMetaSeparationTest {

    private static final Long SRC_SN = 7101L;
    private static final Long RAW_SN = 8101L;
    private static final String ACTOR_SUB = "1001";

    /** FE 가 메타 0건 영상에서 신규 등록 슬롯으로 쓰는 키 — 계속 편집 가능해야 한다. */
    private static final String MANUAL_KEY = "manual-timeseries";
    /** 구 describe 구간 서술 키 — 보존 + 편집 가능 유지. */
    private static final String LEGACY_KEY = "0-8";
    private static final String TECHNICAL_KEY = "video.fps";

    private LsDataMetaRepository metaRepository;
    private LsDataSrcRepository srcRepository;
    private LsDataMetaReviewRepository metaReviewRepository;
    private MetaService service;

    @BeforeEach
    void setUp() {
        metaRepository = mock(LsDataMetaRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        LsTaskAssignmentRepository authrtRepository = mock(LsTaskAssignmentRepository.class);
        metaReviewRepository = mock(LsDataMetaReviewRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        ReviewApprovalGate approvalGate = mock(ReviewApprovalGate.class);

        service = new MetaService(metaRepository, srcRepository, authrtRepository,
                metaReviewRepository, eventPublisher, approvalGate);

        LsDataSrc src = LsDataSrc.create(RAW_SN, 0, "/raw/f0.jpg", null);
        when(srcRepository.findById(SRC_SN)).thenReturn(Optional.of(src));
        when(metaReviewRepository.findByDataMetaSnIn(anyCollection())).thenReturn(List.of());
    }

    private TokenClaims reviewer() {
        return new TokenClaims(ACTOR_SUB, Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    private LsDataMeta metaWithSn(String key, String val, long metaSn) {
        LsDataMeta meta = LsDataMeta.create(RAW_SN, key, val);
        ReflectionTestUtils.setField(meta, "metaSn", metaSn);
        return meta;
    }

    /** 네 분류가 모두 섞인 영상 — 소속을 한 번에 관측한다. */
    private void givenMixedMeta() {
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(
                metaWithSn(VlmResultService.META_KEY_DESCRIPTION, "차량이 진입한다", 701L),
                metaWithSn(VlmResultService.META_KEY_ACCURACY, "0.8", 702L),
                metaWithSn(LEGACY_KEY, "구 구간 서술", 703L),
                metaWithSn(MANUAL_KEY, "수동 등록", 704L),
                metaWithSn(TECHNICAL_KEY, "30", 705L),
                metaWithSn(ImportMetaKeys.VIDEO_LOCATION, "○○시 ○○로", 706L),
                metaWithSn(ImportMetaKeys.VIDEO_COORDINATES, "37.1,127.1", 707L),
                metaWithSn(ImportMetaKeys.VIDEO_ANONYMITY, "Y", 708L)));
    }

    // ───────── AC1 — 이관 원문은 새 목록에만, items 에는 0건 ─────────

    @Test
    @DisplayName("이관원문은_items가_아니라_importedMeta로_내려간다")
    void 이관원문_별도목록_분리() {
        // given
        givenMixedMeta();

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then — 편집 목록에서 빠지고, 버려지지도 않는다(값·PK 와 함께 읽기 전용 목록으로).
        assertThat(res.items()).extracting(MetaResponse.Item::metaKey)
                .noneMatch(k -> k.startsWith(ImportMetaKeys.PREFIX));
        assertThat(res.importedMeta()).extracting(MetaResponse.Item::metaKey)
                .containsExactly(ImportMetaKeys.VIDEO_LOCATION, ImportMetaKeys.VIDEO_COORDINATES,
                        ImportMetaKeys.VIDEO_ANONYMITY);
        assertThat(res.importedMeta()).extracting(MetaResponse.Item::metaVal)
                .containsExactly("○○시 ○○로", "37.1,127.1", "Y");
        assertThat(res.importedMeta()).extracting(MetaResponse.Item::metaSn)
                .containsExactly(706L, 707L, 708L);
    }

    @Test
    @DisplayName("이관_접두로_시작만_하는_유사키는_계속_편집가능하다_과차단_금지")
    void 유사키_과차단_금지() {
        // given — 'import' 로 시작하지만 네임스페이스 접두('import.')가 아닌 키
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(
                metaWithSn("imported-note-0-8", "설명", 709L)));

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then
        assertThat(res.items()).extracting(MetaResponse.Item::metaKey)
                .containsExactly("imported-note-0-8");
        assertThat(res.importedMeta()).isEmpty();
    }

    // ───────── AC2 — 나머지 키의 소속이 변경 전과 동일 ─────────

    @Test
    @DisplayName("기존_세_목록의_소속이_변경전과_동일하다")
    void 기존_분류_불변() {
        // given
        givenMixedMeta();

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then — 신규 분류가 기존 계약을 잠식하지 않는다.
        assertThat(res.technicalMeta()).extracting(MetaResponse.Item::metaKey)
                .containsExactly(TECHNICAL_KEY);
        assertThat(res.readOnlyMeta()).extracting(MetaResponse.Item::metaKey)
                .containsExactly(VlmResultService.META_KEY_ACCURACY);
        assertThat(res.items()).extracting(MetaResponse.Item::metaKey)
                .containsExactly(VlmResultService.META_KEY_DESCRIPTION, LEGACY_KEY, MANUAL_KEY);
    }

    // ───────── AC4 — 이관 영상이 아니면 빈 배열(null 아님) ─────────

    @Test
    @DisplayName("이관원문이_없는_영상에서_importedMeta는_null이_아니라_빈배열이다")
    void 이관없는_영상_빈배열() {
        // given — 대다수 영상이 이 모양이다.
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(
                metaWithSn(VlmResultService.META_KEY_DESCRIPTION, "차량이 진입한다", 710L)));

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then — 화면이 분기 없이 그린다(크래시 방지).
        assertThat(res.importedMeta()).isNotNull().isEmpty();
        assertThat(res.items()).extracting(MetaResponse.Item::metaKey)
                .containsExactly(VlmResultService.META_KEY_DESCRIPTION);
    }

    @Test
    @DisplayName("메타가_0건이면_네_목록_모두_빈_배열이다")
    void 메타_0건_전목록_빈배열() {
        // given
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of());

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then
        assertThat(res.items()).isNotNull().isEmpty();
        assertThat(res.technicalMeta()).isNotNull().isEmpty();
        assertThat(res.readOnlyMeta()).isNotNull().isEmpty();
        assertThat(res.importedMeta()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("이관원문만_있는_영상에서도_값이_유실되지_않는다")
    void 이관원문만_있는_영상() {
        // given — 화면 빈 상태 판정이 이 목록을 세지 않으면 "표시할 메타 정보가 없습니다"가 거짓말이 된다.
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(
                metaWithSn(ImportMetaKeys.VIDEO_CCTV_MNG_NO, "CAM-001", 711L)));

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then
        assertThat(res.items()).isEmpty();
        assertThat(res.importedMeta()).extracting(MetaResponse.Item::metaKey)
                .containsExactly(ImportMetaKeys.VIDEO_CCTV_MNG_NO);
    }

    // ───────── 저장 경로 차단 (AC3) ─────────

    @Test
    @DisplayName("이관원문_키를_수정_요청하면_400")
    void 이관원문_수정_거부() {
        // given
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item(ImportMetaKeys.VIDEO_LOCATION, "덮어쓴 값")));

        // when / then — video.*·읽기 전용과 동일한 에러코드(새 예외 유형 없음),
        //               저장·검토행 생성 모두 없음(외부가 준 원문이 지워지지 않는다).
        assertThatThrownBy(() -> service.update(SRC_SN, req, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(metaRepository, never()).upsertMeta(anyLong(), anyString(), anyString());
        verify(metaReviewRepository, never()).save(any(LsDataMetaReview.class));
    }

    @Test
    @DisplayName("이관원문이_섞이면_같은요청의_편집가능키도_저장되지_않는다_부분저장없음")
    void 이관원문_혼합시_전체_거부() {
        // given — 검증은 첫 upsert '이전'에 전체 항목을 한 번에 훑는다.
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item(VlmResultService.META_KEY_DESCRIPTION, "정상 수정"),
                new MetaUpdateRequest.Item(ImportMetaKeys.VIDEO_COORDINATES, "0,0")));

        // when / then
        assertThatThrownBy(() -> service.update(SRC_SN, req, reviewer()))
                .isInstanceOf(CustomException.class);
        verify(metaRepository, never()).upsertMeta(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("거부_메시지에_요청받은_키_문자열이_노출되지_않는다")
    void 거부메시지_키_미노출() {
        // given — 개행이 섞인 키를 echo 하면 로그 위조가 된다(CWE-117/209).
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item(ImportMetaKeys.VIDEO_CCTV_AZIMUTH, "0")));

        // when / then
        assertThatThrownBy(() -> service.update(SRC_SN, req, reviewer()))
                .isInstanceOf(CustomException.class)
                .hasMessageNotContaining(ImportMetaKeys.VIDEO_CCTV_AZIMUTH)
                .hasMessageNotContaining(ImportMetaKeys.PREFIX)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ───────── 하위호환·성능 계약 ─────────

    @Test
    @DisplayName("기존_응답_필드는_삭제되거나_타입이_바뀌지_않는다")
    void 하위호환_필드_고정() {
        // given / when — 응답 계약(레코드 컴포넌트)을 직접 관측한다.
        List<RecordComponent> components = List.of(MetaResponse.class.getRecordComponents());
        List<String> names = components.stream().map(RecordComponent::getName).toList();

        // then — 기존 3필드는 이름·타입 그대로 존재하고, 신규는 '추가' 뿐이다.
        assertThat(names).containsSubsequence("items", "technicalMeta", "readOnlyMeta");
        assertThat(names).contains("importedMeta");
        components.stream()
                .filter(c -> List.of("items", "technicalMeta", "readOnlyMeta", "importedMeta")
                        .contains(c.getName()))
                .forEach(c -> assertThat(c.getType()).isEqualTo(List.class));

        // then — 항목 필드(FE 소비 계약)는 네 목록이 공유하며 이름·타입 불변.
        List<RecordComponent> item = List.of(MetaResponse.Item.class.getRecordComponents());
        assertThat(item).extracting(RecordComponent::getName)
                .containsExactly("metaSn", "metaKey", "metaVal", "dataMetaReviewSn", "reviewStatus");
        assertThat(item).extracting(RecordComponent::getType)
                .containsExactly(Long.class, String.class, String.class, Long.class, String.class);
    }

    @Test
    @DisplayName("분류가_네갈래로_늘어도_검토행_조회는_1회다_N플러스1_없음")
    void 검토행_배치조회_유지() {
        // given — 배치 조회 대상은 '분류 전 전체 metaSn' 이다(왕복을 쪼개지 않는다).
        givenMixedMeta();

        // when
        service.getByFrame(SRC_SN, reviewer());

        // then
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(metaReviewRepository, times(1)).findByDataMetaSnIn(captor.capture());
        assertThat(captor.getValue())
                .containsExactlyInAnyOrder(701L, 702L, 703L, 704L, 705L, 706L, 707L, 708L);
        verify(metaReviewRepository, never()).existsByDataMetaSn(anyLong());
    }
}
