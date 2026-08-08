package kr.co.cudo.authoring.augment.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 증강 작업 결과 조회 응답 — {@code GET /v1/augments/{jobId}/result}.
 *
 * <p><b>기존 응답 키({@code jobId}/{@code status}/{@code results}/{@code message}/{@code page}/{@code size})를
 * 그대로 유지</b>한다 (FE {@code AugmentResultPage} 계약). 항목 축 4필드는 additive 라 구 FE 는 무시해도
 * 동작한다.
 *
 * <h3>페이징 축이 둘이다 (DEV_FIX HIGH-1)</h3>
 * <p>1차 수정은 {@code page}/{@code size} <b>한 창</b>을 프레임 쌍과 결과 항목에 동시에 적용했다. 중복은
 * 사라졌지만 <b>도달 불가</b>가 생겼다 — 프레임 쌍이 0건인 영상(순수 외부 위탁)에서는 FE 페이저가
 * 렌더되지 않아 항목 2페이지로 갈 UI 자체가 없었고, 응답에 항목 축 총량도 없어 2페이지의 존재조차
 * 알 수 없었다. 한 창을 공유하는 한 "한쪽 축 총량이 0이면 다른 축이 갇힌다" 가 구조적으로 남으므로
 * 축을 분리한다.
 *
 * @param jobId         증강 jobId (= 원본 RAW_SN)
 * @param status        집계 상태 {@code COMPLETED|FAILED|PROCESSING}
 * @param results       결과 항목 — <b>외부 위탁 증강(항목 축으로 페이징된 슬라이스)</b> + <b>해상도 파생</b>
 * @param message       안내 문구
 * @param page          <b>프레임 쌍 축</b> 페이지 번호 (0-based). 기존 키·기본값(0) 불변.
 * @param size          <b>프레임 쌍 축</b> 페이지 크기. 기존 키·기본값(12) 불변.
 * @param itemPage      <b>결과 항목 축</b> 페이지 번호 (0-based)
 * @param itemSize      <b>결과 항목 축</b> 페이지 크기
 * @param totalElements 결과 항목 축 총량 — <b>외부 위탁 증강 + 해상도 파생 항목 전체</b> 수
 *                      (DEV_FIX MED-5). 즉 {@code results.length} 는 언제나 이 총량을
 *                      {@code itemSize} 로 자른 한 페이지 분량이며, <b>페이지를 이어붙이면 전체 항목이
 *                      정확히 한 번씩</b> 모인다(표준 페이징 규약, {@code rules/api-design.md}).
 *                      <p>구 구현은 해상도 파생을 이 총량에서 제외하고 {@code itemPage==0} 에만 실었다.
 *                      그 결과 <b>항목 페이저를 넘긴 사용자는 프레임 쌍(비교 이미지)에 도달할 수
 *                      없었다</b>. 반대로 모든 항목 페이지에 실으면 이어붙이는 클라이언트가 해상도
 *                      항목을 페이지 수만큼 중복 수집한다. 항목 축의 정식 원소로 세면 둘 다 사라진다.
 *                      <p>두 종류는 {@code derivativeRawSn} 의 null 여부로 구분된다(해상도 파생만 non-null).
 * @param totalPages    결과 항목 축 총 페이지 수 (총량 0 이면 0)
 * @param requestedAt   <b>요청일시</b> — 이 영상의 증강 행 {@code MIN(REG_DT)}. 증강 행이 0건이면
 *                      {@code null}(지어내지 않는다). 목록 API({@code GET /v1/augments} 의
 *                      {@code requestedAt})와 <b>같은 축·같은 산출식</b>이며 판정 단일 원천은
 *                      {@code AugmentReviewService.resolveRequestedAt} 이다.
 *                      <p><b>항목별 {@code decidedAt}(채택·반려 결정 시각)과 축이 다르다</b> — 요청일시는
 *                      "언제 만들어 달라고 했는가", 결정 시각은 "REVIEWER 가 언제 결정했는가" 라
 *                      서로 대체할 수 없다.
 *                      <p>페이징과 무관한 <b>잡 단위</b> 값이라 항목이 0건인 페이지·범위 밖 페이지
 *                      응답에도 동일하게 실린다.
 */
public record AugmentResultResponse(
        Long jobId,
        String status,
        List<AugmentResultItemResponse> results,
        String message,
        int page,
        int size,
        int itemPage,
        int itemSize,
        long totalElements,
        int totalPages,
        LocalDateTime requestedAt
) {
}
