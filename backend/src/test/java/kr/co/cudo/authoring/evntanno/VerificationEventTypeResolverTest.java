package kr.co.cudo.authoring.evntanno;

import kr.co.cudo.authoring.evntanno.service.MarkingSelectedQuestionReader;
import kr.co.cudo.authoring.evntanno.service.VerificationEventTypeResolver;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.IngestSourceRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 * {@link VerificationEventTypeResolver} 단위 시험 — <b>검증 이벤트 유형 조달 순서</b>의 계약.
 *
 * <h3>이 파일이 고정하는 것</h3>
 * <p>확정 순서는 <b>「관제 인입 값 → 마킹에서 작업자가 고른 값 → 없음」</b>이다. 세 갈래를 <b>각각</b>
 * 못박는다 — 1순위만 있으면 관제 미수신 영상이 아무것도 못 받고, 2순위만 있으면 관제 값이 있는데도
 * 작업자 선택이 이길 수 있으며, 마지막 없음을 지우면 값을 지어내게 된다.
 *
 * <p>★ <b>왜 순서를 한 자리에 모았나</b> — 소비자가 여럿(위탁 조립·어노테이션 초안 적재)이라 소비자마다
 * 다시 적으면 <b>한쪽만 고쳐도 아무 시험이 죽지 않는다</b>. 이 저장소는 그 형태(판정 사본이 앞단에서
 * 가로채기)를 실측으로 겪었다.
 */
class VerificationEventTypeResolverTest {

    private static final long RAW_SN = 8300L;

    private IngestSourceRepository ingestSourceRepository;
    private LsMarkingRepository markingRepository;
    private VerificationEventTypeResolver resolver;

