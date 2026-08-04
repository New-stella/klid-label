package kr.co.cudo.authoring.eventtype.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.eventtype.dto.EventTypeResponse;
import kr.co.cudo.authoring.eventtype.entity.LsEvntCtgry;
import kr.co.cudo.authoring.eventtype.entity.LsEvntType;
import kr.co.cudo.authoring.eventtype.repository.LsEvntCtgryRepository;
import kr.co.cudo.authoring.eventtype.repository.LsEvntTypeRepository;
import kr.co.cudo.authoring.sysconfig.ConfigKeys;
import kr.co.cudo.authoring.sysconfig.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EventTypeService 단위 테스트 (Mockito) — 캐시 프록시 없이 순수 매핑/필터/폴백 로직 검증.
 *
 * <p><b>축은 유형(type)</b>이다(V168). 구 버전은 (대분류+카테고리)로 dedup 했으나 이제 유형 1건이
 * 옵션 1행이다. 캐시 적중·인가는 별도 IT 에서 검증한다.
 */
class EventTypeServiceTest {

    private LsEvntTypeRepository typeRepository;
    private LsEvntCtgryRepository ctgryRepository;
    private SystemConfigService systemConfigService;
    private EventTypeService service;

    @BeforeEach
    void setUp() {
        typeRepository = mock(LsEvntTypeRepository.class);
        ctgryRepository = mock(LsEvntCtgryRepository.class);
        lenient().when(ctgryRepository.findAll()).thenReturn(List.of());
        systemConfigService = mock(SystemConfigService.class);
        // 기본 제외 대분류 = 08(배회). 개별 테스트에서 필요 시 재스텁한다.
        lenient().when(systemConfigService.getStringSet(ConfigKeys.EVENT_EXCLUDED_CLASS_CODES))
                .thenReturn(Set.of("08"));
        service = new EventTypeService(typeRepository, ctgryRepository, systemConfigService);
    }

    /** 등록 유형 1건 — (코드, 관제 수신명, 대분류, 수집여부). 카테고리코드는 대분류+"0001". */
    private static LsEvntType type(String code, String name, String clsf, String clctYn) {
        return type(code, name, null, clsf, "0001", clctYn);
    }

    /** 등록 유형 1건 — 전체 축(운영자 표시명·카테고리코드 포함). */
    private static LsEvntType type(String code, String evntNm, String optrIndctNm,
                                   String clsf, String ctgry, String clctYn) {
        LsEvntType t = mock(LsEvntType.class);
        lenient().when(t.getEvntTypeCd()).thenReturn(code);
        lenient().when(t.getEvntNm()).thenReturn(evntNm);
        lenient().when(t.getOptrIndctNm()).thenReturn(optrIndctNm);
        lenient().when(t.getEvntClsfCd()).thenReturn(clsf);
        lenient().when(t.getEvntCtgryCd()).thenReturn(ctgry);
        lenient().when(t.isCollected()).thenReturn("Y".equalsIgnoreCase(clctYn));
        return t;
    }

    /** 카테고리 마스터 1건. */
    private static LsEvntCtgry ctgry(String clsf, String ctgryCd, String name) {
        LsEvntCtgry c = mock(LsEvntCtgry.class);
        lenient().when(c.getEvntClsfCd()).thenReturn(clsf);
        lenient().when(c.getEvntCtgryCd()).thenReturn(ctgryCd);
        lenient().when(c.getEvntCtgryNm()).thenReturn(name);
        return c;
    }

    private void seedCategories(List<LsEvntCtgry> categories) {
        when(ctgryRepository.findAll()).thenReturn(categories);
    }

    private void seed(List<LsEvntType> types) {
        when(typeRepository.findAll()).thenReturn(types);
    }

