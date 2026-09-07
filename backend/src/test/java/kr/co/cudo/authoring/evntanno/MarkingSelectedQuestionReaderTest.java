package kr.co.cudo.authoring.evntanno;

import kr.co.cudo.authoring.evntanno.service.MarkingSelectedQuestionReader;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link MarkingSelectedQuestionReader} 단위 시험 — 이벤트 어노테이션 질문 칸의 <b>1순위 조달값</b>을
 * 어느 마킹 행에서 읽는지의 계약.
 *
 * <h3>이 파일이 고정하는 것</h3>
 * <p>확정 규칙은 <b>「활성 마킹 우선, 없으면 그 영상의 최신 마킹 한 건」</b>이다. 2단인 이유는 두 위탁
 * 창구의 콜백 도착 순서가 보장되지 않는데 <b>마킹 상태를 전이시키는 것은 묘사 축뿐</b>이라, 활성만
 * 보면 묘사가 먼저 도착한 경우 활성 마킹이 사라져 <b>같은 영상인데도 도착 순서에 따라 기록되는
 * 질문이 달라지기</b> 때문이다. 그래서 「활성 우선」과 「최신 폴백」을 <b>각각</b> 못박는다 — 둘 중
 * 하나만 있으면 규칙이 아니라 우연이다.
 *
 * <p>★ <b>읽는 선택값이 둘</b>(질문·검증 이벤트 유형)이라, 두 값이 <b>같은 행</b>에서 나오는지도 함께
 * 못박는다 — 행 선택 규칙이 둘이 되면 같은 영상에서 질문과 유형이 서로 다른 마킹에서 올 수 있다.
 */
class MarkingSelectedQuestionReaderTest {

    private static final long RAW_SN = 7100L;
    private static final long ACTIVE_QSTN_SN = 11L;
    private static final long LATEST_QSTN_SN = 22L;

    private LsMarkingRepository markingRepository;
    private MarkingSelectedQuestionReader reader;

