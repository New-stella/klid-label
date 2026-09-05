package kr.co.cudo.authoring.eventtype.service;

import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService;
import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService.AnnotationToggle;
import kr.co.cudo.authoring.batch.policy.PresetResolution;
import kr.co.cudo.authoring.batch.policy.PresetResolutionStatus;
import kr.co.cudo.authoring.eventtype.dto.EventTypeAdminResponse;
import kr.co.cudo.authoring.eventtype.dto.EventTypeUpdateRequest;
import kr.co.cudo.authoring.eventtype.dto.PresetLinkStatus;
import kr.co.cudo.authoring.eventtype.dto.PresetLinkStatusFilter;
import kr.co.cudo.authoring.eventtype.entity.LsEvntType;
import kr.co.cudo.authoring.eventtype.repository.LsEvntTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 이벤트유형 관리 목록의 <b>프리셋 연결 상태</b> 노출·거르기를 고정한다.
 * [@design API-185] [@design ADR-054] [@design AC-116]
 *
 * <h3>이 시험이 무엇을 지키는가</h3>
 * <ol>
 *   <li><b>사유 여섯 → 상태 넷</b> 대응. 특히 "라벨을 안 담은 프리셋(선언)"과 "라벨은 담았는데 전부
 *       매핑 없음(사고)"이 같은 상태로 뭉개지면, 사고가 조치 목록에서 사라진다.</li>
 *   <li><b>그룹 축</b>. 프리셋은 그룹 대표코드에 걸리므로 비대표 코드 행도 대표코드의 프리셋을
 *       반영해야 한다 — 코드 단위로 판정하면 그룹의 비대표 유형이 전부 미연결로 보인다.</li>
 *   <li><b>{@code WITHHELD} 의 경계</b>. 제외 선언은 보류를 유발하지 않으므로 들어가면 안 된다.</li>
 *   <li><b>N+1 회피</b>. 같은 그룹은 해석을 한 번만 한다.</li>
 * </ol>
 *
 * <p>엔티티에 공개 팩토리가 없어(등록은 네이티브 upsert 경로다) mock 으로 만든다 —
 * {@code EventTypeAdminDisplayNameSourceTest} 와 동일한 기법이다.
 */
class EventTypeAdminPresetLinkStatusTest {

    private LsEvntTypeRepository repository;
    private EventTypeService eventTypeService;
    private PresetLabelLookupService presetLookup;
    private EventTypeAdminService service;

    @BeforeEach
    void setUp() {
        repository = mock(LsEvntTypeRepository.class);
        eventTypeService = mock(EventTypeService.class);
        presetLookup = mock(PresetLabelLookupService.class);
        EventTypeCacheEvictor evictor = mock(EventTypeCacheEvictor.class);
        service = new EventTypeAdminService(repository, evictor, eventTypeService, presetLookup);
        lenient().when(eventTypeService.categoryNameIndex()).thenReturn(Map.of());
    }

    // ---------------------------------------------------------------- 픽스처

    /** 등록 유형 1건(표시명 후보 없음 — 이 시험의 관심사는 연결 상태뿐이다). */
    private static LsEvntType type(String code) {
        LsEvntType t = mock(LsEvntType.class);
        lenient().when(t.getEvntTypeCd()).thenReturn(code);
        lenient().when(t.getClctYn()).thenReturn("Y");
        return t;
    }

    /** 실효 해석 결과 — 토글이 1건 이상이어야 계약을 만족한다. */
    private static PresetResolution resolved() {
        return PresetResolution.resolved(Map.of("person", new AnnotationToggle(true, false)));
    }

    /**
     * 유형 1건 + 그 유형의 해석 사유를 심는다. 스텁 대상 mock 을 <b>지역변수에 먼저 담고</b> 그다음
     * 스텁한다 — {@code when(...).thenReturn(헬퍼호출())} 안에서 다시 {@code when} 을 부르면
     * {@code UnfinishedStubbingException} 이 난다.
     */
    private void given(String code, PresetResolutionStatus status) {
        LsEvntType entity = type(code);
        when(repository.findAll()).thenReturn(List.of(entity));
        when(eventTypeService.filterKeyOf(code)).thenReturn(Optional.of(code));
        when(presetLookup.resolve(code)).thenReturn(resolutionOf(status));
    }

