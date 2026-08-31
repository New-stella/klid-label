package kr.co.cudo.authoring.stats.report;

import kr.co.cudo.authoring.stats.dto.EventDistributionItem;
import kr.co.cudo.authoring.stats.dto.OverallStatSummaryResponse;
import kr.co.cudo.authoring.stats.dto.WorkerStatSummaryResponse;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SCR-STAT-002 「전체 구축 현황」 리포트 CSV 본문 작성기.
 *
 * <p>{@code GET /v1/stats/report} 응답 본문을 조립한다. 입력은 <b>{@code getOverallSummary()}
 * 응답 하나뿐</b>이며 이 클래스는 집계를 스스로 유도하지 않는다 — 화면
 * ({@code GET /v1/stats/overall})과 리포트가 같은 수치를 말해야 하기 때문이다.
 * 리포트 전용 집계를 여기서 다시 계산하면 두 번째 진실원이 된다.
 *
 * <p>본문 구성(블록 사이는 빈 줄 1개, 블록 제목은 대괄호 한 칸):
 * <pre>
 * 전체 구축 현황,{생성 기준 일시}
 *
 * [누적 학습데이터]   구분 / 검수완료 / 전체
 * [처리현황]         구분 / 건수      — 완료·처리중·대기·실패 4구간
 * [일별 작업량]      일자 / 검수완료건수 — 0-fill 전량(창 길이 = period)
 * [이벤트 유형 분포]  유형 / 검수완료 / 전체 — eventTypeCd 로 짝지음
 * [작업자별 현황]     작업자 / 라벨 / 진행 / 검수 / 오토라벨(%) / 반려율(%)
 * </pre>
 *
 * <p><b>BOM 은 여기서 붙이지 않는다</b> — 응답 조립(컨트롤러)의 책임이다.
 *
 * <p>안전 규약 두 가지를 <b>모든 셀</b>이 {@link #row(StringBuilder, Object...)} 하나를 거쳐
 * 강제로 적용받는다:
 * <ul>
 *   <li><b>수식 인젝션 무해화(CWE-1236)</b> — 작업자명·이벤트 표시명처럼 사람이 쓴 문자열이
 *       {@code = + - @} 탭·캐리지리턴으로 시작하면 Excel 이 수식으로 해석·실행한다. 문자열
 *       셀에만 작은따옴표를 앞세워 무해화한다. <b>숫자 셀은 대상이 아니다</b>(무해화하면
 *       Excel 재가공이 텍스트로 깨진다).</li>
 *   <li><b>RFC 4180 이스케이프</b> — 값에 {@code , " } 개행이 있으면 큰따옴표로 감싸고 내부
 *       큰따옴표는 이중화한다. 이벤트 표시명은 운영자가 관리 화면에서 바꿀 수 있어 쉼표가
 *       들어올 수 있다.</li>
 * </ul>
 *
 * @design API-058
 */
@Component
public class OverallStatReportCsvWriter {

    /**
     * 파일 첫 행의 생성 기준 일시 포맷.
     *
     * <p>{@link Locale#ROOT} 고정 — 숫자 셀({@link #format(Object)})과 <b>같은 규칙</b>이다.
     * 미지정이면 JVM 기본 로케일의 {@code DecimalStyle} 을 타서 비 ASCII 숫자 로케일
     * (예 {@code -u-nu-thai})에서 첫 행 숫자 표기만 달라진다. 한 파일 안에서 로케일 규칙이
     * 갈리지 않게 한다.
     */
    private static final DateTimeFormatter GENERATED_AT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    /** Excel 이 수식으로 해석하는 선행 문자 (CWE-1236). */
    private static final String FORMULA_LEADING_CHARS = "=+-@\t\r";

    /**
     * 행 구분자 — <b>LF 고정이며 CRLF 로 바꾸지 않는다.</b>
     *
     * <p>RFC 4180 은 CRLF 를 규정하지만 이 응답의 계약({@code API-058} 200 example)이 LF 라
     * 계약과 일치시킨 것이다. Excel·프론트 모두 LF 로 정상 동작한다. 바꾸려면 설계(ITEM)를
     * 먼저 고쳐야 한다 — "RFC 4180 위반"으로 보고 코드만 돌리지 말 것.
     */
    private static final String LINE = "\n";

    /** 작업자 배정 행이 0 건일 때 화면(WorkerStatsTable)과 같은 안내 문구. */
    private static final String NO_WORKER_NOTICE = "작업자 통계가 없습니다";

    /**
     * 리포트 본문을 만든다.
     *
     * @param summary     {@code StatsService.getOverallSummary(...)} 응답. 이 값 밖에서 수치를
     *                    조달하지 않는다.
     * @param generatedAt 파일 첫 행에 담을 생성 기준 일시.
     */
    public String write(OverallStatSummaryResponse summary, LocalDateTime generatedAt) {
        // 본문을 String 연결로 누적하면 YEAR(365행) + 작업자/이벤트 행에서 중간 문자열이
        // 제곱으로 늘어난다(CWE-770). 한 버퍼에 append 한다.
        StringBuilder sb = new StringBuilder(4096);

        row(sb, "전체 구축 현황", GENERATED_AT_FMT.format(generatedAt) + " 기준");

        writeCumulativeBlock(sb, summary);
        writeProcessingBlock(sb, summary.processing());
        writeDailyBlock(sb, summary.dailyCounts());
        writeEventDistributionBlock(sb, summary.eventDistribution(), summary.approvedEventDistribution());
        writeWorkerBlock(sb, summary.workers());

        return sb.toString();
    }

    /** [누적 학습데이터] — 검수완료 기준과 전체 기준을 나란히 둔다(둘을 하나로 섞지 않는다). */
    private void writeCumulativeBlock(StringBuilder sb, OverallStatSummaryResponse s) {
        blockHeader(sb, "누적 학습데이터");
        row(sb, "구분", "검수완료", "전체");
        row(sb, "이미지(장)", s.approvedImageCount(), s.cumulativeImageCount());
        row(sb, "영상(건)", s.approvedVideoCount(), s.cumulativeVideoCount());
    }

    /**
     * [처리현황] — 화면 SCR-STAT-002 와 동일한 <b>4구간 접기</b>.
     *
     * <p>처리중 = 진행중(ASSIGNED) + 검수대기(IN_REVIEW). 응답의 5값을 5행으로 그대로 펴면
     * 리포트가 화면과 다른 것을 말하게 된다.
     */
    private void writeProcessingBlock(StringBuilder sb, OverallStatSummaryResponse.Processing p) {
        OverallStatSummaryResponse.Processing safe =
                (p == null) ? OverallStatSummaryResponse.Processing.empty() : p;
        blockHeader(sb, "처리현황");
        row(sb, "구분", "건수");
        row(sb, "완료", safe.approved());
        row(sb, "처리중", safe.inProgress() + safe.reviewPending());
        row(sb, "대기", safe.pending());
        row(sb, "실패", safe.rejected());
    }

    /**
     * [일별 작업량] — 0-fill 된 전량을 그대로 담는다. 작업이 없던 날의 {@code 0} 행을 빼지 않는다:
     * 날짜 연속성이 이 블록의 의미다.
     */
    private void writeDailyBlock(StringBuilder sb, List<WorkerStatSummaryResponse.DailyCompletion> daily) {
        blockHeader(sb, "일별 작업량");
        row(sb, "일자", "검수완료건수");
        if (daily == null) {
            return;
        }
        for (WorkerStatSummaryResponse.DailyCompletion d : daily) {
            row(sb, d.date(), d.count());
        }
    }

    /**
     * [이벤트 유형 분포] — 검수완료·전체를 <b>{@code eventTypeCd} 로 짝지어</b> 한 행에 놓는다.
     * 두 목록의 인덱스 위치로 맞추면 한쪽 구성이 달라지는 순간 수치가 다른 유형에 붙는다.
     *
     * <p>{@code count=0} 유형도 그대로 남긴다. 전체 목록에 없는 검수완료 전용 유형이 섞여 와도
     * 버리지 않고 뒤에 잇는다(조용한 손실 방지).
     */
    private void writeEventDistributionBlock(StringBuilder sb,
                                             List<EventDistributionItem> total,
                                             List<EventDistributionItem> approved) {
        blockHeader(sb, "이벤트 유형 분포");
        row(sb, "유형", "검수완료", "전체");

        Map<String, EventDistributionItem> approvedByCode = new LinkedHashMap<>();
        if (approved != null) {
            for (EventDistributionItem a : approved) {
                if (a != null && a.eventTypeCd() != null) {
                    approvedByCode.put(a.eventTypeCd(), a);
                }
            }
        }

        if (total != null) {
            for (EventDistributionItem t : total) {
                if (t == null) {
                    continue;
                }
                EventDistributionItem matched =
                        (t.eventTypeCd() == null) ? null : approvedByCode.remove(t.eventTypeCd());
                long approvedCount = (matched == null) ? 0L : matched.count();
                row(sb, label(t), approvedCount, t.count());
            }
        }
        // 전체 목록에 대응이 없는 검수완료 항목 — 정상 형상에선 비지만 유실시키지 않는다.
        for (EventDistributionItem leftover : approvedByCode.values()) {
            row(sb, label(leftover), leftover.count(), 0L);
        }
    }

    /**
     * [작업자별 현황] — 화면 WorkerStatsTable 과 동일한 6컬럼·동일 순서.
     *
     * <p><b>비율은 이미 백분율(0~100)이다</b> — 다시 100 을 곱하지 않는다(작업자 통계
     * SCR-STAT-001 의 0~1 축과는 의도된 비대칭). 반려율은 화면과 같은 {@code 100 - 승인율} 파생.
     *
     * <p>{@code workers} 가 비어 있는 것은 자리표시가 아니라 <b>LABELER 배정 행이 0 건</b>이라는
     * 뜻이므로, 예외를 던지지 않고 헤더 + 안내 문구 한 줄로 마무리한다.
     */
    private void writeWorkerBlock(StringBuilder sb, List<OverallStatSummaryResponse.WorkerRow> workers) {
        blockHeader(sb, "작업자별 현황");
        row(sb, "작업자", "라벨", "진행", "검수", "오토라벨(%)", "반려율(%)");
        if (workers == null || workers.isEmpty()) {
            row(sb, NO_WORKER_NOTICE);
            return;
        }
        for (OverallStatSummaryResponse.WorkerRow w : workers) {
            row(sb, w.name(), w.labeled(), w.inProgress(), w.reviewed(),
                    w.autoLabelRate(), 100.0d - w.approvalRate());
        }
    }

    private static String label(EventDistributionItem item) {
        return (item.label() == null || item.label().isBlank()) ? item.eventTypeCd() : item.label();
    }

    /** 블록 구분 — 빈 줄 1개 + 대괄호 제목 행. */
    private static void blockHeader(StringBuilder sb, String title) {
        sb.append(LINE);
        row(sb, "[" + title + "]");
    }

    /**
     * <b>모든 셀이 반드시 거치는 단일 조립 지점.</b> 타입으로 포맷과 무해화 여부를 가른다 —
     * 문자열 연결로 행을 직접 만들면 이스케이프가 빠진 셀이 생긴다.
     *
     * <ul>
     *   <li>{@code String} — 수식 인젝션 무해화 + RFC 4180 이스케이프</li>
     *   <li>{@code Long/Integer} — 정수. <b>로케일 천 단위 구분자를 넣지 않는다</b>(Excel 이
     *       텍스트로 읽는다)</li>
     *   <li>{@code Double} — 비율, 소수 1자리 ({@link Locale#ROOT} 고정 — 로케일에 따라 소수점이
     *       쉼표가 되면 컬럼이 밀린다)</li>
     * </ul>
     */
    private static void row(StringBuilder sb, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(format(cells[i]));
        }
        sb.append(LINE);
    }

    private static String format(Object cell) {
        if (cell instanceof String s) {
            return text(s);
        }
        if (cell instanceof Long || cell instanceof Integer) {
            return cell.toString();
        }
        if (cell instanceof Double d) {
            return String.format(Locale.ROOT, "%.1f", d);
        }
        // 지원하지 않는 타입을 조용히 toString 하면 이스케이프 규약이 뚫린다.
        throw new IllegalArgumentException("지원하지 않는 CSV 셀 타입: "
                + (cell == null ? "null" : cell.getClass().getName()));
    }

    /** 문자열 셀 — 수식 인젝션 무해화(CWE-1236) 후 RFC 4180 이스케이프. */
    private static String text(String raw) {
        String v = (raw == null) ? "" : raw;
        if (!v.isEmpty() && FORMULA_LEADING_CHARS.indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
            v = '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }
}