    @Test
    @DisplayName("필터옵션이_등록된_이벤트유형을_노출한다")
    void 필터옵션이_등록된_이벤트유형을_노출한다() {
        // given — 침수 3종(같은 대분류 01)·산사태·화재 2종 + 배회(08, 제외) + 비수집 1종
        seed(List.of(
                type("EV01000103", "침수(범람)", "01", "Y"),
                type("EV01000101", "침수(범람)", "01", "Y"),
                type("EV01000102", "침수(범람)", "01", "Y"),
                type("EV01000201", "산사태", "01", "Y"),
                type("EV02000101", "화재", "02", "Y"),
                type("EV08000101", "배회", "08", "Y"),
                type("EV07000201", "기타 상황", "07", "N")));

        // when
        List<EventTypeResponse> options = service.filterOptions();

        // then — ★유형 축: 같은 카테고리라도 뭉치지 않고 유형마다 1행. 코드 오름차순.
        assertThat(options).extracting(EventTypeResponse::categoryKey)
                .containsExactly("EV01000101", "EV01000102", "EV01000103", "EV01000201", "EV02000101");
        // then — 제외 대분류(08)와 비수집(N)은 빠진다
        assertThat(options).extracting(EventTypeResponse::categoryKey)
                .doesNotContain("EV08000101", "EV07000201");
        // then — memberCodes 는 자기 자신 1건(FE 가 그대로 되돌려 보내는 값)
        assertThat(options.get(0).memberCodes()).containsExactly("EV01000101");
    }

    @Test
    @DisplayName("유형명이_없으면_카테고리명으로_표시된다")
    void 유형명이_없으면_카테고리명으로_표시된다() {
        // given — 관제 마스터에는 유형별 이름이 없었다(이관 직후의 실제 상태).
        //   같은 카테고리의 유형들이 같은 이름으로 보이는 것은 결함이 아니라 정상이다.
        seed(List.of(
                type("EV01000101", null, null, "01", "0001", "Y"),
                type("EV01000102", null, null, "01", "0001", "Y")));
        seedCategories(List.of(ctgry("01", "0001", "침수(범람)")));

        // when / then
        assertThat(service.filterOptions()).extracting(EventTypeResponse::label)
                .containsExactly("침수(범람)", "침수(범람)");
        assertThat(service.resolveLabel("EV01000101")).isEqualTo("침수(범람)");
    }

    @Test
    @DisplayName("카테고리명도_없으면_유형코드로_폴백한다")
    void 카테고리명도_없으면_유형코드로_폴백한다() {
        // given — 이름이 하나도 없는 유형(관제 미송신 + 카테고리 미등록)
        seed(List.of(type("EV02000201", null, null, "02", "0002", "Y")));
        seedCategories(List.of());

        // when / then — NPE 없이 코드로 폴백(예외·빈 화면 금지)
        assertThat(service.filterOptions().get(0).label()).isEqualTo("EV02000201");
        assertThat(service.resolveLabel("EV02000201")).isEqualTo("EV02000201");
        // 이름이 없는 유형은 라벨맵에 넣지 않는다(소비측 원문 폴백과 결과 동일)
        assertThat(service.codeLabelMap()).doesNotContainKey("EV02000201");
    }

    @Test
    @DisplayName("관제_수신명이_있으면_카테고리명보다_우선한다")
    void 관제_수신명이_있으면_카테고리명보다_우선한다() {
        // given — 관제가 그 유형에만 고유 이름을 보냈다(점진 전환)
        seed(List.of(
                type("EV01000101", null, null, "01", "0001", "Y"),
                type("EV01000102", "수위상승", null, "01", "0001", "Y")));
        seedCategories(List.of(ctgry("01", "0001", "침수(범람)")));

        // when / then — 이름이 온 유형만 갈라진다
        assertThat(service.filterOptions()).extracting(EventTypeResponse::label)
                .containsExactly("침수(범람)", "수위상승");
    }

    @Test
    @DisplayName("운영자_표시명이_관제_수신명보다_우선한다")
    void 운영자_표시명이_관제_수신명보다_우선한다() {
        // given — 관제 칸과 운영자 칸이 모두 채워진 유형
        seed(List.of(type("EV01000101", "관제원본명", "운영자표시명", "01", "0001", "Y")));
        seedCategories(List.of(ctgry("01", "0001", "침수(범람)")));

        // when / then — 사람이 정한 값이 가장 세다. 관제 원본은 유실되지 않는다(칸이 다르다).
        assertThat(service.filterOptions().get(0).label()).isEqualTo("운영자표시명");
        assertThat(service.resolveLabel("EV01000101")).isEqualTo("운영자표시명");
    }