    private static PresetResolution resolutionOf(PresetResolutionStatus status) {
        return status == PresetResolutionStatus.RESOLVED ? resolved() : PresetResolution.of(status);
    }

    private PresetLinkStatus onlyRowStatus() {
        List<EventTypeAdminResponse> rows = service.list(null);
        assertThat(rows).hasSize(1);
        return rows.get(0).presetLinkStatus();
    }

    // ---------------------------------------------------------------- 사유 → 상태 대응

    @Test
    @DisplayName("실효하는_프리셋이_있으면_LINKED다")
    void resolvedIsLinked() {
        given("EV01000101", PresetResolutionStatus.RESOLVED);

        assertThat(onlyRowStatus()).isEqualTo(PresetLinkStatus.LINKED);
    }

    @Test
    @DisplayName("라벨을_하나도_담지_않은_프리셋은_LINKED_EXCLUDED다_오토라벨_제외_선언")
    void presetEmptyIsExcluded() {
        given("EV01000101", PresetResolutionStatus.PRESET_EMPTY);

        // 사람이 일부러 뺀 것이라 아래 미매핑(사고)과 반드시 갈라져야 한다.
        assertThat(onlyRowStatus()).isEqualTo(PresetLinkStatus.LINKED_EXCLUDED);
    }

    @Test
    @DisplayName("담긴_라벨이_마스터에_연결되지_않았으면_LINKED_INEFFECTIVE다")
    void presetUnlinkedIsIneffective() {
        given("EV01000101", PresetResolutionStatus.PRESET_UNLINKED);

        assertThat(onlyRowStatus()).isEqualTo(PresetLinkStatus.LINKED_INEFFECTIVE);
    }

    @Test
    @DisplayName("담긴_라벨이_전부_검출클래스_미매핑이면_LINKED_INEFFECTIVE다")
    void presetUnmappedIsIneffective() {
        given("EV01000101", PresetResolutionStatus.PRESET_UNMAPPED);

        // 운영자는 필터를 걸었다고 믿는데 실제로는 안 걸리는 상태 — 제외 선언과 같은 값이 되면 안 된다.
        assertThat(onlyRowStatus()).isEqualTo(PresetLinkStatus.LINKED_INEFFECTIVE);
    }

    @Test
    @DisplayName("프리셋이_없으면_UNLINKED다")
    void presetAbsentIsUnlinked() {
        given("EV01000101", PresetResolutionStatus.PRESET_ABSENT);

        assertThat(onlyRowStatus()).isEqualTo(PresetLinkStatus.UNLINKED);
    }

    @Test
    @DisplayName("유형_미등록_해석은_목록에_나오지_않지만_경합_창에서는_UNLINKED로_낮춘다")
    void unregisteredFallsBackToUnlinked() {
        // given — 그룹 인덱스가 아직 그 코드를 모르는 좁은 창(캐시-마스터 경합). 키를 지어내지 않고
        //   코드 자체를 넘겨 판정을 다시 묻는다.
        LsEvntType entity = type("EV09999999");
        when(repository.findAll()).thenReturn(List.of(entity));
        when(eventTypeService.filterKeyOf("EV09999999")).thenReturn(Optional.empty());
        when(presetLookup.resolve("EV09999999"))
                .thenReturn(PresetResolution.of(PresetResolutionStatus.EVENT_TYPE_UNREGISTERED));

        // then — 예외로 목록 전체를 500 내지 않는다. 보류 축에서도 UNLINKED 와 같은 편이다.
        assertThat(onlyRowStatus()).isEqualTo(PresetLinkStatus.UNLINKED);
    }

    // ---------------------------------------------------------------- 그룹 축 (AC-116)

