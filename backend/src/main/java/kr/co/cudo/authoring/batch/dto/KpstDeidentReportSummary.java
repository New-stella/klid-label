package kr.co.cudo.authoring.batch.dto;

import kr.co.cudo.authoring.common.client.dto.KpstReportResponse;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 비식별 처리 결과 리포트 요약 — 벤더 응답 1행({@code dsStatus[]})을 적재 형태로 옮긴 값. [req: R14]
 *
 * <p>{@code LS_DEIDENT_PROC_LOG} 의 리포트 컬럼 6종에 그대로 대응한다.
 *
 * <h3>이 변환은 절대 예외를 던지지 않는다 (Critical)</h3>
 * <p>리포트는 <b>부가 정보</b>다. 여기서 예외가 새면 폴링 완료 경로가 터져 비식별 완료 전이
 * ({@code DE_IDNTF_YN='Y'} → {@code MARKING_READY})가 막히고, 그 영상은 외부 부가 API 하나 때문에
 * 파이프라인에 고착된다. 그래서 해석 실패는 <b>해당 필드만 null</b> 로 두고 나머지는 살린다
 * ({@code KpstDeidentService.resolveMaskingOptions} 의 fail-safe 와 같은 취지).
 */
public record KpstDeidentReportSummary(
        Long faceCount,
        Long lpCount,
        Long totalFrame,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        String reportFilePath
) {

    /**
     * {@code RPT_FILE_PATH_NM} 컬럼 폭(V184). 외부값이라 길이를 신뢰할 수 없어 적재 전 절단한다.
     *
     * <p>절단은 표시용 부가 정보의 손실이지만, 절단하지 않으면 컬럼 폭 초과가 <b>INSERT 시점 DB
     * 오류(500)</b> 로 새어 완료 트랜잭션 전체를 롤백시킨다 — 이 저장소가 이미 겪은 실패 모드다.
     */
    public static final int MAX_REPORT_FILE_PATH_LEN = 1000;

    /**
     * 허용 시각 포맷 — 벤더 실측 포맷({@code 'yyyy-MM-dd HH:mm:ss'})과 ISO 변형을 모두 받는다.
     * 어느 것에도 맞지 않으면 {@code null} 이다(예: 실서버가 미시작 구간에 주는 문자열 {@code "None"}).
     */
    private static final List<DateTimeFormatter> TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME);

    /**
     * 벤더 응답 1행 → 요약. {@code ds} 가 {@code null} 이면 {@code null} 을 돌려준다
     * (= "리포트 없음" 을 그대로 표현. 빈 요약을 만들어 0 으로 채우면 <b>0건 검출</b>과 구분되지 않는다).
     */
    public static KpstDeidentReportSummary from(KpstReportResponse.DsStatus ds) {
        if (ds == null) {
            return null;
        }
        return new KpstDeidentReportSummary(
                ds.faceCount(),
                ds.lpCount(),
                ds.totalFrame(),
                parseTime(ds.startTime()),
                parseTime(ds.endTime()),
                clampPath(ds.fileName()));
    }

    /** 어떤 입력에도 예외를 던지지 않는 시각 해석 — 해석 불가면 {@code null}. */
    private static LocalDateTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        for (DateTimeFormatter format : TIME_FORMATS) {
            try {
                return LocalDateTime.parse(value, format);
            } catch (DateTimeParseException ignored) {
                // 다음 포맷으로 시도 — 전부 실패하면 null(부분 성공 허용).
            }
        }
        return null;
    }

    /** 컬럼 폭 초과 절단(2중 방어의 앱 측). blank 는 값 없음과 같으므로 {@code null} 로 정규화한다. */
    private static String clampPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.length() <= MAX_REPORT_FILE_PATH_LEN
                ? raw
                : raw.substring(0, MAX_REPORT_FILE_PATH_LEN);
    }
}
