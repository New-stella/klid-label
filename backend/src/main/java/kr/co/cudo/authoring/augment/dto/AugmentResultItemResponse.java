package kr.co.cudo.authoring.augment.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 증강/파생 결과 항목 1건(= 유형 탭 1개) — FE {@code AugmentResult} 계약 정합.
 *
 * <p>현재는 <b>해상도 파생(RESL_*)</b> 만 채워진다. 외부 위탁 증강(WINTER/NIGHT/RAIN)의 프레임 쌍은
 * 외부 SFR-07 연동 이후 별도 경로에서 채워지므로 이 목록에 등장하지 않는다(회귀 0).
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
        boolean reviewable
) {
}