    @Test
    @DisplayName("같은_표시명_그룹의_비대표_코드도_대표코드의_프리셋을_반영한다")
    void nonRepresentativeCodeReflectsGroupPreset() {
        // given — 표시명이 같아 한 그룹으로 접힌 세 유형. 대표코드는 최소 코드다.
        String rep = "EV01000101";
        String member2 = "EV01000102";
        String member3 = "EV01000103";
        // ★엔티티 mock 을 지역변수에 먼저 담는다 — thenReturn(...) 인자 안에서 헬퍼가 다시 when 을
        //   부르면 UnfinishedStubbingException 이다(Optional 뿐 아니라 List 인자에서도 똑같이 난다).
        List<LsEvntType> entities = List.of(type(rep), type(member2), type(member3));
        when(repository.findAll()).thenReturn(entities);
        when(eventTypeService.filterKeyOf(rep)).thenReturn(Optional.of(rep));
        when(eventTypeService.filterKeyOf(member2)).thenReturn(Optional.of(rep));
        when(eventTypeService.filterKeyOf(member3)).thenReturn(Optional.of(rep));
        // 프리셋은 대표코드에만 걸려 있다 — 비대표 코드로 직접 조회하면 매칭 0건이다.
        when(presetLookup.resolve(rep)).thenReturn(resolved());

        // when
        List<EventTypeAdminResponse> rows = service.list(null);

        // then — 세 행 모두 LINKED. 코드 단위로 판정하면 뒤 두 행이 UNLINKED 로 잘못 보인다.
        assertThat(rows).extracting(EventTypeAdminResponse::presetLinkStatus)
                .containsExactly(PresetLinkStatus.LINKED, PresetLinkStatus.LINKED,
                        PresetLinkStatus.LINKED);
    }

    @Test
    @DisplayName("같은_그룹은_해석을_한_번만_한다_N플러스원_방지")
    void resolvesOncePerGroup() {
        // given — 같은 그룹 3건
        String rep = "EV01000101";
        List<LsEvntType> entities = List.of(type(rep), type("EV01000102"), type("EV01000103"));
        when(repository.findAll()).thenReturn(entities);
        when(eventTypeService.filterKeyOf("EV01000101")).thenReturn(Optional.of(rep));
        when(eventTypeService.filterKeyOf("EV01000102")).thenReturn(Optional.of(rep));
        when(eventTypeService.filterKeyOf("EV01000103")).thenReturn(Optional.of(rep));
        when(presetLookup.resolve(rep)).thenReturn(resolved());

        // when
        service.list(null);

        // then — 유형 수가 아니라 그룹 수만큼만 조회한다(프리셋+라벨마스터 왕복이 행마다 나지 않는다)
        verify(presetLookup, times(1)).resolve(rep);
    }

    // ---------------------------------------------------------------- 거르기

    /** 상태가 서로 다른 네 유형을 심는다 — 코드 오름차순이 곧 반환 순서다. */
    private void givenAllFourStates() {
        String linked = "EV01";
        String excluded = "EV02";
        String ineffective = "EV03";
        String unlinked = "EV04";
        List<LsEvntType> entities =
                List.of(type(linked), type(excluded), type(ineffective), type(unlinked));
        when(repository.findAll()).thenReturn(entities);
        when(eventTypeService.filterKeyOf(linked)).thenReturn(Optional.of(linked));
        when(eventTypeService.filterKeyOf(excluded)).thenReturn(Optional.of(excluded));
        when(eventTypeService.filterKeyOf(ineffective)).thenReturn(Optional.of(ineffective));
        when(eventTypeService.filterKeyOf(unlinked)).thenReturn(Optional.of(unlinked));
        when(presetLookup.resolve(linked)).thenReturn(resolved());
        when(presetLookup.resolve(excluded))
                .thenReturn(PresetResolution.of(PresetResolutionStatus.PRESET_EMPTY));
        when(presetLookup.resolve(ineffective))
                .thenReturn(PresetResolution.of(PresetResolutionStatus.PRESET_UNMAPPED));
        when(presetLookup.resolve(unlinked))
                .thenReturn(PresetResolution.of(PresetResolutionStatus.PRESET_ABSENT));
    }

