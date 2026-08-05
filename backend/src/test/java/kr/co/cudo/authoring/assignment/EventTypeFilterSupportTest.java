package kr.co.cudo.authoring.assignment;

import kr.co.cudo.authoring.assignment.dto.EventTypeOptionsResponse;
import kr.co.cudo.authoring.assignment.service.EventTypeFilterSupport;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 작업목록·배정목록 공용 이벤트유형 판정기({@link EventTypeFilterSupport}) 단위 테스트 (R6).
 *
 * <h3>고정하는 계약</h3>
 * <ul>
 *   <li><b>접기</b> — 같은 표시명 그룹의 코드들은 대표코드 1건으로 접힌다(드롭다운 중복 제거).</li>
 *   <li><b>미등록 코드 보존</b> — 접을 수 없는 코드는 <b>원문 그대로</b> 남는다. 버리면 그 코드의
 *       영상이 필터로 도달 불가능해진다(조용한 데이터 손실).</li>
 *   <li><b>절단은 접은 뒤</b> — 접기 → distinct → 정렬 → 상한. 접기 전에 자르면 items 손실 +
 *       {@code truncated} 오안내가 난다.</li>
 *   <li><b>필터 확장</b> — 대표/비대표 어느 코드로 필터해도 그룹 전체로 확장된다(북마크 하위호환).</li>
 * </ul>
 */
class EventTypeFilterSupportTest {

    private EventTypeService eventTypeService;
    private EventTypeFilterSupport support;

    @BeforeEach
    void setUp() {
        eventTypeService = mock(EventTypeService.class);
        // 기본 스텁 — 미등록(접기 불가). 그룹이 필요한 테스트에서만 개별 재스텁한다.
        lenient().when(eventTypeService.filterKeyOf(anyString())).thenReturn(Optional.empty());
        lenient().when(eventTypeService.codesForFilterKey(anyString())).thenReturn(Set.of());
        support = new EventTypeFilterSupport(eventTypeService);
    }

    /** 침수(범람) 3종이 한 그룹(대표 EV01000101), 화재 1종이 독립 그룹인 마스터 상태. */
    private void seedFloodGroup() {
        Set<String> flood = Set.of("EV01000101", "EV01000102", "EV01000103");
        for (String code : flood) {
            when(eventTypeService.filterKeyOf(code)).thenReturn(Optional.of("EV01000101"));
            when(eventTypeService.codesForFilterKey(code)).thenReturn(flood);
        }
        when(eventTypeService.filterKeyOf("EV02000101")).thenReturn(Optional.of("EV02000101"));
        when(eventTypeService.codesForFilterKey("EV02000101")).thenReturn(Set.of("EV02000101"));
    }

    // ------------------------------------------------------------------- 접기

    @Test
    @DisplayName("같은_표시명_그룹의_코드는_옵션에서_대표코드_1건으로_접힌다")
    void 같은_표시명_그룹의_코드는_옵션에서_대표코드_1건으로_접힌다() {
        // given — 데이터에 침수 3종·화재 1종이 실재
        seedFloodGroup();

        // when
        EventTypeOptionsResponse response = support.foldOptions(
                List.of("EV01000101", "EV01000102", "EV01000103", "EV02000101"), 500, "Test");

        // then — 침수 3종은 옵션 1건(대표코드)으로 접히고 오름차순이 유지된다
        assertThat(response.items()).containsExactly("EV01000101", "EV02000101");
        assertThat(response.truncated()).isFalse();
    }

    @Test
    @DisplayName("데이터에_비대표코드만_있어도_옵션은_대표코드로_노출된다")
    void 데이터에_비대표코드만_있어도_옵션은_대표코드로_노출된다() {
        // given — 그룹의 대표코드(EV01000101) 영상은 아직 없고 비대표 코드만 실재한다
        seedFloodGroup();

        // when
        EventTypeOptionsResponse response = support.foldOptions(List.of("EV01000103"), 500, "Test");

        // then — 대표코드로 노출된다(필터 확장이 그룹 전체를 매칭하므로 0건이 되지 않는다)
        assertThat(response.items()).containsExactly("EV01000101");
    }

    @Test
    @DisplayName("미등록_비규격코드는_옵션에서_제거되지_않고_원문으로_남는다")
    void 미등록_비규격코드는_옵션에서_제거되지_않고_원문으로_남는다() {
        // given — INTRUSION 같은 비규격 코드는 마스터에 없어 접을 수 없다
        seedFloodGroup();

        // when
        EventTypeOptionsResponse response = support.foldOptions(
                List.of("EV01000102", "INTRUSION"), 500, "Test");

        // then — 버리면 그 코드의 영상이 필터로 도달 불가능해진다(조용한 데이터 손실)
        assertThat(response.items()).containsExactly("EV01000101", "INTRUSION");
    }

    @Test
    @DisplayName("빈_스캔결과는_빈_옵션이며_truncated는_false다")
    void 빈_스캔결과는_빈_옵션이며_truncated는_false다() {
        EventTypeOptionsResponse response = support.foldOptions(List.of(), 500, "Test");

        assertThat(response.items()).isEmpty();
        assertThat(response.truncated()).isFalse();
    }

