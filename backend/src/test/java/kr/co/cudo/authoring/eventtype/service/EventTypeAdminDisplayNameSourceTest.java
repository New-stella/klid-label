package kr.co.cudo.authoring.eventtype.service;

import kr.co.cudo.authoring.eventtype.dto.EventTypeAdminResponse;
import kr.co.cudo.authoring.eventtype.dto.EventTypeUpdateRequest;
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
import static org.mockito.Mockito.when;

/**
 * 이벤트유형 관리 <b>두 응답</b>(목록 조회 · 수정)이 표시명의 <b>채택 단계</b>를 함께 내려주는지
 * 고정한다. @design API-185, API-186
 *
 * <p><b>왜 두 응답 모두인가</b>: 수정 직후 화면은 목록을 재조회하지 않고 응답으로 그 행을 갱신한다.
 * 수정 응답에만 단계가 빠지면 저장 직후 화면에서 출처 표시가 사라지거나(누락) 옛 값으로 남는다.
 *
 * <p><b>왜 서비스 레이어인가</b>: 두 경로가 같은 조립 지점({@code EventTypeAdminResponse.from})을
 * 지나므로 그 지점이 단계를 실어 보내는지는 여기서 다 검증된다. 와이어(JSON) 노출은
 * {@code EventTypeAdminControllerIT} 가 별도로 확인한다.
 *
 * <p><b>픽스처 값은 단계마다 다르게 둔다</b> — 네 후보가 모두 {@code String} 이라 순서가 뒤바뀌어도
 * 타입으로는 걸리지 않는다.
 */
class EventTypeAdminDisplayNameSourceTest {

    private static final String CODE = "EV01000101";
    private static final String CLSF = "01";
    private static final String CTGRY = "0001";
    /** 카테고리 인덱스 조인 키 구분자 — 서비스와 동일 규칙이어야 스텁이 매칭된다. */
    private static final String CATEGORY_KEY = CLSF + "\u001f" + CTGRY;

    private static final String OPERATOR_NM = "운영자지정명";
    private static final String CONTROL_NM = "관제수신명";
    private static final String CATEGORY_NM = "카테고리명";

    private LsEvntTypeRepository repository;
    private EventTypeService eventTypeService;
    private EventTypeAdminService service;

    @BeforeEach
    void setUp() {
        repository = mock(LsEvntTypeRepository.class);
        eventTypeService = mock(EventTypeService.class);
        EventTypeCacheEvictor evictor = mock(EventTypeCacheEvictor.class);
        service = new EventTypeAdminService(repository, evictor, eventTypeService);
        // 기본은 카테고리명이 있는 환경 — 개별 테스트에서 필요 시 빈 인덱스로 재스텁한다.
        lenient().when(eventTypeService.categoryNameIndex())
                .thenReturn(Map.of(CATEGORY_KEY, CATEGORY_NM));
    }

    /**
     * 등록 유형 1건. 엔티티에 공개 팩토리가 없어(등록은 네이티브 upsert 경로다) mock 으로 만든다 —
     * {@code EventTypeServiceTest} 와 동일한 기법이다.
     */
    private LsEvntType type(String optrIndctNm, String evntNm) {
        LsEvntType t = mock(LsEvntType.class);
        lenient().when(t.getEvntTypeCd()).thenReturn(CODE);
        lenient().when(t.getOptrIndctNm()).thenReturn(optrIndctNm);
        lenient().when(t.getEvntNm()).thenReturn(evntNm);
        lenient().when(t.getEvntClsfCd()).thenReturn(CLSF);
        lenient().when(t.getEvntCtgryCd()).thenReturn(CTGRY);
        lenient().when(t.getClctYn()).thenReturn("Y");
        return t;
    }

