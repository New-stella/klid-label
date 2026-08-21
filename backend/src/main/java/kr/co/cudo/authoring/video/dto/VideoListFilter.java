package kr.co.cudo.authoring.video.dto;

import java.time.LocalDate;

/**
 * 영상 처리 현황 목록({@code GET /v1/videos}) 검색·필터 조건 (요청 원본값).
 *
 * <p>모든 필드는 <b>선택</b>이며 전부 null 이면 변경 전과 동일한 목록(원본 영상 전체, 기본 정렬)이
 * 반환된다 — 하위호환 계약이다(프로젝트 CLAUDE.md "목록 화면 정렬·필터 정책").
 *
 * <p>정규화(공백 제거·길이 상한·LIKE 이스케이프·카테고리 키 → EV-코드 변환·날짜 → 시각 경계 확장)는
 * {@code VideoQueryService} 가 수행한다. 컨트롤러는 요청 원본값을 그대로 담아 넘기기만 한다.
 *
 * @param dataSttsCd      배치 단계 상태({@code LS_DATA_RAW.DATA_STTS_CD})
 * @param reviewStatusCd  검수 워크플로 상태({@code LS_RAW_DATA_STATUS.DATA_STTS_CD})
 * @param cctvNameKeyword 검색어 — CCTV 명 부분일치 <b>또는</b> 영상 ID(숫자 입력 시) 일치
 * @param eventTypeCd     이벤트 <b>카테고리 키</b>({@code EVNT_CLS_CD + EVNT_CTGRY_CD}, 예 {@code 010001}).
 *                        영상이 보유한 EV-코드와 축이 달라 서비스가 관제 마스터로 변환해 비교한다.
 * @param from            촬영일({@code SHT_DT}) 시작 — 해당일 00:00:00 부터 포함
 * @param to              촬영일({@code SHT_DT}) 종료 — 해당일 23:59:59.999999999 까지 포함
 * @param skippedStage    <b>지금</b> 건너뛴 상태인 작업 묶음({@code VLM}/{@code AUTOLABEL}). 건너뛰기가
 *                        해제된 영상은 남지 않는다. 벤더 연동이 끝난 뒤 건너뛴 영상을 모아 되살리는
 *                        자리에서 쓴다 — 일괄 요청에 건수 상한이 있어 목록에서 대상을 골라내지 못하면
 *                        회수가 성립하지 않는다. 지원하지 않는 값은 400 이다. [@design API-042]
 * @param failedStage     <b>지금</b> 그 작업 묶음이 실패한 상태인 영상만({@code VLM}/{@code AUTOLABEL}).
 *                        시계열 위탁은 논블로킹이라 실패해도 배치 상태가 완료로 남고 단계 실패 표시도
 *                        서지 않아, 이 필터가 없으면 <b>벤더 장애로 실패한 영상을 목록에서 모을 수
 *                        없다</b>(일괄 건너뛰기가 쓰이는 바로 그 자리다). 지원하지 않는 값은 400 이며
 *                        {@code skippedStage} 와 <b>함께</b> 지정할 수 있다(축이 다르다 — 한쪽은 「사람이
 *                        건너뛴 상태」, 다른 쪽은 「실패한 상태」). [@design API-042] [@design ADR-050]
 */
public record VideoListFilter(
        String dataSttsCd,
        String reviewStatusCd,
        String cctvNameKeyword,
        String eventTypeCd,
        LocalDate from,
        LocalDate to,
        String skippedStage,
        String failedStage
) {

    /** 상태 2종만 지정하는 축약 생성 — 기존 호출(검색어·이벤트·기간 미사용)과 동일한 조건. */
    public static VideoListFilter ofStatus(String dataSttsCd, String reviewStatusCd) {
        return new VideoListFilter(dataSttsCd, reviewStatusCd, null, null, null, null, null, null);
    }
}