    @Test
    @DisplayName("라벨맵은_비수집유형도_이름으로_해석한다")
    void 라벨맵은_비수집유형도_이름으로_해석한다() {
        // given — 비수집(N) 유형도 라벨 해석 대상이다(목록·상세 표시용)
        seed(List.of(
                type("EV02000101", "화재", "02", "Y"),
                type("EV07000201", "기타 상황", "07", "N")));

        // when
        Map<String, String> labels = service.codeLabelMap();

        // then
        assertThat(labels).containsEntry("EV07000201", "기타 상황");
        assertThat(labels).containsEntry("EV02000101", "화재");
    }

    @Test
    @DisplayName("미등록_코드는_원문_폴백한다")
    void 미등록_코드는_원문_폴백한다() {
        // given
        seed(List.of(type("EV02000101", "화재", "02", "Y")));

        // when / then — 미등록 코드는 원문 폴백, null/blank 는 입력 그대로(예외 금지)
        assertThat(service.resolveLabel("EV99999999")).isEqualTo("EV99999999");
        assertThat(service.resolveLabel("INTRUSION")).isEqualTo("INTRUSION");
        assertThat(service.resolveLabel("EV02000101")).isEqualTo("화재");
        assertThat(service.resolveLabel(null)).isNull();
        assertThat(service.resolveLabel("  ")).isEqualTo("  ");
    }

    @Test
    @DisplayName("빈_마스터면_빈_리스트와_빈_맵을_예외없이_반환한다")
    void 빈_마스터면_빈_리스트와_빈_맵을_예외없이_반환한다() {
        // given
        seed(List.of());

        // when / then
        assertThat(service.filterOptions()).isEmpty();
        assertThat(service.codeLabelMap()).isEmpty();
        assertThat(service.registeredCodes()).isEmpty();
    }

    // ----- 제외 대분류 설정 -----

    /** 침수(01)/화재(02) + 배회(08) + 미아(09) — 제외 코드 전환 검증용. */
    private void seedWithExcludableClasses() {
        seed(List.of(
                type("EV01000101", "침수(범람)", "01", "Y"),
                type("EV02000101", "화재", "02", "Y"),
                type("EV08000101", "배회", "08", "Y"),
                type("EV09000101", "미아", "09", "Y")));
    }

    @Test
    @DisplayName("제외_대분류_설정이_계속_동작한다")
    void 제외_대분류_설정이_계속_동작한다() {
        // given — 설정값 ["08"]
        seedWithExcludableClasses();
        when(systemConfigService.getStringSet(ConfigKeys.EVENT_EXCLUDED_CLASS_CODES))
                .thenReturn(Set.of("08"));

        // when / then — 08 만 빠지고 09 는 노출
        assertThat(service.filterOptions()).extracting(EventTypeResponse::categoryKey)
                .containsExactly("EV01000101", "EV02000101", "EV09000101");
    }

    @Test
    @DisplayName("제외코드_설정을_09로_바꾸면_08은_노출되고_09는_빠진다")
    void 제외코드_설정을_09로_바꾸면_08은_노출되고_09는_빠진다() {
        // given — 설정값 ["09"]
        seedWithExcludableClasses();
        when(systemConfigService.getStringSet(ConfigKeys.EVENT_EXCLUDED_CLASS_CODES))
                .thenReturn(Set.of("09"));

        // when / then — 배포 없이 제외 대상이 08 → 09 로 전환
        assertThat(service.filterOptions()).extracting(EventTypeResponse::categoryKey)
                .containsExactly("EV01000101", "EV02000101", "EV08000101");
    }

    @Test
    @DisplayName("제외코드_설정이_빈배열이면_전체_대분류_노출")
    void 제외코드_설정이_빈배열이면_전체_대분류_노출() {
        // given — 설정값 []
        seedWithExcludableClasses();
        when(systemConfigService.getStringSet(ConfigKeys.EVENT_EXCLUDED_CLASS_CODES))
                .thenReturn(Set.of());

        // when / then
        assertThat(service.filterOptions()).extracting(EventTypeResponse::categoryKey)
                .containsExactly("EV01000101", "EV02000101", "EV08000101", "EV09000101");
    }