    /** 목록 조회 경로의 그 유형 1행. */
    private EventTypeAdminResponse listed(LsEvntType type) {
        when(repository.findAll()).thenReturn(List.of(type));
        List<EventTypeAdminResponse> rows = service.list();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    /** 수정 경로의 응답 — 표시명 지정 요청 1건을 태운다. */
    private EventTypeAdminResponse updated(LsEvntType type, String newOptrIndctNm) {
        when(repository.findById(CODE)).thenReturn(Optional.of(type));
        return service.update(CODE, new EventTypeUpdateRequest(newOptrIndctNm, null));
    }

    // ---------------------------------------------------------------- 목록 조회 4단계

    @Test
    @DisplayName("목록_운영자_지정명이_있으면_출처는_operator다")
    void listOperator() {
        // given / when
        EventTypeAdminResponse row = listed(type(OPERATOR_NM, CONTROL_NM));

        // then
        assertThat(row.dsplNm()).isEqualTo(OPERATOR_NM);
        assertThat(row.dsplNmSource()).isEqualTo("operator");
    }

    @Test
    @DisplayName("목록_운영자_지정명이_없으면_출처는_control이다")
    void listControl() {
        // given / when — 운영자 칸이 비어 있다
        EventTypeAdminResponse row = listed(type(null, CONTROL_NM));

        // then — 관제 원본은 응답에 그대로 남고 표시명이 그 값으로 내려온다
        assertThat(row.dsplNm()).isEqualTo(CONTROL_NM);
        assertThat(row.evntNm()).isEqualTo(CONTROL_NM);
        assertThat(row.dsplNmSource()).isEqualTo("control");
    }

    @Test
    @DisplayName("목록_고유_이름이_없으면_출처는_category다")
    void listCategory() {
        // given / when — 관제 마스터에 유형별 이름이 애초에 없던 정상 상태
        EventTypeAdminResponse row = listed(type(null, null));

        // then
        assertThat(row.dsplNm()).isEqualTo(CATEGORY_NM);
        assertThat(row.evntCtgryNm()).isEqualTo(CATEGORY_NM);
        assertThat(row.dsplNmSource()).isEqualTo("category");
    }

    @Test
    @DisplayName("목록_후보가_전부_없으면_출처는_code다")
    void listCode() {
        // given — 카테고리 인덱스에도 그 키가 없다(카테고리 마스터 미등록)
        when(eventTypeService.categoryNameIndex()).thenReturn(Map.of());

        // when
        EventTypeAdminResponse row = listed(type(null, null));

        // then — 표시명은 유형코드로 폴백하고 출처가 그 사실을 알려준다
        assertThat(row.dsplNm()).isEqualTo(CODE);
        assertThat(row.evntCtgryNm()).isNull();
        assertThat(row.dsplNmSource()).isEqualTo("code");
    }

    // ---------------------------------------------------------------- 수정 응답 4단계

    @Test
    @DisplayName("수정_표시명을_지정하면_응답_출처가_operator로_바뀐다")
    void updateToOperator() {
        // given — 관제 수신명만 있던 행(수정 전 출처는 control)
        LsEvntType type = type(null, CONTROL_NM);
        // 수정이 반영된 상태를 재현한다(mock 엔티티라 applyManagement 가 상태를 바꾸지 않는다).
        when(type.applyManagement(OPERATOR_NM, null)).thenAnswer(inv -> {
            lenient().when(type.getOptrIndctNm()).thenReturn(OPERATOR_NM);
            return true;
        });

        // when
        EventTypeAdminResponse res = updated(type, OPERATOR_NM);

        // then — ★수정 응답에도 출처가 실린다(화면이 목록 재조회 없이 이 행을 갱신한다)
        assertThat(res.dsplNm()).isEqualTo(OPERATOR_NM);
        assertThat(res.dsplNmSource()).isEqualTo("operator");
    }

    @Test
    @DisplayName("수정_표시명을_해제하면_응답_출처가_control로_복귀한다")
    void updateBackToControl() {
        // given — 운영자가 정했던 행. 빈 문자열로 해제한다(되돌리기 경로)
        LsEvntType type = type(OPERATOR_NM, CONTROL_NM);
        when(type.applyManagement("", null)).thenAnswer(inv -> {
            lenient().when(type.getOptrIndctNm()).thenReturn(null);
            return true;
        });

        // when
        EventTypeAdminResponse res = updated(type, "");

        // then — 관제 수신명으로 자연 복귀하고 출처도 함께 내려간다
        assertThat(res.dsplNm()).isEqualTo(CONTROL_NM);
        assertThat(res.dsplNmSource()).isEqualTo("control");
    }

    @Test
    @DisplayName("수정_고유_이름이_없는_행의_응답_출처는_category다")
    void updateStaysCategory() {
        // given — 이름 후보가 없는 행에서 수집여부만 토글한다
        LsEvntType type = type(null, null);
        when(repository.findById(CODE)).thenReturn(Optional.of(type));
        when(type.applyManagement(null, "N")).thenReturn(true);

        // when
        EventTypeAdminResponse res = service.update(CODE, new EventTypeUpdateRequest(null, "N"));

        // then
        assertThat(res.dsplNm()).isEqualTo(CATEGORY_NM);
        assertThat(res.dsplNmSource()).isEqualTo("category");
    }

    @Test
    @DisplayName("수정_카테고리명마저_없는_행의_응답_출처는_code다")
    void updateStaysCode() {
        // given — 카테고리 마스터에도 없는 비규격 코드
        when(eventTypeService.categoryNameIndex()).thenReturn(Map.of());
        LsEvntType type = type(null, null);
        when(repository.findById(CODE)).thenReturn(Optional.of(type));
        when(type.applyManagement(null, "N")).thenReturn(true);

        // when
        EventTypeAdminResponse res = service.update(CODE, new EventTypeUpdateRequest(null, "N"));

        // then
        assertThat(res.dsplNm()).isEqualTo(CODE);
        assertThat(res.dsplNmSource()).isEqualTo("code");
    }

    // ---------------------------------------------------------------- 계약

    @Test
    @DisplayName("출처는_네_값_중_하나이며_항상_채워진다")
    void sourceIsAlwaysOneOfFour() {
        // given — 4단계를 모두 거치는 행들
        List<LsEvntType> types = List.of(
                type(OPERATOR_NM, CONTROL_NM),
                type(null, CONTROL_NM),
                type(null, null));
        when(repository.findAll()).thenReturn(types);

        // when
        List<EventTypeAdminResponse> rows = service.list();

        // then — null·대문자·미지 값이 섞이면 화면 분기가 조용히 실패한다
        assertThat(rows).extracting(EventTypeAdminResponse::dsplNmSource)
                .containsExactly("operator", "control", "category");
        assertThat(rows).allSatisfy(r -> assertThat(r.dsplNmSource())
                .isIn("operator", "control", "category", "code"));
    }
}
