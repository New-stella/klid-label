package kr.co.cudo.authoring.meta;

import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
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
 * {@code vlm.accuracy}(일치도) 를 <b>편집 대상 목록에서 분리</b>하는 회귀 가드 (Phase 5, @req R12).
 *
 * <h3>왜 필요한가</h3>
 * <p>verify 콜백 적재는 {@code vlm.description}(검수큐 진입·편집 가능) + {@code vlm.accuracy}
 * (검수큐 미진입·<b>화면 전용</b>) 2키 구조다. 그런데 조회 응답은 {@code video.*} 만 분리하고 나머지를 전부
 * {@code items} 에 담았고 {@code PUT} 거부 목록도 {@code video.*} 뿐이라, 읽기 전용이어야 할 일치도가
 * <b>편집 대상처럼 내려가고 실제 저장까지</b> 됐다.
 *
 * <h3>계약</h3>
 * <ul>
 *   <li>{@code items} — 편집 가능한 시계열 메타만({@code vlm.description} + 레거시 구간 키 +
 *       {@code manual-timeseries}). {@code vlm.accuracy} 는 <b>포함되지 않는다</b>.</li>
 *   <li>{@code readOnlyMeta} — 화면 전용 읽기 키. 버리지 않고 별도 필드로 여전히 조회된다.</li>
 *   <li>{@code technicalMeta} — {@code video.*} 기술메타(기존 계약 그대로).</li>
 *   <li>수정 경로는 읽기 전용 키를 {@code video.*} 와 <b>동일한 방식·에러코드</b>로 거부한다(400).</li>
 * </ul>
 *
 * <p>판정은 {@code MetaService} 가 단독 소유한다 — 접두·키 문자열을 DTO/FE 에 복제하면 소유자가 키를
 * 늘릴 때 조용히 드리프트한다.
 */
class MetaServiceReadOnlyMetaSeparationTest {

    private static final Long SRC_SN = 7001L;
    private static final Long RAW_SN = 8001L;
    private static final String ACTOR_SUB = "1001";

    /** FE 가 메타 0건 영상에서 신규 등록 슬롯으로 쓰는 키 — 계속 편집 가능해야 한다. */
    private static final String MANUAL_KEY = "manual-timeseries";
    /** 구 describe 구간 서술 키 — 보존 + 편집 가능 유지. */
    private static final String LEGACY_KEY = "0-8";

    private LsDataMetaRepository metaRepository;
    private LsDataSrcRepository srcRepository;
    private LsTaskAssignmentRepository authrtRepository;
    private LsDataMetaReviewRepository metaReviewRepository;
    private ApplicationEventPublisher eventPublisher;
    private LsRawDataStatusRepository rawDataStatusRepository;
    private MetaService service;

