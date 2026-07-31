package kr.co.cudo.authoring.augment.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 증강/파생 결과 항목 1건 — FE {@code AugmentResult} 계약 정합.
 *
 * <p><b>항목 = 증강 행 1건({@code DATA_AUG_SN})</b> 이다. 같은 (영상 × 종류) 재요청이 허용된
 * 2026-07-31 이후로는 같은 {@code type} 항목이 여러 건 나올 수 있다 — 항목을 구분하는 것은
 * {@code type} 이 아니라 {@code id} 와 {@link #prompt} 다.
 *
 * <p>수록 범위: <b>외부 위탁 증강(WINTER/NIGHT/RAIN)</b> + <b>해상도 파생(RESL_*)</b>. 외부 위탁 항목의
 * {@code framePairs} 는 아직 비어 있다(프레임별 비교 결과는 외부 SFR-07 연동 이후) — 그래도 항목 자체는
 * 내려야 REVIEWER 가 <b>채택/반려를 결정하기 전에</b> 그 결과물이 어떤 조건으로 생성됐는지 확인할 수 있다
 * (R9 역추적). 구 구현은 외부 위탁 항목을 아예 제외해, 조건을 볼 수 있는 유일한 경로가 accept/reject
 * <b>응답</b>뿐이었다(= 결정한 뒤에야, 그것도 1회만 볼 수 있었다).
 *
 * @param id              결과 항목 ID = {@code LS_DATA_AUG.DATA_AUG_SN}
 * @param videoId         그룹 키 = <b>원본(부모) RAW_SN</b>. 해상도 3종이 한 영상 섹션의 탭으로 묶인다.
 * @param cctvName        원본 영상 CCTV 명 (미상 시 폴백)
 * @param type            증강/파생 유형 코드 (예: {@code RESL_720P})
 * @param framePairs      프레임 쌍(페이징된 슬라이스)
 * @param decision        활용 결정 상태. 해상도 파생은 검수 대상이 아니므로 항상 {@code ACCEPTED}(생성 완료)
 * @param decidedAt       생성 확정 일시(해상도 파생은 증강행 등록 일시)
 * @param rejectReason    반려 사유 (해상도 파생은 항상 null)
 * @param derivativeRawSn 파생 영상 RAW_SN — FE 가 파생본 라벨링/검수로 이동할 때 사용
 * @param totalFramePairs 페이징 전 전체 프레임 쌍 후보 수(파생 프레임 총 개수)
 * @param reviewable      accept/reject 가능 여부. 해상도 파생은 항상 false (내부 생성물)
 * @param prompt          이 결과물을 만들 때 외부로 전송한 생성 조건 원문({@code LS_DATA_AUG.PROMPT_CN}).
 *                        <b>보낸 그대로의 JSON 문자열</b>이며 서버가 재가공하지 않는다 — 역추적의 정본은
 *                        "실제로 나간 값" 이므로 파싱·재직렬화로 모양이 달라지면 근거가 약해진다(FE 가
 *                        {@code JSON.parse} 로 5필드를 읽는다). 해상도 파생(내부 생성물)과 V147 이전
 *                        요청은 {@code null}.
 */
public record AugmentResultItemResponse(
        Long id,
        Long videoId,
        String cctvName,
        String type,
        List<AugmentFramePairResponse> framePairs,
        String decision,
        LocalDateTime decidedAt,
        String rejectReason,
        Long derivativeRawSn,
        long totalFramePairs,
        boolean reviewable,
        String prompt
) {
}
