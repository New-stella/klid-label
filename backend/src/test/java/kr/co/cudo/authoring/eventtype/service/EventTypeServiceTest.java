package kr.co.cudo.authoring.eventtype.service;

import kr.co.cudo.authoring.eventtype.dto.EventTypeResponse;
import kr.co.cudo.authoring.eventtype.repository.MngExEvntTypeMapRepository;
import kr.co.cudo.authoring.eventtype.repository.MngExEvntTypeRepository;
import kr.co.cudo.authoring.video.entity.MngExEvntType;
import kr.co.cudo.authoring.video.entity.MngExEvntTypeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EventTypeService 단위 테스트 (Mockito) — 캐시 프록시 없이 순수 매핑/dedup/폴백 로직 검증.
 *
 * <p>관제 데이터 형태(dev-seed 와 동일 부분집합)를 mock 으로 구성한다. 캐시 적중·인가는 별도
 * IT 에서 검증한다.
 */
class EventTypeServiceTest {

    private MngExEvntTypeRepository typeRepository;
    private MngExEvntTypeMapRepository mapRepository;
    private EventTypeService service;

    @BeforeEach
    void setUp() {
        typeRepository = mock(MngExEvntTypeRepository.class);
        mapRepository = mock(MngExEvntTypeMapRepository.class);
        service = new EventTypeService(typeRepository, mapRepository);
    }

    private static MngExEvntType type(String code, String cls, String ctgry, String clctYn) {
        MngExEvntType t = mock(MngExEvntType.class);
        lenient().when(t.getEvntTypeCd()).thenReturn(code);
        lenient().when(t.getEvntClsCd()).thenReturn(cls);
        lenient().when(t.getEvntCtgryCd()).thenReturn(ctgry);
        lenient().when(t.getClctYn()).thenReturn(clctYn);
        return t;
    }

    private static MngExEvntTypeMap categoryRow(String cls, String ctgry, String evntNm) {
        MngExEvntTypeMap m = mock(MngExEvntTypeMap.class);
        lenient().when(m.getCdType()).thenReturn("02");
        lenient().when(m.getEvntClsCd()).thenReturn(cls);
        lenient().when(m.getEvntCtgryCd()).thenReturn(ctgry);
        lenient().when(m.getDtlEvnt()).thenReturn("");
        lenient().when(m.getEvntTypeCd()).thenReturn("");
        lenient().when(m.getEvntNm()).thenReturn(evntNm);
        return m;
    }

    /** CD_TYPE='02' 이지만 DTL_EVNT/EVNT_TYPE_CD 가 채워진 상세행 — 카테고리 라벨에서 제외되어야 한다. */
    private static MngExEvntTypeMap detailRow(String cls, String ctgry, String dtl, String code, String evntNm) {
        MngExEvntTypeMap m = mock(MngExEvntTypeMap.class);
        lenient().when(m.getCdType()).thenReturn("02");
        lenient().when(m.getEvntClsCd()).thenReturn(cls);
        lenient().when(m.getEvntCtgryCd()).thenReturn(ctgry);
        lenient().when(m.getDtlEvnt()).thenReturn(dtl);
        lenient().when(m.getEvntTypeCd()).thenReturn(code);
        lenient().when(m.getEvntNm()).thenReturn(evntNm);
        return m;
    }

    /** 침수 3코드(동일 카테고리), 산사태, 화재 2코드 + ignore(08) 1코드 + 비수집 1코드 시드. */
    private void seedTypical() {
        // 주의: mock 엔티티 생성(when 스텁 포함)을 when().thenReturn() 인자 안에서 하면
        // Mockito UnfinishedStubbing 이 발생하므로 리스트를 먼저 구성한 뒤 스텁한다.
        List<MngExEvntType> types = List.of(
                type("EV01000101", "01", "0001", "Y"),
                type("EV01000103", "01", "0001", "Y"),
                type("EV01000102", "01", "0001", "Y"),
                type("EV01000201", "01", "0002", "Y"),
                type("EV02000101", "02", "0001", "Y"),
                type("EV02000102", "02", "0001", "Y"),
                type("EV08000101", "08", "0001", "Y")   // ignore 대분류 — 제외 대상
        );
        List<MngExEvntTypeMap> maps = List.of(
                categoryRow("01", "0001", "침수(범람)"),
                categoryRow("01", "0002", "산사태"),
                categoryRow("02", "0001", "화재"),
                categoryRow("07", "0002", "기타 상황")
        );
        when(typeRepository.findByClctYn("Y")).thenReturn(types);
        when(mapRepository.findByCdType("02")).thenReturn(maps);
    }

