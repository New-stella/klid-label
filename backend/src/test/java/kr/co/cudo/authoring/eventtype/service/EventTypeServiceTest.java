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
 * 옵션의 최소 단위다. 캐시 적중·인가는 별도 IT 에서 검증한다.
 *
 * <h3>★ 2026-08-05 정정 — 옵션은 <b>표시명 그룹</b> 단위다 (R3·R4·R5)</h3>
 * <p>구 단언은 "유형 1건 = 옵션 1행"(1:1 축)이었다. 관제가 유형별 이름({@code EVNT_NM})을 아직
 * 보내지 않아 표시명이 <b>카테고리명</b>으로 폴백되면서 드롭다운에 같은 이름이 3번씩 뜬 것이
 * 원인이다. 이제 <b>표시명이 같은 유형들을 한 옵션으로 접고</b> 그룹 대표코드(= 그룹 내 최소
 * 유형코드)를 필터 키로 노출한다. 파라미터 값은 여전히 <b>코드</b>이며(이름 문자열을 올리지
 * 않는다) 비대표 코드로 들어온 기존 북마크도 그룹 전체로 해석된다.
 *
 * <p>이 접기는 <b>영구 병합이 아니다</b> — 관제가 {@code EVNT_NM} 을 보내거나 운영자가 표시명을
 * 지정하면 그 유형만 자기 이름을 얻어 그룹이 <b>자동으로 쪼개진다</b>(아래
 * {@code 관제_수신명이_있으면_카테고리명보다_우선한다} 가 그 성질을 고정한다).
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

        // then — ★2026-08-05 정정: 표시명이 같은 침수(범람) 3종은 <옵션 1행>으로 접힌다.
        //   구 단언(EV01000101/102/103 을 각각 1행)은 드롭다운 중복의 원인이었다(R3).
        //   옵션 정렬은 대표코드 오름차순(기존 안정 순서 관례 유지).
        assertThat(options).extracting(EventTypeResponse::categoryKey)
                .containsExactly("EV01000101", "EV01000201", "EV02000101");
        // then — 제외 대분류(08)와 비수집(N)은 빠진다
        assertThat(options).extracting(EventTypeResponse::categoryKey)
                .doesNotContain("EV08000101", "EV07000201");
        // then — memberCodes 에 그룹 전체 코드가 담긴다(BE 가 이 집합으로 IN 필터한다)
        assertThat(options.get(0).memberCodes())
                .containsExactly("EV01000101", "EV01000102", "EV01000103");
    }

    @Test
    @DisplayName("같은_표시명의_유형들이_옵션_1건으로_접힌다")
    void 같은_표시명의_유형들이_옵션_1건으로_접힌다() {
        // given — 실측 dev DB 상태: 침수 3종·교통사고 3종·화재 2종이 모두 카테고리명으로 폴백
        seed(List.of(
                type("EV01000101", null, null, "01", "0001", "Y"),
                type("EV01000102", null, null, "01", "0001", "Y"),
                type("EV01000103", null, null, "01", "0001", "Y"),
                type("EV03000101", null, null, "03", "0001", "Y"),
                type("EV03000102", null, null, "03", "0001", "Y"),
                type("EV03000103", null, null, "03", "0001", "Y"),
                type("EV02000101", null, null, "02", "0001", "Y"),
                type("EV02000102", null, null, "02", "0001", "Y")));
        seedCategories(List.of(
                ctgry("01", "0001", "침수(범람)"),
                ctgry("02", "0001", "화재"),
                ctgry("03", "0001", "교통사고")));

        // when
        List<EventTypeResponse> options = service.filterOptions();

        // then — 같은 label 이 두 번 나오지 않는다(수용 기준 1)
        assertThat(options).extracting(EventTypeResponse::label)
                .containsExactly("침수(범람)", "화재", "교통사고");
        assertThat(options).extracting(EventTypeResponse::label).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("옵션의_memberCodes에_그룹_전체_코드가_담긴다")
    void 옵션의_memberCodes에_그룹_전체_코드가_담긴다() {
        // given — 입력 순서를 일부러 뒤섞어도 memberCodes 는 코드 오름차순이어야 한다
        seed(List.of(
                type("EV01000103", null, null, "01", "0001", "Y"),
                type("EV01000101", null, null, "01", "0001", "Y"),
                type("EV01000102", null, null, "01", "0001", "Y")));
        seedCategories(List.of(ctgry("01", "0001", "침수(범람)")));

        // when / then
        assertThat(service.filterOptions().get(0).memberCodes())
                .containsExactly("EV01000101", "EV01000102", "EV01000103");
    }

    @Test
    @DisplayName("그룹_대표코드는_그룹내_최소_유형코드다")
    void 그룹_대표코드는_그룹내_최소_유형코드다() {
        // given — 대표코드가 실행마다 흔들리면 프리셋 매핑이 조용히 어긋난다(HIGH #5).
        //   삽입 순서와 무관하게 <정렬 기반 최소 코드>로 고정한다.
        seed(List.of(
                type("EV03000103", null, null, "03", "0001", "Y"),
                type("EV03000102", null, null, "03", "0001", "Y"),
                type("EV03000101", null, null, "03", "0001", "Y")));
        seedCategories(List.of(ctgry("03", "0001", "교통사고")));

        // when / then
        assertThat(service.filterOptions()).extracting(EventTypeResponse::categoryKey)
                .containsExactly("EV03000101");
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

        // when / then — ★2026-08-05 정정: 구 단언은 같은 이름 2행이었다(= 드롭다운 중복).
        //   이제 한 옵션으로 접히고, 라벨 해석(resolveLabel)은 코드 단위 그대로다.
        assertThat(service.filterOptions()).extracting(EventTypeResponse::label)
                .containsExactly("침수(범람)");
        assertThat(service.resolveLabel("EV01000101")).isEqualTo("침수(범람)");
        assertThat(service.resolveLabel("EV01000102")).isEqualTo("침수(범람)");
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
        // given — 그룹이 자기 혼자인 경우(이름이 유일)
        seed(List.of(type("EV03000102", "교통사고", "03", "Y")));

        // when / then
        assertThat(service.codesForFilterKey("EV03000102")).containsExactly("EV03000102");
        assertThat(service.codesForFilterKey("030001")).isEmpty();   // 구 카테고리 키는 이제 매칭 0건
        assertThat(service.codesForFilterKey(null)).isEmpty();
    }

    /** 표시명이 같은 침수 3종(그룹) + 이름이 다른 산사태 1종. 그룹 대표는 EV01000101. */
    private void seedGroupedTypes() {
        seed(List.of(
                type("EV01000101", null, null, "01", "0001", "Y"),
                type("EV01000102", null, null, "01", "0001", "Y"),
                type("EV01000103", null, null, "01", "0001", "Y"),
                type("EV01000201", null, null, "01", "0002", "Y")));
        seedCategories(List.of(
                ctgry("01", "0001", "침수(범람)"),
                ctgry("01", "0002", "산사태")));
    }

    @Test
    @DisplayName("대표코드로_필터하면_그룹_전체_코드가_매칭된다")
    void 대표코드로_필터하면_그룹_전체_코드가_매칭된다() {
        // given
        seedGroupedTypes();

        // when / then — 드롭다운이 내려준 대표코드 1개로 그룹 전체 영상이 잡혀야 한다(R4)
        assertThat(service.codesForFilterKey("EV01000101"))
                .containsExactlyInAnyOrder("EV01000101", "EV01000102", "EV01000103");
        // 다른 그룹은 섞이지 않는다
        assertThat(service.codesForFilterKey("EV01000201")).containsExactly("EV01000201");
    }

    @Test
    @DisplayName("비대표코드로_필터해도_그룹_전체_코드가_매칭된다")
    void 비대표코드로_필터해도_그룹_전체_코드가_매칭된다() {
        // given — 기존 북마크 URL(?eventTypeCd=EV01000103)이 0건이 되면 안 된다(HIGH #2)
        seedGroupedTypes();

        // when / then
        assertThat(service.codesForFilterKey("EV01000103"))
                .containsExactlyInAnyOrder("EV01000101", "EV01000102", "EV01000103");
        assertThat(service.codesForFilterKey("EV01000102"))
                .containsExactlyInAnyOrder("EV01000101", "EV01000102", "EV01000103");
    }

    @Test
    @DisplayName("filterKeyOf는_비대표코드에_대해_대표코드를_돌려준다")
    void filterKeyOf는_비대표코드에_대해_대표코드를_돌려준다() {
        // given — 오토라벨 프리셋이 그룹 전체에 걸리려면(R5) 영상 EV-코드가 대표코드로 접혀야 한다
        seedGroupedTypes();

        // when / then
        assertThat(service.filterKeyOf("EV01000103")).contains("EV01000101");
        assertThat(service.filterKeyOf("EV01000102")).contains("EV01000101");
        assertThat(service.filterKeyOf("EV01000101")).contains("EV01000101");
        assertThat(service.filterKeyOf("EV01000201")).contains("EV01000201");
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
    @DisplayName("validFilterKeys는_비대표코드도_포함한다")
    void validFilterKeys는_비대표코드도_포함한다() {
        // given — 그룹 도입 전에 저장된 프리셋은 비대표 코드를 들고 있을 수 있다.
        //   대표코드만 유효 키로 인정하면 그 프리셋이 저장·수정에서 400 으로 탈락한다(HIGH #3).
        seedGroupedTypes();

        // when
        Set<String> keys = service.validFilterKeys();

        // then
        assertThat(keys).containsExactlyInAnyOrder(
                "EV01000101", "EV01000102", "EV01000103", "EV01000201");
    }

    @Test
    @DisplayName("이름없는_비규격코드는_자기_혼자_그룹이_된다")
    void 이름없는_비규격코드는_자기_혼자_그룹이_된다() {
        // given — 대분류·카테고리가 null 이라 표시명이 <코드 원문>으로 폴백되는 비규격 코드.
        //   코드가 서로 달라 표시명도 달라지므로 <한 덩어리로 뭉치면 안 된다>.
        seed(List.of(
                type("INTRUSION", null, null, null, null, "Y"),
                type("LOITERING", null, null, null, null, "Y")));
        seedCategories(List.of());

        // when
        List<EventTypeResponse> options = service.filterOptions();

        // then — 각자 1행 + 라벨은 코드 원문
        assertThat(options).extracting(EventTypeResponse::categoryKey)
                .containsExactly("INTRUSION", "LOITERING");
        assertThat(options).extracting(EventTypeResponse::label)
                .containsExactly("INTRUSION", "LOITERING");
        assertThat(options.get(0).memberCodes()).containsExactly("INTRUSION");
        assertThat(service.codesForFilterKey("INTRUSION")).containsExactly("INTRUSION");
        assertThat(service.filterKeyOf("INTRUSION")).contains("INTRUSION");
    }

    @Test
    @DisplayName("미등록코드는_빈_집합이고_예외를_던지지_않는다")
    void 미등록코드는_빈_집합이고_예외를_던지지_않는다() {
        // given — fail-safe 계약: 잘못된 코드 하나로 목록 조회가 500 이 되면 북마크 진입이 죽는다
        seedGroupedTypes();

        // when / then
        assertThat(service.codesForFilterKey("EV99999999")).isEmpty();
        assertThat(service.codesForFilterKey("'; DROP TABLE LS_EVNT_TYPE; --")).isEmpty();
        assertThat(service.codesForFilterKey("침수(범람)")).isEmpty();   // 이름 문자열은 키가 아니다
        assertThat(service.codesForFilterKey(null)).isEmpty();
        assertThat(service.codesForFilterKey("   ")).isEmpty();
        assertThat(service.filterKeyOf("EV99999999")).isEmpty();
        assertThat(service.filterKeyOf(null)).isEmpty();
        assertThat(service.filterKeyOf("   ")).isEmpty();
    }

    @Test
    @DisplayName("비수집_유형은_옵션에_포함되지_않는다")
    void 비수집_유형은_옵션에_포함되지_않는다() {
        // given — 비수집(N) 유형이 수집 유형과 <같은 표시명>이어도 그룹에 끌려들어오면 안 된다.
        //   끌려오면 통계 분포(memberCodes 합산)에 비수집 카운트가 섞인다(기존 정책 위반).
        seed(List.of(
                type("EV07000101", null, null, "07", "0002", "Y"),
                type("EV07000201", null, null, "07", "0002", "N")));
        seedCategories(List.of(ctgry("07", "0002", "기타 상황")));

        // when
        List<EventTypeResponse> options = service.filterOptions();

        // then — 노출은 수집 유형 1건, memberCodes 에도 비수집 코드가 없다
        assertThat(options).extracting(EventTypeResponse::categoryKey).containsExactly("EV07000101");
        assertThat(options.get(0).memberCodes()).containsExactly("EV07000101");
        assertThat(service.validFilterKeys()).doesNotContain("EV07000201");
        // 비수집 코드로 직접 필터하면 <자기 자신>만 매칭한다(기존 fail-safe 유지)
        assertThat(service.codesForFilterKey("EV07000201")).containsExactly("EV07000201");
    }

    @Test
    @DisplayName("제외_대분류_유형은_옵션에_포함되지_않고_대분류_null은_노출된다")
    void 제외_대분류_유형은_옵션에_포함되지_않고_대분류_null은_노출된다() {
        // given — 제외 대분류(08)와 <같은 표시명>인 노출 유형 + 대분류 null(관제 미송신) 유형
        seed(List.of(
                type("EV01000101", "배회", null, "01", "0001", "Y"),
                type("EV08000101", "배회", null, "08", "0001", "Y"),
                type("EV09000101", "미아", null, null, null, "Y")));
        seedCategories(List.of());

        // when
        List<EventTypeResponse> options = service.filterOptions();

        // then — 제외 유형은 그룹 멤버로도 들어오지 않는다(제외가 그룹을 통해 새면 안 된다)
        assertThat(options).extracting(EventTypeResponse::categoryKey)
                .containsExactly("EV01000101", "EV09000101");
        assertThat(options.get(0).memberCodes()).containsExactly("EV01000101");
        // then — ★대분류 null 은 fail-open(노출). 제외하면 관제 송신 전까지 영상이 통째로 사라진다.
        assertThat(service.validFilterKeys()).contains("EV09000101").doesNotContain("EV08000101");
    }

    @Test
    @DisplayName("표시명이_공백뿐이어도_NPE없이_코드로_폴백해_각자_그룹이_된다")
    void 표시명이_공백뿐이어도_NPE없이_코드로_폴백해_각자_그룹이_된다() {
        // given — 관제 수신값/수기 입력이 공백만인 경우. 공백을 그룹 키로 쓰면 <서로 다른 유형이
        //   한 덩어리>가 된다(HIGH #4). 표시명 판정은 공백을 "값 없음"으로 보고 코드로 폴백한다.
        seed(List.of(
                type("EV05000101", "   ", "  ", null, null, "Y"),
                type("EV05000201", "", null, null, null, "Y")));
        seedCategories(List.of());

        // when
        List<EventTypeResponse> options = service.filterOptions();

        // then
        assertThat(options).extracting(EventTypeResponse::categoryKey)
                .containsExactly("EV05000101", "EV05000201");
        assertThat(options).extracting(EventTypeResponse::label)
                .containsExactly("EV05000101", "EV05000201");
    }

    @Test
    @DisplayName("표시명_앞뒤_공백이_달라도_같은_그룹으로_접힌다")
    void 표시명_앞뒤_공백이_달라도_같은_그룹으로_접힌다() {
        // given — 그룹 키는 trim 된 표시명 기준이다(운영자 수기 입력의 공백 차이 흡수)
        seed(List.of(
                type("EV02000101", null, " 화재 ", null, null, "Y"),
                type("EV02000102", null, "화재", null, null, "Y")));
        seedCategories(List.of());

        // when
        List<EventTypeResponse> options = service.filterOptions();

        // then
        assertThat(options).hasSize(1);
        assertThat(options.get(0).label()).isEqualTo("화재");
        assertThat(options.get(0).memberCodes()).containsExactly("EV02000101", "EV02000102");
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
