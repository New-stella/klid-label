package kr.co.cudo.authoring.stats.report;

import kr.co.cudo.authoring.stats.dto.EventDistributionItem;
import kr.co.cudo.authoring.stats.dto.OverallStatSummaryResponse;
import kr.co.cudo.authoring.stats.dto.WorkerStatSummaryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * API-058 리포트 CSV 본문 작성기 단위 테스트.
 *
 * <p>검증 축: 블록 5개 구성 · 처리현황 4구간 접기 · 0-fill 일별 행 보존 · 이벤트 분포의
 * {@code eventTypeCd} 짝맞춤 · 작업자 6컬럼과 비율 단위(0~100, 이중 곱하기 없음) ·
 * 수식 인젝션 무해화(CWE-1236) · RFC 4180 이스케이프.
 */
class OverallStatReportCsvWriterTest {

    private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 8, 31, 14, 2);

    private final OverallStatReportCsvWriter writer = new OverallStatReportCsvWriter();

    // ------------------------------------------------------------------
    // 픽스처
    // ------------------------------------------------------------------

    private static List<WorkerStatSummaryResponse.DailyCompletion> daily(int days) {
        List<WorkerStatSummaryResponse.DailyCompletion> list = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            list.add(new WorkerStatSummaryResponse.DailyCompletion(
                    String.format("2026-01-%02d", i + 1), i == 0 ? 14L : 0L));
        }
        return list;
    }

    private static OverallStatSummaryResponse summary(
            List<OverallStatSummaryResponse.WorkerRow> workers,
            List<EventDistributionItem> total,
            List<EventDistributionItem> approved,
            List<WorkerStatSummaryResponse.DailyCompletion> daily) {
        return new OverallStatSummaryResponse(
                120340L,
                4820L,
                new OverallStatSummaryResponse.Processing(120L, 300L, 120L, 3110L, 55L),
                total,
                workers,
                86210L,
                3110L,
                approved,
                daily);
    }

    private static OverallStatSummaryResponse defaultSummary() {
        return summary(
                List.of(new OverallStatSummaryResponse.WorkerRow(
                        7L, "홍길동", 320L, 300L, 96.7d, 12L, 71.2d)),
                List.of(new EventDistributionItem("020001", "화재", 1200L),
                        new EventDistributionItem("020002", "쓰러짐", 540L)),
                List.of(new EventDistributionItem("020001", "화재", 820L),
                        new EventDistributionItem("020002", "쓰러짐", 310L)),
                daily(3));
    }

    private static List<String> lines(String csv) {
        return List.of(csv.split("\n", -1));
    }

    /** 블록 제목 행 다음의 n 번째 데이터 행(제목·컬럼행 제외). */
    private static String rowAfter(String csv, String blockTitle, int offset) {
        List<String> lines = lines(csv);
        int idx = lines.indexOf(blockTitle);
        assertThat(idx).as("블록 %s 존재", blockTitle).isNotNegative();
        return lines.get(idx + offset);
    }

    // ------------------------------------------------------------------
    // 구성
    // ------------------------------------------------------------------

    @Test
    @DisplayName("본문은_생성일시_첫행과_블록_5개를_빈줄로_구분해_담는다")
    void writesFiveBlocksSeparatedByBlankLines() {
        String csv = writer.write(defaultSummary(), GENERATED_AT);

        List<String> lines = lines(csv);
        assertThat(lines.get(0)).isEqualTo("전체 구축 현황,2026-08-31 14:02 기준");
        assertThat(csv)
                .contains("[누적 학습데이터]")
                .contains("[처리현황]")
                .contains("[일별 작업량]")
                .contains("[이벤트 유형 분포]")
                .contains("[작업자별 현황]");
        // 블록 제목 앞은 항상 빈 줄 1개.
        for (String title : List.of("[누적 학습데이터]", "[처리현황]", "[일별 작업량]",
                "[이벤트 유형 분포]", "[작업자별 현황]")) {
            assertThat(lines.get(lines.indexOf(title) - 1)).as("%s 앞 빈 줄", title).isEmpty();
        }
        // 블록 순서 고정.
        assertThat(csv.indexOf("[누적 학습데이터]")).isLessThan(csv.indexOf("[처리현황]"));
        assertThat(csv.indexOf("[처리현황]")).isLessThan(csv.indexOf("[일별 작업량]"));
        assertThat(csv.indexOf("[일별 작업량]")).isLessThan(csv.indexOf("[이벤트 유형 분포]"));
        assertThat(csv.indexOf("[이벤트 유형 분포]")).isLessThan(csv.indexOf("[작업자별 현황]"));
        // BOM 은 응답 조립(컨트롤러) 책임이라 본문에는 없다.
        assertThat(csv).doesNotStartWith("﻿");
    }

    @Test
    @DisplayName("누적_학습데이터는_검수완료와_전체를_나란히_담는다")
    void cumulativeBlockKeepsApprovedAndTotalSideBySide() {
        String csv = writer.write(defaultSummary(), GENERATED_AT);

        assertThat(rowAfter(csv, "[누적 학습데이터]", 1)).isEqualTo("구분,검수완료,전체");
        assertThat(rowAfter(csv, "[누적 학습데이터]", 2)).isEqualTo("이미지(장),86210,120340");
        assertThat(rowAfter(csv, "[누적 학습데이터]", 3)).isEqualTo("영상(건),3110,4820");
    }

    @Test
    @DisplayName("처리현황은_화면과_같은_4구간이며_처리중은_진행중과_검수대기의_합이다")
    void processingBlockFoldsIntoFourBuckets() {
        String csv = writer.write(defaultSummary(), GENERATED_AT);

        assertThat(rowAfter(csv, "[처리현황]", 1)).isEqualTo("구분,건수");
        assertThat(rowAfter(csv, "[처리현황]", 2)).isEqualTo("완료,3110");
        // 처리중 = inProgress(300) + reviewPending(120)
        assertThat(rowAfter(csv, "[처리현황]", 3)).isEqualTo("처리중,420");
        assertThat(rowAfter(csv, "[처리현황]", 4)).isEqualTo("대기,120");
        assertThat(rowAfter(csv, "[처리현황]", 5)).isEqualTo("실패,55");
        // 5값을 5행으로 펴지 않는다(화면과 다른 것을 말하게 된다).
        assertThat(rowAfter(csv, "[처리현황]", 6)).isEmpty();
    }

    @Test
    @DisplayName("일별_작업량은_0fill된_전량을_그대로_담는다_0행을_빼지_않는다")
    void dailyBlockKeepsZeroFilledRows() {
        String csv = writer.write(summary(List.of(), List.of(), List.of(), daily(7)), GENERATED_AT);

        assertThat(rowAfter(csv, "[일별 작업량]", 1)).isEqualTo("일자,검수완료건수");
        assertThat(rowAfter(csv, "[일별 작업량]", 2)).isEqualTo("2026-01-01,14");
        assertThat(rowAfter(csv, "[일별 작업량]", 3)).isEqualTo("2026-01-02,0");
        // 데이터 행 수 = 창 길이(7). 마지막 행 다음은 블록 구분 빈 줄.
        assertThat(rowAfter(csv, "[일별 작업량]", 8)).isEqualTo("2026-01-07,0");
        assertThat(rowAfter(csv, "[일별 작업량]", 9)).isEmpty();
    }

    @Test
    @DisplayName("이벤트_분포는_eventTypeCd로_짝지어지고_0건_유형도_남는다")
    void eventDistributionPairsByEventTypeCd() {
        // given — 두 목록의 순서가 서로 다르고 검수완료가 0 인 유형이 섞여 있다.
        OverallStatSummaryResponse s = summary(
                List.of(),
                List.of(new EventDistributionItem("020001", "화재", 1200L),
                        new EventDistributionItem("020002", "쓰러짐", 540L)),
                List.of(new EventDistributionItem("020002", "쓰러짐", 310L),
                        new EventDistributionItem("020001", "화재", 0L)),
                List.of());

        String csv = writer.write(s, GENERATED_AT);

        assertThat(rowAfter(csv, "[이벤트 유형 분포]", 1)).isEqualTo("유형,검수완료,전체");
        // 인덱스 위치가 아니라 코드로 맞춘다 — 위치로 맞췄다면 화재에 310 이 붙는다.
        assertThat(rowAfter(csv, "[이벤트 유형 분포]", 2)).isEqualTo("화재,0,1200");
        assertThat(rowAfter(csv, "[이벤트 유형 분포]", 3)).isEqualTo("쓰러짐,310,540");
    }

    @Test
    @DisplayName("전체목록에_없는_검수완료_유형도_버리지_않고_뒤에_잇는다")
    void eventDistributionKeepsApprovedOnlyCategories() {
        OverallStatSummaryResponse s = summary(
                List.of(),
                List.of(new EventDistributionItem("020001", "화재", 1200L)),
                List.of(new EventDistributionItem("020001", "화재", 820L),
                        new EventDistributionItem("099999", "미등록", 5L)),
                List.of());

        String csv = writer.write(s, GENERATED_AT);

        assertThat(rowAfter(csv, "[이벤트 유형 분포]", 2)).isEqualTo("화재,820,1200");
        assertThat(rowAfter(csv, "[이벤트 유형 분포]", 3)).isEqualTo("미등록,5,0");
    }

    // ------------------------------------------------------------------
    // 작업자 블록
    // ------------------------------------------------------------------

    @Test
    @DisplayName("작업자별_현황은_6컬럼이고_비율은_이미_백분율이라_다시_곱하지_않는다")
    void workerBlockHasSixColumnsAndPercentUnitsAreNotRescaled() {
        String csv = writer.write(defaultSummary(), GENERATED_AT);

        assertThat(rowAfter(csv, "[작업자별 현황]", 1))
                .isEqualTo("작업자,라벨,진행,검수,오토라벨(%),반려율(%)");
        // autoLabelRate 71.2 그대로 · 반려율 = 100 - 96.7 = 3.3
        String row = rowAfter(csv, "[작업자별 현황]", 2);
        assertThat(row).isEqualTo("홍길동,320,12,300,71.2,3.3");
        assertThat(row.split(",")).hasSize(6);
    }

    @Test
    @DisplayName("작업자가_0건이면_예외없이_헤더와_안내문구만_남는다")
    void emptyWorkersProducesHeaderAndNotice() {
        String csv = writer.write(summary(List.of(), List.of(), List.of(), List.of()), GENERATED_AT);

        assertThat(rowAfter(csv, "[작업자별 현황]", 1))
                .isEqualTo("작업자,라벨,진행,검수,오토라벨(%),반려율(%)");
        assertThat(rowAfter(csv, "[작업자별 현황]", 2)).isEqualTo("작업자 통계가 없습니다");
    }

    @Test
    @DisplayName("비율은_소수1자리이고_로케일_천단위_구분자를_넣지_않는다")
    void numbersUseFixedFormatWithoutGrouping() {
        OverallStatSummaryResponse s = summary(
                List.of(new OverallStatSummaryResponse.WorkerRow(
                        1L, "김작업", 1234567L, 1000L, 0.0d, 0L, 100.0d)),
                List.of(), List.of(), List.of());

        String csv = writer.write(s, GENERATED_AT);

        assertThat(rowAfter(csv, "[작업자별 현황]", 2)).isEqualTo("김작업,1234567,0,1000,100.0,100.0");
    }

    // ------------------------------------------------------------------
    // 안전 규약
    // ------------------------------------------------------------------

    @Test
    @DisplayName("수식으로_시작하는_이름은_무해화된다_CWE_1236")
    void neutralizesFormulaLeadingCharacters() {
        OverallStatSummaryResponse s = summary(
                List.of(new OverallStatSummaryResponse.WorkerRow(1L, "=1+1", 1L, 1L, 0.0d, 0L, 0.0d),
                        new OverallStatSummaryResponse.WorkerRow(2L, "+82", 1L, 1L, 0.0d, 0L, 0.0d),
                        new OverallStatSummaryResponse.WorkerRow(3L, "-2+3", 1L, 1L, 0.0d, 0L, 0.0d),
                        new OverallStatSummaryResponse.WorkerRow(4L, "@SUM(A1)", 1L, 1L, 0.0d, 0L, 0.0d)),
                List.of(new EventDistributionItem("020001", "=cmd|'/c calc'!A1", 1L)),
                List.of(),
                List.of());

        String csv = writer.write(s, GENERATED_AT);

        assertThat(rowAfter(csv, "[작업자별 현황]", 2)).startsWith("'=1+1,");
        assertThat(rowAfter(csv, "[작업자별 현황]", 3)).startsWith("'+82,");
        assertThat(rowAfter(csv, "[작업자별 현황]", 4)).startsWith("'-2+3,");
        assertThat(rowAfter(csv, "[작업자별 현황]", 5)).startsWith("'@SUM(A1),");
        assertThat(rowAfter(csv, "[이벤트 유형 분포]", 2)).startsWith("'=cmd|'/c calc'!A1,");
        // 숫자 셀은 무해화 대상이 아니다 — 앞에 작은따옴표가 붙으면 Excel 재가공이 깨진다.
        assertThat(rowAfter(csv, "[누적 학습데이터]", 2)).isEqualTo("이미지(장),86210,120340");
    }

    @Test
    @DisplayName("쉼표_큰따옴표_개행이_든_값은_RFC4180으로_이스케이프된다")
    void escapesPerRfc4180() {
        OverallStatSummaryResponse s = summary(
                List.of(new OverallStatSummaryResponse.WorkerRow(
                        1L, "홍길동, 팀장", 1L, 1L, 0.0d, 0L, 0.0d)),
                List.of(new EventDistributionItem("020001", "화재 \"대형\"", 1L),
                        new EventDistributionItem("020002", "침수\n범람", 2L)),
                List.of(),
                List.of());

        String csv = writer.write(s, GENERATED_AT);

        assertThat(csv).contains("\"홍길동, 팀장\",1,0,1,0.0,100.0");
        assertThat(csv).contains("\"화재 \"\"대형\"\"\",0,1");
        assertThat(csv).contains("\"침수\n범람\",0,2");
    }

    @Test
    @DisplayName("표시명이_비면_이벤트코드로_대체하고_행을_잃지_않는다")
    void fallsBackToEventCodeWhenLabelBlank() {
        OverallStatSummaryResponse s = summary(
                List.of(),
                List.of(new EventDistributionItem("020003", null, 7L),
                        new EventDistributionItem("020004", "  ", 3L)),
                List.of(),
                List.of());

        String csv = writer.write(s, GENERATED_AT);

        assertThat(rowAfter(csv, "[이벤트 유형 분포]", 2)).isEqualTo("020003,0,7");
        assertThat(rowAfter(csv, "[이벤트 유형 분포]", 3)).isEqualTo("020004,0,3");
    }

    @Test
    @DisplayName("null_셀은_조용히_넘어가지_않고_예외로_드러난다_fail_loud")
    void nullCellFailsLoudInsteadOfSilentFallback() {
        // given — 작업자명이 null 인 행. 현재 스키마상 도달 경로는 닫혀 있지만(USER_NM 은
        //   NOT NULL DEFAULT ''), 그 전제가 깨지면 조용한 빈 셀이 아니라 드러나야 한다.
        //   빈 문자열 폴백은 "이름 없는 작업자 행"을 정상처럼 내보내 원인 추적을 막는다.
        OverallStatSummaryResponse s = summary(
                List.of(new OverallStatSummaryResponse.WorkerRow(1L, null, 1L, 1L, 0.0d, 0L, 0.0d)),
                List.of(), List.of(), List.of());

        assertThatThrownBy(() -> writer.write(s, GENERATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CSV 셀 타입");
    }
}