    @BeforeEach
    void setUp() {
        metaRepository = mock(LsDataMetaRepository.class);
        srcRepository = mock(LsDataSrcRepository.class);
        authrtRepository = mock(LsTaskAssignmentRepository.class);
        metaReviewRepository = mock(LsDataMetaReviewRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        rawDataStatusRepository = mock(LsRawDataStatusRepository.class);

        service = new MetaService(metaRepository, srcRepository, authrtRepository,
                metaReviewRepository, eventPublisher, rawDataStatusRepository);

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

    /** 전 키가 섞인 영상 — 분류 4갈래를 한 번에 관측한다. */
    private void givenMixedMeta() {
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(
                metaWithSn(VlmResultService.META_KEY_DESCRIPTION, "차량이 진입한다", 501L),
                metaWithSn(VlmResultService.META_KEY_ACCURACY, "0.8", 502L),
                metaWithSn(LEGACY_KEY, "구 구간 서술", 503L),
                metaWithSn(MANUAL_KEY, "수동 등록", 504L),
                metaWithSn("video.fps", "30", 505L)));
    }

    // ───────── 조회 분리 ─────────

    @Test
    @DisplayName("accuracy는_items가_아니라_별도_필드로_내려간다")
    void accuracy_별도필드로_분리() {
        // given
        givenMixedMeta();

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then — 편집 목록에서 빠지고, 버려지지도 않는다(값과 함께 읽기 전용 목록으로).
        assertThat(res.items()).extracting(MetaResponse.Item::metaKey)
                .doesNotContain(VlmResultService.META_KEY_ACCURACY);
        assertThat(res.readOnlyMeta()).extracting(MetaResponse.Item::metaKey)
                .containsExactly(VlmResultService.META_KEY_ACCURACY);
        assertThat(res.readOnlyMeta()).extracting(MetaResponse.Item::metaVal)
                .containsExactly("0.8");
        assertThat(res.readOnlyMeta()).extracting(MetaResponse.Item::metaSn)
                .containsExactly(502L);
    }

    @Test
    @DisplayName("description은_items에_남는다")
    void description은_편집목록에_남는다() {
        // given
        givenMixedMeta();

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then — 검수 대상이자 편집 가능 키다(과차단 금지).
        assertThat(res.items()).extracting(MetaResponse.Item::metaKey)
                .contains(VlmResultService.META_KEY_DESCRIPTION);
    }

    @Test
    @DisplayName("레거시_구간키는_items에_그대로_남는다")
    void 레거시_구간키_보존() {
        // given
        givenMixedMeta();

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then — 구 describe 구간 서술은 삭제·이동 대상이 아니다.
        assertThat(res.items()).extracting(MetaResponse.Item::metaKey)
                .contains(LEGACY_KEY, MANUAL_KEY);
    }

    @Test
    @DisplayName("기술메타는_기존처럼_technicalMeta로_분리된다")
    void 기술메타_기존계약_유지() {
        // given
        givenMixedMeta();

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then — 신규 분류가 기존 video.* 분리를 잠식하지 않는다.
        assertThat(res.technicalMeta()).extracting(MetaResponse.Item::metaKey)
                .containsExactly("video.fps");
        assertThat(res.items()).extracting(MetaResponse.Item::metaKey)
                .containsExactly(VlmResultService.META_KEY_DESCRIPTION, LEGACY_KEY, MANUAL_KEY);
    }

    @Test
    @DisplayName("미등록_vlm_읽기전용_키도_items에서_빠진다_failclosed")
    void 미래의_vlm키는_failclosed로_읽기전용() {
        // given — 아직 존재하지 않는 vlm.* 키가 적재된 경우(편집 화이트리스트 밖)
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(
                metaWithSn("vlm.confidence", "0.5", 601L)));

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then — 명시적으로 편집 허용한 vlm.* 만 items 로 간다.
        assertThat(res.items()).isEmpty();
        assertThat(res.readOnlyMeta()).extracting(MetaResponse.Item::metaKey)
                .containsExactly("vlm.confidence");
    }