    @Test
    @DisplayName("설정_조회_실패시_기본값_08로_폴백한다")
    void 설정_조회_실패시_기본값_08로_폴백한다() {
        // given — 설정 키 미시드/조회 실패
        seedWithExcludableClasses();
        when(systemConfigService.getStringSet(ConfigKeys.EVENT_EXCLUDED_CLASS_CODES))
                .thenThrow(new CustomException(ErrorCode.NOT_FOUND, "설정 키를 찾을 수 없습니다."));

        // when — 예외 전파 없이 기본값(08) 폴백
        // then
        assertThat(service.filterOptions()).extracting(EventTypeResponse::categoryKey)
                .containsExactly("EV01000101", "EV02000101", "EV09000101");
    }

    @Test
    @DisplayName("비규격_코드가_제외필터에_잘못_걸리지_않는다")
    void 비규격_코드가_제외필터에_잘못_걸리지_않는다() {
        // given — ★대분류는 관제 수신값이며 코드에서 유도하지 않는다(사용자 확정 2026-08-04).
        //   비규격 코드 'INTRUSION' 은 관제가 대분류를 아직 안 보내 null 이다.
        //   구 안(SUBSTRING(cd,3,2))이었다면 'TR' 이라는 존재하지 않는 대분류가 만들어졌다.
        seed(List.of(
                type("INTRUSION", "침입", null, "Y"),
                type("TRESPASS", "무단침입", "TR", "Y"),
                type("EV08000101", "배회", "08", "Y")));
        when(systemConfigService.getStringSet(ConfigKeys.EVENT_EXCLUDED_CLASS_CODES))
                .thenReturn(Set.of("08", "TR"));

        // when
        List<EventTypeResponse> options = service.filterOptions();

        // then — ★대분류 null 은 fail-open(노출). 제외되면 관제 송신 전까지 영상이 목록에서 사라진다.
        assertThat(options).extracting(EventTypeResponse::categoryKey).contains("INTRUSION");
        // then — 대분류가 <실제로> 제외 목록에 있는 유형만 빠진다
        assertThat(options).extracting(EventTypeResponse::categoryKey)
                .doesNotContain("EV08000101", "TRESPASS");
    }

    // ----- 필터 키(프리셋·목록 필터) -----

    @Test
    @DisplayName("filterKeyOf는_등록된_유형코드만_키로_인정한다")
    void filterKeyOf는_등록된_유형코드만_키로_인정한다() {
        // given
        seed(List.of(type("EV03000102", "교통사고", "03", "Y")));

        // when / then — 축이 유형이라 키 = 코드. 미등록/null/blank 는 fail-safe 빈 Optional
        assertThat(service.filterKeyOf("EV03000102")).contains("EV03000102");
        assertThat(service.filterKeyOf("EV99999999")).isEmpty();
        assertThat(service.filterKeyOf(null)).isEmpty();
        assertThat(service.filterKeyOf("  ")).isEmpty();
    }

    @Test
    @DisplayName("codesForFilterKey는_등록코드는_단건_미등록은_빈집합")
    void codesForFilterKey는_등록코드는_단건_미등록은_빈집합() {
        // given
        seed(List.of(type("EV03000102", "교통사고", "03", "Y")));

        // when / then
        assertThat(service.codesForFilterKey("EV03000102")).containsExactly("EV03000102");
        assertThat(service.codesForFilterKey("030001")).isEmpty();   // 구 카테고리 키는 이제 매칭 0건
        assertThat(service.codesForFilterKey(null)).isEmpty();
    }

    @Test
    @DisplayName("validFilterKeys는_filterOptions의_키집합을_반환한다")
    void validFilterKeys는_filterOptions의_키집합을_반환한다() {
        // given
        seedWithExcludableClasses();

        // when
        Set<String> keys = service.validFilterKeys();

        // then — 드롭다운에 노출되는 유형만 프리셋 매핑을 허용한다(제외 08 불포함)
        assertThat(keys).containsExactlyInAnyOrder("EV01000101", "EV02000101", "EV09000101");
        assertThat(keys).doesNotContain("EV08000101");
    }

    @Test
    @DisplayName("N플러스원_회피_마스터는_findAll_1회만_조회한다")
    void N플러스원_회피_마스터는_findAll_1회만_조회한다() {
        // given
        seedWithExcludableClasses();

        // when
        service.filterOptions();

        // then — 유형·카테고리 각각 1회 로드. 코드별 조회는 존재하지 않는다.
        verify(typeRepository, times(1)).findAll();
        verify(ctgryRepository, times(1)).findAll();
    }
}