    @Test
    @DisplayName("거르기를_주지_않으면_전체를_반환한다_기존_호출_하위호환")
    void noFilterReturnsAll() {
        givenAllFourStates();

        // 서버 기본값을 두지 않는다 — 기존 호출(파라미터 없음)의 결과가 달라지면 안 된다.
        assertThat(service.list(null)).hasSize(4);
        assertThat(service.list()).hasSize(4);
    }

    @Test
    @DisplayName("단일_상태로_거르면_그_상태만_남는다")
    void filterBySingleState() {
        givenAllFourStates();

        assertThat(service.list(PresetLinkStatusFilter.UNLINKED))
                .extracting(EventTypeAdminResponse::evntTypeCd)
                .containsExactly("EV04");
        assertThat(service.list(PresetLinkStatusFilter.LINKED_EXCLUDED))
                .extracting(EventTypeAdminResponse::evntTypeCd)
                .containsExactly("EV02");
        assertThat(service.list(PresetLinkStatusFilter.LINKED_INEFFECTIVE))
                .extracting(EventTypeAdminResponse::evntTypeCd)
                .containsExactly("EV03");
        assertThat(service.list(PresetLinkStatusFilter.LINKED))
                .extracting(EventTypeAdminResponse::evntTypeCd)
                .containsExactly("EV01");
    }

    @Test
    @DisplayName("WITHHELD는_무효와_미연결만_모으고_제외_선언은_넣지_않는다")
    void withheldExcludesDeclaredExclusion() {
        givenAllFourStates();

        // ★핵심 — LINKED_EXCLUDED 는 보류를 유발하지 않는다(사람이 일부러 뺀 것). 여기 섞이면
        //   조치가 필요 없는 유형이 조치 목록에 계속 남는다.
        assertThat(service.list(PresetLinkStatusFilter.WITHHELD))
                .extracting(EventTypeAdminResponse::evntTypeCd)
                .containsExactly("EV03", "EV04");
    }

    // ---------------------------------------------------------------- 계약

    @Test
    @DisplayName("응답_상태값에는_WITHHELD가_없다_거르기_전용이다")
    void withheldIsFilterOnly() {
        // 응답 enum 과 거르기 enum 을 합치면 화면이 존재하지 않는 상태를 분기해야 한다.
        assertThat(PresetLinkStatus.values()).extracting(Enum::name)
                .containsExactlyInAnyOrder("LINKED", "LINKED_EXCLUDED", "LINKED_INEFFECTIVE",
                        "UNLINKED")
                .doesNotContain("WITHHELD");
        assertThat(PresetLinkStatusFilter.values()).extracting(Enum::name)
                .contains("WITHHELD");
    }

    @Test
    @DisplayName("모든_해석_사유가_상태로_번역된다_누락시_컴파일이_아니라_여기서_잡힌다")
    void everyResolutionStatusMaps() {
        for (PresetResolutionStatus status : PresetResolutionStatus.values()) {
            assertThat(PresetLinkStatus.from(status)).isNotNull();
        }
    }

    @Test
    @DisplayName("수정_응답은_연결_상태를_싣지_않는다_그룹_캐시가_커밋_이후에_비워지기_때문")
    void updateResponseOmitsLinkStatus() {
        // given
        String code = "EV01000101";
        LsEvntType entity = type(code);
        when(repository.findById(code)).thenReturn(Optional.of(entity));
        when(entity.applyManagement("새이름", null)).thenReturn(true);

        // when
        EventTypeAdminResponse res = service.update(code, new EventTypeUpdateRequest("새이름", null));

        // then — 표시명 수정은 그룹을 쪼갤 수 있는데 이 트랜잭션은 아직 옛 그룹을 본다.
        //   옛 값을 실어 보내면 "프리셋이 조용히 떨어진" 상태를 연결됨으로 잘못 안내한다.
        assertThat(res.presetLinkStatus()).isNull();
    }
}