    @Test
    @DisplayName("vlm으로_시작만_하는_유사키는_계속_편집가능하다_과차단_금지")
    void 유사키_과차단_금지() {
        // given — 'vlm' 으로 시작하지만 네임스페이스 접두('vlm.')가 아닌 키
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(
                metaWithSn("vlmnote-0-8", "설명", 602L)));

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then
        assertThat(res.items()).extracting(MetaResponse.Item::metaKey)
                .containsExactly("vlmnote-0-8");
        assertThat(res.readOnlyMeta()).isEmpty();
    }

    @Test
    @DisplayName("메타가_0건이면_모든_목록이_빈_배열이다")
    void 메타_0건_전목록_빈배열() {
        // given
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of());

        // when
        MetaResponse res = service.getByFrame(SRC_SN, reviewer());

        // then — FE 가 항상 배열로 다룰 수 있어야 한다(크래시 방지).
        assertThat(res.items()).isNotNull().isEmpty();
        assertThat(res.technicalMeta()).isNotNull().isEmpty();
        assertThat(res.readOnlyMeta()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("기존_응답_필드는_삭제되거나_타입이_바뀌지_않는다")
    void 하위호환_필드_고정() {
        // given / when — 응답 계약(레코드 컴포넌트)을 직접 관측한다.
        List<RecordComponent> components = List.of(MetaResponse.class.getRecordComponents());
        List<String> names = components.stream().map(RecordComponent::getName).toList();

        // then — 기존 2필드는 이름·타입 그대로 존재하고, 신규는 '추가' 뿐이다.
        assertThat(names).containsSubsequence("items", "technicalMeta");
        components.stream()
                .filter(c -> List.of("items", "technicalMeta", "readOnlyMeta").contains(c.getName()))
                .forEach(c -> assertThat(c.getType()).isEqualTo(List.class));

        // then — 항목 필드(FE 소비 계약)도 이름·타입 불변.
        List<RecordComponent> item = List.of(MetaResponse.Item.class.getRecordComponents());
        assertThat(item).extracting(RecordComponent::getName)
                .containsExactly("metaSn", "metaKey", "metaVal", "dataMetaReviewSn", "reviewStatus");
        assertThat(item).extracting(RecordComponent::getType)
                .containsExactly(Long.class, String.class, String.class, Long.class, String.class);
    }

    @Test
    @DisplayName("검토행_조회가_배치로_이뤄진다")
    void 검토행_배치조회_N플러스1_없음() {
        // given — 분류가 3갈래로 늘어도 검토행 조회는 1회여야 한다(쿼리 쪼개기 금지).
        givenMixedMeta();

        // when
        service.getByFrame(SRC_SN, reviewer());

        // then
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(metaReviewRepository, times(1)).findByDataMetaSnIn(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder(501L, 502L, 503L, 504L, 505L);
        verify(metaReviewRepository, never()).existsByDataMetaSn(anyLong());
    }

    // ───────── 저장 경로 차단 ─────────

    @Test
    @DisplayName("accuracy_키를_수정_요청하면_400")
    void accuracy_수정_거부() {
        // given
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item(VlmResultService.META_KEY_ACCURACY, "1.0")));

        // when / then — video.* 와 동일한 에러코드(새 예외 유형 없음), 저장·검토행 생성 모두 없음.
        assertThatThrownBy(() -> service.update(SRC_SN, req, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(metaRepository, never()).upsertMeta(anyLong(), anyString(), anyString());
        verify(metaReviewRepository, never()).save(any(LsDataMetaReview.class));
    }

    @Test
    @DisplayName("accuracy가_섞이면_같은요청의_편집가능키도_저장되지_않는다_부분저장없음")
    void accuracy_혼합시_전체_거부() {
        // given
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item(VlmResultService.META_KEY_DESCRIPTION, "정상 수정"),
                new MetaUpdateRequest.Item(VlmResultService.META_KEY_ACCURACY, "1.0")));

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
                new MetaUpdateRequest.Item(VlmResultService.META_KEY_ACCURACY, "1.0")));

        // when / then
        assertThatThrownBy(() -> service.update(SRC_SN, req, reviewer()))
                .isInstanceOf(CustomException.class)
                .hasMessageNotContaining(VlmResultService.META_KEY_ACCURACY)
                .hasMessageNotContaining("vlm.")
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("기술메타_키를_수정_요청하면_기존대로_400")
    void 기술메타_수정_거부_회귀() {
        // given
        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item("video.fps", "산문으로 덮어쓰기 시도")));

        // when / then
        assertThatThrownBy(() -> service.update(SRC_SN, req, reviewer()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
        verify(metaRepository, never()).upsertMeta(anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("레거시_구간키는_계속_수정할_수_있다")
    void 레거시_구간키_수정_허용() {
        // given
        LsDataMeta existing = metaWithSn(LEGACY_KEY, "구 서술", 503L);
        when(metaRepository.findByRawSnAndMetaKey(RAW_SN, LEGACY_KEY)).thenReturn(Optional.of(existing));
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(existing));

        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item(LEGACY_KEY, "수정본")));

        // when
        service.update(SRC_SN, req, reviewer());

        // then — fail-closed 를 이유로 화이트리스트를 좁혀 이들을 막으면 안 된다.
        verify(metaRepository).upsertMeta(RAW_SN, LEGACY_KEY, "수정본");
    }

    @Test
    @DisplayName("manual-timeseries_키는_계속_수정할_수_있다")
    void 수동등록_키_수정_허용() {
        // given — 메타 0건 영상의 신규 등록 슬롯(FE 계약)
        when(metaRepository.findByRawSnAndMetaKey(RAW_SN, MANUAL_KEY)).thenReturn(Optional.empty());
        LsDataMeta saved = metaWithSn(MANUAL_KEY, "신규 등록", 700L);
        when(metaRepository.findByRawSnAndMetaKey(RAW_SN, MANUAL_KEY))
                .thenReturn(Optional.empty(), Optional.of(saved));
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(saved));

        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item(MANUAL_KEY, "신규 등록")));

        // when
        service.update(SRC_SN, req, reviewer());

        // then
        verify(metaRepository).upsertMeta(RAW_SN, MANUAL_KEY, "신규 등록");
    }

    @Test
    @DisplayName("description_키는_계속_수정할_수_있다")
    void description_수정_허용() {
        // given
        LsDataMeta existing = metaWithSn(VlmResultService.META_KEY_DESCRIPTION, "원문", 501L);
        when(metaRepository.findByRawSnAndMetaKey(RAW_SN, VlmResultService.META_KEY_DESCRIPTION))
                .thenReturn(Optional.of(existing));
        when(metaRepository.findByRawSn(RAW_SN)).thenReturn(List.of(existing));

        MetaUpdateRequest req = new MetaUpdateRequest(List.of(
                new MetaUpdateRequest.Item(VlmResultService.META_KEY_DESCRIPTION, "검토 수정본")));

        // when
        service.update(SRC_SN, req, reviewer());

        // then — 검수 대상 서술은 편집 가능해야 한다.
        verify(metaRepository).upsertMeta(RAW_SN, VlmResultService.META_KEY_DESCRIPTION, "검토 수정본");
    }
}