    // ----------------------------------------------------------------- 절단 순서

    @Test
    @DisplayName("접기_후_개수가_상한_이하면_truncated는_false다")
    void 접기_후_개수가_상한_이하면_truncated는_false다() {
        // given — 상한 2, 실재 코드 3건이지만 그중 3종이 아니라 침수 3종이 한 그룹이라 접으면 1건이다.
        //         접기 <전에> 상한을 적용하는 구현이면 여기서 truncated=true 가 되어 오안내가 난다.
        seedFloodGroup();

        EventTypeOptionsResponse response = support.foldOptions(
                List.of("EV01000101", "EV01000102", "EV01000103"), 2, "Test");

        assertThat(response.items()).containsExactly("EV01000101");
        assertThat(response.truncated())
                .as("접은 결과가 상한 이하인데 truncated=true 면 화면에 '일부만 표시' 오안내가 뜬다")
                .isFalse();
    }

    @Test
    @DisplayName("접기_후에도_상한을_넘으면_truncated는_true다")
    void 접기_후에도_상한을_넘으면_truncated는_true다() {
        // given — 접히지 않는(미등록) 코드 상한+1 종
        int max = 5;
        List<String> scanned = new ArrayList<>();
        for (int i = 0; i <= max; i++) {
            scanned.add(String.format("EVT-%03d", i));
        }

        EventTypeOptionsResponse response = support.foldOptions(scanned, max, "Test");

        assertThat(response.items()).hasSize(max);
        assertThat(response.items()).first().isEqualTo("EVT-000");
        assertThat(response.items()).last().isEqualTo("EVT-004");
        assertThat(response.items()).doesNotContain("EVT-005");
        assertThat(response.truncated()).isTrue();
    }

    /**
     * <b>스캔 상한은 {@code max + 1} 이 아니다</b> — 접기가 코드 수를 줄이므로 그만큼 더 읽어야
     * "스캔은 잘렸는데 접은 결과는 상한 이하"라 절단을 못 알아채는 구간이 사라진다.
     *
     * <p>여분 = 노출 그룹 멤버 코드 수({@code validFilterKeys}). 접기로 줄어드는 개수가 이 값을 넘을 수
     * 없으므로, 스캔이 잘리면 접은 결과는 <b>반드시</b> 상한을 넘어 {@code truncated=true} 가 된다.
     */
    @Test
    @DisplayName("스캔_상한은_접기로_줄어드는_최대개수만큼_상한보다_크다")
    void 스캔_상한은_접기로_줄어드는_최대개수만큼_상한보다_크다() {
        when(eventTypeService.validFilterKeys())
                .thenReturn(Set.of("EV01000101", "EV01000102", "EV01000103", "EV02000101"));

        assertThat(support.scanLimitFor(500))
                .as("max+1 만 읽으면 '스캔은 잘렸는데 접은 결과는 상한 이하' 구간에서 절단이 숨는다")
                .isEqualTo(505);
    }

    @Test
    @DisplayName("마스터가_비어도_스캔_상한은_최소_상한_더하기_1_이다")
    void 마스터가_비어도_스캔_상한은_최소_상한_더하기_1_이다() {
        when(eventTypeService.validFilterKeys()).thenReturn(Set.of());

        assertThat(support.scanLimitFor(500)).isEqualTo(501);
    }

    // --------------------------------------------------------------- 필터 확장

    @Test
    @DisplayName("대표코드로_필터하면_그룹_전체_코드로_확장된다")
    void 대표코드로_필터하면_그룹_전체_코드로_확장된다() {
        seedFloodGroup();

        assertThat(support.matchCodesFor("EV01000101"))
                .containsExactlyInAnyOrder("EV01000101", "EV01000102", "EV01000103");
    }

    @Test
    @DisplayName("비대표코드로_필터해도_그룹_전체_코드로_확장된다")
    void 비대표코드로_필터해도_그룹_전체_코드로_확장된다() {
        // 그룹 도입 이전에 만들어진 북마크 URL(?eventTypeCd=EV01000103)이 0건이 되면 안 된다.
        seedFloodGroup();

        assertThat(support.matchCodesFor("EV01000103"))
                .containsExactlyInAnyOrder("EV01000101", "EV01000102", "EV01000103");
    }

    @Test
    @DisplayName("미등록_키는_원문_단건으로_폴백되어_필터가_0건이_되지_않는다")
    void 미등록_키는_원문_단건으로_폴백되어_필터가_0건이_되지_않는다() {
        assertThat(support.matchCodesFor("INTRUSION")).containsExactly("INTRUSION");
        assertThat(support.matchCodesFor("EVT-FIRE")).containsExactly("EVT-FIRE");
    }

    @Test
    @DisplayName("필터키가_없으면_빈집합_필터_미적용이다")
    void 필터키가_없으면_빈집합_필터_미적용이다() {
        assertThat(support.matchCodesFor(null)).isEmpty();
        assertThat(support.matchCodesFor("   ")).isEmpty();
    }
}
