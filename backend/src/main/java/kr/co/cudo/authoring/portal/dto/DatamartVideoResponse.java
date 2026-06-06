package kr.co.cudo.authoring.portal.dto;

import java.time.LocalDateTime;

/**
 * Phase B — 포털 홈 데이터마트 영상 목록 응답 1행.
 *
 * <p>데이터마트 노출(검수 완료 = LS_RAW_DATA_STATUS.DATA_STTS_CD 'APPROVED') 영상만 포함한다.
 * Entity(LsDataRaw) 직접 반환 금지 정책 준수 — 노출 필드만 명시적으로 매핑.
 *
 * <p>{@code firstSrcSn} 은 라벨링 진입(/portal/label/{firstSrcSn}) 용 첫 프레임 SRC_SN.
 * 프레임이 0건인 영상은 진입 불가하므로 서비스 단에서 목록에서 제외한다(firstSrcSn 항상 non-null).
 *
 * <p>MED-3: {@code lastUpdatedAt} 은 {@code LS_RAW_DATA_STATUS.UPD_DT}(마지막 상태 변경 일시)이다.
 * 정확한 검수 승인 시각을 보존하는 별도 컬럼이 없어 '승인 시각'이 아닌 '최종 갱신 일시'를 노출한다.
 * (재승인 전 다른 상태 전이가 끼면 approvedAt 으로 해석될 때 오해를 일으키므로 의미에 맞게 명명)
 */
public record DatamartVideoResponse(
        Long rawSn,
        String title,
        String eventName,
        long frameCount,
        Long firstSrcSn,
        LocalDateTime lastUpdatedAt
) {
}