    @BeforeEach
    void setUp() {
        ingestSourceRepository = mock(IngestSourceRepository.class);
        markingRepository = mock(LsMarkingRepository.class);
        // 행 선택 규칙은 리더가 소유한다 — 목으로 갈아끼우면 「같은 행에서 온다」가 시험 밖으로 나간다.
        resolver = new VerificationEventTypeResolver(
                ingestSourceRepository, new MarkingSelectedQuestionReader(markingRepository));

        when(markingRepository.findByRawSnAndSttsCdIn(anyLong(), any())).thenReturn(List.of());
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(anyLong())).thenReturn(List.of());
    }

    // ------------------------------------------------------------------ 1순위 (관제 인입)

    @Test
    @DisplayName("★관제_인입_값이_있으면_그것이_1순위다")
    void ingestValueWins() {
        stubIngest("fire");
        stubActiveMarkingType("flooding");

        assertThat(resolver.resolve(RAW_SN)).isEqualTo("fire");
    }

    @Test
    @DisplayName("★관제_값이_있으면_마킹은_아예_읽지_않는다")
    void doesNotReadMarkingWhenIngestHasValue() {
        stubIngest("fire");

        resolver.resolve(RAW_SN);

        verify(markingRepository, never()).findByRawSnAndSttsCdIn(anyLong(), any());
        verify(markingRepository, never()).findByRawSnOrderByRegDtDescMarkingSnDesc(anyLong());
    }

    // ------------------------------------------------------------------ 2순위 (마킹 선택값)

    @Test
    @DisplayName("★관제_값이_없으면_마킹에서_작업자가_고른_유형을_쓴다")
    void fallsBackToMarkingSelection() {
        stubIngest(null);
        stubActiveMarkingType("flooding");

        assertThat(resolver.resolve(RAW_SN))
                .as("이 폴백이 없으면 관제 미수신 영상은 유형을 영영 얻지 못한다")
                .isEqualTo("flooding");
    }

    @Test
    @DisplayName("인입_행_자체가_없어도_터지지_않고_마킹_선택값으로_내려간다")
    void fallsBackWhenIngestRowMissing() {
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(null);
        stubActiveMarkingType("fall");

        assertThat(resolver.resolve(RAW_SN)).isEqualTo("fall");
    }

    @Test
    @DisplayName("마킹_행을_고르는_규칙은_리더가_소유한다_활성_우선")
    void usesReaderRowSelectionRule() {
        stubIngest(null);
        stubActiveMarkingType("flooding");
        stubLatestMarkingType("violence");

        assertThat(resolver.resolve(RAW_SN))
                .as("최신 마킹은 활성이 없을 때의 폴백이지 1순위가 아니다")
                .isEqualTo("flooding");
        verify(markingRepository).findByRawSnAndSttsCdIn(RAW_SN, LsMarking.ACTIVE_STATUSES);
    }

    // ------------------------------------------------------------------ 3순위 (없음)

    @Test
    @DisplayName("★어느_쪽에도_없으면_값을_지어내지_않는다")
    void returnsNullWhenNeitherSourceHasValue() {
        stubIngest(null);

        assertThat(resolver.resolve(RAW_SN)).isNull();
    }

    @Test
    @DisplayName("마킹이_유형을_고르지_않았으면_없음이다_이웃_행에서_주워_오지_않는다")
    void returnsNullWhenChosenMarkingHasNoType() {
        stubIngest(null);
        stubActiveMarkingType(null);
        stubLatestMarkingType("violence");

        assertThat(resolver.resolve(RAW_SN)).isNull();
    }

    @Test
    @DisplayName("영상번호가_없으면_조회하지_않고_없음이다")
    void returnsNullWithoutQueryingWhenRawSnMissing() {
        assertThat(resolver.resolve(null)).isNull();
        verifyNoInteractions(ingestSourceRepository, markingRepository);
    }

    // ------------------------------------------------------------------ 정규화 (단일 진실원 재사용)

    @Test
    @DisplayName("★두_조달처_모두_같은_정규화를_탄다_표기가_갈리지_않는다")
    void normalizesBothSourcesTheSameWay() {
        stubIngest("  FIRE ");
        assertThat(resolver.resolve(RAW_SN)).isEqualTo("fire");

        stubIngest(null);
        stubActiveMarkingType("  FLOODING ");
        assertThat(resolver.resolve(RAW_SN))
                .as("정규화 규칙을 복제하면 인입이 실어 보낸 표기와 작업자가 고른 값이 조용히 어긋난다")
                .isEqualTo("flooding");
    }

    @Test
    @DisplayName("공백뿐인_인입_값은_없는_것으로_보고_마킹_선택값으로_내려간다")
    void blankIngestValueIsTreatedAsAbsent() {
        stubIngest("   ");
        stubActiveMarkingType("smoke");

        assertThat(resolver.resolve(RAW_SN)).isEqualTo("smoke");
    }

    @Test
    @DisplayName("우리가_아는_유형_목록_밖의_값도_그대로_돌려준다_사전차단_폐기")
    void doesNotJudgeAgainstKnownTypeList() {
        stubIngest(null);
        stubActiveMarkingType("unknown_event");

        assertThat(resolver.resolve(RAW_SN)).isEqualTo("unknown_event");
    }

    // ------------------------------------------------------------------ helpers

    private void stubIngest(String vrfcEvntTypeCd) {
        IngestSourceRow row = mock(IngestSourceRow.class);
        when(row.getVrfcEvntTypeCd()).thenReturn(vrfcEvntTypeCd);
        when(ingestSourceRepository.findSourceMeta(RAW_SN)).thenReturn(row);
    }

    private void stubActiveMarkingType(String vrfcEvntTypeCd) {
        // ⚠ 목 생성을 when(...) 인자 안에서 하면 미완료 스터빙으로 터진다 — 먼저 만들어 둔다.
        LsMarking marking = markingWithType(vrfcEvntTypeCd);
        when(markingRepository.findByRawSnAndSttsCdIn(eq(RAW_SN), any())).thenReturn(List.of(marking));
    }

    private void stubLatestMarkingType(String vrfcEvntTypeCd) {
        LsMarking marking = markingWithType(vrfcEvntTypeCd);
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(RAW_SN)).thenReturn(List.of(marking));
    }

    private static LsMarking markingWithType(String vrfcEvntTypeCd) {
        LsMarking marking = mock(LsMarking.class);
        when(marking.getVrfcEvntTypeCd()).thenReturn(vrfcEvntTypeCd);
        return marking;
    }
}