    @Test
    @DisplayName("필터목록은_수집대상이고_ignore가_아닌_카테고리만_dedup해서_반환")
    void filterOptionsDedupExcludingIgnore() {
        // given
        seedTypical();

        // when
        List<EventTypeResponse> options = service.filterOptions();

        // then — 침수(3코드 1옵션)/산사태/화재 = 3개, ignore(08)는 제외
        assertThat(options).hasSize(3);
        assertThat(options).extracting(EventTypeResponse::categoryKey)
                .containsExactly("010001", "010002", "020001");
        assertThat(options).extracting(EventTypeResponse::label)
                .containsExactly("침수(범람)", "산사태", "화재");
        assertThat(options).noneMatch(o -> o.categoryKey().startsWith("08"));
    }

    @Test
    @DisplayName("필터옵션의_memberCodes가_해당_카테고리_수집코드를_담는다")
    void filterOptionMemberCodes() {
        // given
        seedTypical();

        // when
        EventTypeResponse flood = service.filterOptions().get(0);

        // then — 침수 카테고리에 3개 상세코드가 오름차순으로 담긴다
        assertThat(flood.categoryKey()).isEqualTo("010001");
        assertThat(flood.memberCodes())
                .containsExactly("EV01000101", "EV01000102", "EV01000103");
    }

    @Test
    @DisplayName("라벨맵은_비수집코드_EV07000201도_기타상황으로_해석한다")
    void codeLabelMapResolvesNonCollected() {
        // given — findAll 에는 비수집 EV07000201 포함
        List<MngExEvntType> all = List.of(
                type("EV02000101", "02", "0001", "Y"),
                type("EV07000201", "07", "0002", "N")
        );
        List<MngExEvntTypeMap> maps = List.of(
                categoryRow("02", "0001", "화재"),
                categoryRow("07", "0002", "기타 상황")
        );
        when(typeRepository.findAll()).thenReturn(all);
        when(mapRepository.findByCdType("02")).thenReturn(maps);

        // when
        Map<String, String> labels = service.codeLabelMap();

        // then
        assertThat(labels).containsEntry("EV07000201", "기타 상황");
        assertThat(labels).containsEntry("EV02000101", "화재");
    }

    @Test
    @DisplayName("관제_미등록_코드는_resolveLabel이_원문코드를_폴백반환한다")
    void resolveLabelFallsBackToRawCode() {
        // given — 라벨맵에 없는 코드
        List<MngExEvntType> all = List.of(type("EV02000101", "02", "0001", "Y"));
        List<MngExEvntTypeMap> maps = List.of(categoryRow("02", "0001", "화재"));
        when(typeRepository.findAll()).thenReturn(all);
        when(mapRepository.findByCdType("02")).thenReturn(maps);

        // when / then — 미등록 코드는 원문 폴백, null/blank 는 입력 그대로
        assertThat(service.resolveLabel("EV99999999")).isEqualTo("EV99999999");
        assertThat(service.resolveLabel("EV02000101")).isEqualTo("화재");
        assertThat(service.resolveLabel(null)).isNull();
        assertThat(service.resolveLabel("  ")).isEqualTo("  ");
    }

    @Test
    @DisplayName("카테고리_라벨행이_없으면_NPE없이_폴백한다")
    void missingCategoryLabelFallsBack() {
        // given — 수집코드는 있으나 카테고리명행(MAP)이 비어있음
        List<MngExEvntType> collected = List.of(type("EV02000201", "02", "0002", "Y"));
        List<MngExEvntType> all = List.of(type("EV02000201", "02", "0002", "Y"));
        when(typeRepository.findByClctYn("Y")).thenReturn(collected);
        when(typeRepository.findAll()).thenReturn(all);
        when(mapRepository.findByCdType("02")).thenReturn(List.of());

        // when / then — filterOptions 는 categoryKey 폴백, codeLabelMap 은 라벨없어 미수록 → resolveLabel 원문
        List<EventTypeResponse> options = service.filterOptions();
        assertThat(options).hasSize(1);
        assertThat(options.get(0).label()).isEqualTo("020002");   // categoryKey 폴백, NPE 없음
        assertThat(service.resolveLabel("EV02000201")).isEqualTo("EV02000201");
    }