    @BeforeEach
    void setUp() {
        markingRepository = mock(LsMarkingRepository.class);
        reader = new MarkingSelectedQuestionReader(markingRepository);
        when(markingRepository.findByRawSnAndSttsCdIn(anyLong(), any())).thenReturn(List.of());
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(anyLong())).thenReturn(List.of());
    }

    // ------------------------------------------------------------------ 수용기준 3 (활성 우선)

    @Test
    @DisplayName("★활성_마킹이_있으면_그_마킹의_선택값을_읽는다")
    void readsFromActiveMarking() {
        stubActive(marking(ACTIVE_QSTN_SN));

        assertThat(reader.findSelectedQuestionSn(RAW_SN)).isEqualTo(ACTIVE_QSTN_SN);
    }

    @Test
    @DisplayName("★활성_마킹과_과거_마킹이_함께_있으면_활성_쪽을_읽는다")
    void prefersActiveOverLatest() {
        stubActive(marking(ACTIVE_QSTN_SN));
        stubOrdered(marking(LATEST_QSTN_SN), marking(33L));

        assertThat(reader.findSelectedQuestionSn(RAW_SN))
                .as("최신 마킹은 활성이 없을 때의 폴백이지 1순위가 아니다")
                .isEqualTo(ACTIVE_QSTN_SN);
    }

    @Test
    @DisplayName("★활성_마킹이_있으면_최신_조회는_아예_돌지_않는다")
    void doesNotQueryLatestWhenActiveExists() {
        stubActive(marking(ACTIVE_QSTN_SN));

        reader.findSelectedQuestionSn(RAW_SN);

        verify(markingRepository, never()).findByRawSnOrderByRegDtDescMarkingSnDesc(anyLong());
    }

    @Test
    @DisplayName("활성_판정은_마킹_도메인의_활성_상태_집합을_그대로_쓴다")
    void usesMarkingDomainActiveStatuses() {
        reader.findSelectedQuestionSn(RAW_SN);

        verify(markingRepository).findByRawSnAndSttsCdIn(RAW_SN, LsMarking.ACTIVE_STATUSES);
    }

    // ------------------------------------------------------------------ 수용기준 2 (최신 폴백)

    @Test
    @DisplayName("★활성_마킹이_없고_과거_마킹이_여럿이면_최신_한_건의_선택값을_읽는다")
    void fallsBackToLatestMarking() {
        stubOrdered(marking(LATEST_QSTN_SN), marking(33L), marking(44L));

        assertThat(reader.findSelectedQuestionSn(RAW_SN))
                .as("조회가 최신 먼저로 정렬돼 오므로 첫 항목이 최신 한 건이다")
                .isEqualTo(LATEST_QSTN_SN);
    }

    // ------------------------------------------------------------------ 수용기준 4·6 (값이 없을 때)

    @Test
    @DisplayName("마킹이_아예_없으면_선택값이_없다_지어내지_않는다")
    void returnsNullWhenNoMarkingAtAll() {
        assertThat(reader.findSelectedQuestionSn(RAW_SN)).isNull();
    }

    @Test
    @DisplayName("★규칙이_정한_행이_질문을_고르지_않았으면_이웃_행에서_주워_오지_않는다")
    void doesNotBorrowFromAnotherRowWhenChosenRowHasNoSelection() {
        stubActive(marking(null));
        stubOrdered(marking(LATEST_QSTN_SN));

        assertThat(reader.findSelectedQuestionSn(RAW_SN))
                .as("활성 마킹이 고르지 않았다는 것도 사실이다 — 과거 행 값으로 대신하지 않는다")
                .isNull();
    }

    @Test
    @DisplayName("영상번호가_없으면_조회하지_않고_선택값도_없다")
    void returnsNullWithoutQueryingWhenRawSnMissing() {
        assertThat(reader.findSelectedQuestionSn(null)).isNull();
        verifyNoInteractions(markingRepository);
    }

    // ------------------------------------------------------------------ 방어 (도달하지 않아야 할 형상)

    @Test
    @DisplayName("활성_마킹이_둘_이상이어도_터지지_않고_한_건을_고른다")
    void toleratesMoreThanOneActiveMarking() {
        stubActive(marking(ACTIVE_QSTN_SN), marking(99L));

        assertThat(reader.findSelectedQuestionSn(RAW_SN)).isEqualTo(ACTIVE_QSTN_SN);
    }

    // ------------------------------------------------- 선택값 둘은 같은 행에서 나온다

    @Test
    @DisplayName("★질문과_검증이벤트유형이_같은_마킹_행에서_나온다")
    void readsBothSelectionsFromTheSameRow() {
        stubActive(marking(ACTIVE_QSTN_SN, "fire"));
        stubOrdered(marking(LATEST_QSTN_SN, "flooding"));

        assertThat(reader.findSelectedQuestionSn(RAW_SN)).isEqualTo(ACTIVE_QSTN_SN);
        assertThat(reader.findSelectedVrfcEvntType(RAW_SN))
                .as("행 선택 규칙이 둘이 되면 같은 영상에서 질문과 유형이 서로 다른 마킹에서 온다")
                .isEqualTo("fire");
    }

    @Test
    @DisplayName("유형도_최신_폴백을_똑같이_탄다")
    void typeAlsoFallsBackToLatestMarking() {
        stubOrdered(marking(LATEST_QSTN_SN, "flooding"), marking(33L, "fire"));

        assertThat(reader.findSelectedVrfcEvntType(RAW_SN)).isEqualTo("flooding");
    }

    @Test
    @DisplayName("마킹이_유형을_고르지_않았으면_지어내지_않는다")
    void returnsNullWhenChosenRowHasNoType() {
        stubActive(marking(ACTIVE_QSTN_SN, null));
        stubOrdered(marking(LATEST_QSTN_SN, "flooding"));

        assertThat(reader.findSelectedVrfcEvntType(RAW_SN)).isNull();
    }

    @Test
    @DisplayName("영상번호가_없으면_유형_조회도_하지_않는다")
    void returnsNullTypeWithoutQueryingWhenRawSnMissing() {
        assertThat(reader.findSelectedVrfcEvntType(null)).isNull();
        verifyNoInteractions(markingRepository);
    }

    // ------------------------------------------------------------------ helpers

    private void stubActive(LsMarking... markings) {
        when(markingRepository.findByRawSnAndSttsCdIn(eq(RAW_SN), any()))
                .thenReturn(Arrays.asList(markings));
    }

    private void stubOrdered(LsMarking... markings) {
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(RAW_SN))
                .thenReturn(Arrays.asList(markings));
    }

    private static LsMarking marking(Long vrfcEvntQstnSn) {
        return marking(vrfcEvntQstnSn, null);
    }

    private static LsMarking marking(Long vrfcEvntQstnSn, String vrfcEvntTypeCd) {
        LsMarking m = mock(LsMarking.class);
        when(m.getVrfcEvntQstnSn()).thenReturn(vrfcEvntQstnSn);
        when(m.getVrfcEvntTypeCd()).thenReturn(vrfcEvntTypeCd);
        return m;
    }
}