    @Test
    @DisplayName("빈_마스터면_빈_리스트와_빈_맵을_예외없이_반환한다")
    void emptyMasterReturnsEmpty() {
        // given
        when(typeRepository.findByClctYn("Y")).thenReturn(List.of());
        when(typeRepository.findAll()).thenReturn(List.of());
        when(mapRepository.findByCdType("02")).thenReturn(List.of());

        // when / then
        assertThat(service.filterOptions()).isEmpty();
        assertThat(service.codeLabelMap()).isEmpty();
    }

    @Test
    @DisplayName("CD_TYPE_02에_상세행이_섞여도_카테고리명행만으로_라벨을_도출한다")
    void detailRowsDoNotOverrideCategoryLabel() {
        // given — 동일 (02,0002) 에 카테고리명행 '쓰러짐' + 상세행 '쓰러짐(상세)' 가 섞여 반환
        List<MngExEvntType> collected = List.of(type("EV02000201", "02", "0002", "Y"));
        List<MngExEvntType> all = List.of(type("EV02000201", "02", "0002", "Y"));
        List<MngExEvntTypeMap> maps = List.of(
                categoryRow("02", "0002", "쓰러짐"),
                detailRow("02", "0002", "01", "EV02000201", "쓰러짐(상세)")
        );
        when(typeRepository.findByClctYn("Y")).thenReturn(collected);
        when(typeRepository.findAll()).thenReturn(all);
        when(mapRepository.findByCdType("02")).thenReturn(maps);

        // then — 상세행이 라벨을 덮어쓰지 않고 카테고리명행 '쓰러짐' 으로 도출
        assertThat(service.filterOptions().get(0).label()).isEqualTo("쓰러짐");
        assertThat(service.resolveLabel("EV02000201")).isEqualTo("쓰러짐");
    }

    @Test
    @DisplayName("categoryKeyOf_상세코드를_카테고리키로_변환한다")
    void categoryKeyOfConvertsDetailCode() {
        // given — 마스터 전체(findAll)에 교통사고(03,0001) 상세코드 EV03000102 포함
        List<MngExEvntType> all = List.of(
                type("EV03000101", "03", "0001", "Y"),
                type("EV03000102", "03", "0001", "Y"),
                type("EV01000101", "01", "0001", "Y")
        );
        when(typeRepository.findAll()).thenReturn(all);

        // when / then — 상세 EV-코드 → categoryKey(cls+ctgry)
        assertThat(service.categoryKeyOf("EV03000102")).contains("030001");
        assertThat(service.categoryKeyOf("EV01000101")).contains("010001");
    }

    @Test
    @DisplayName("categoryKeyOf_미등록코드_null_blank는_빈Optional_failsafe")
    void categoryKeyOfUnknownReturnsEmpty() {
        // given — mock 엔티티(when 스텁 포함) 생성을 thenReturn 인자 밖에서 먼저 한다(UnfinishedStubbing 회피).
        List<MngExEvntType> all = List.of(type("EV03000101", "03", "0001", "Y"));
        when(typeRepository.findAll()).thenReturn(all);

        // when / then — 미등록/null/blank 는 빈 Optional
        assertThat(service.categoryKeyOf("EV99999999")).isEmpty();
        assertThat(service.categoryKeyOf(null)).isEmpty();
        assertThat(service.categoryKeyOf("  ")).isEmpty();
    }

    @Test
    @DisplayName("validCategoryKeys_filterOptions의_categoryKey집합을_반환한다")
    void validCategoryKeysReturnsFilterOptionCategoryKeys() {
        // given — 침수(010001)/산사태(010002)/화재(020001) + ignore(08) + 매핑
        seedTypical();

        // when
        Set<String> keys = service.validCategoryKeys();

        // then — filterOptions 의 categoryKey 집합(ignore 08 제외)
        assertThat(keys).containsExactlyInAnyOrder("010001", "010002", "020001");
        assertThat(keys).noneMatch(k -> k.startsWith("08"));
    }

    @Test
    @DisplayName("N+1회피_카테고리라벨은_findByCdType_1회로드_findCategoryLabel_미사용")
    void avoidsNPlusOneQueries() {
        // given
        seedTypical();

        // when
        service.filterOptions();

        // then — 카테고리 라벨은 findByCdType('02') 1회만, 코드별 findCategoryLabel 호출 없음
        verify(mapRepository, times(1)).findByCdType("02");
        verify(mapRepository, never()).findCategoryLabel(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
