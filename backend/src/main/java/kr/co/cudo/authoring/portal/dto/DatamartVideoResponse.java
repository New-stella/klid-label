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
 *
 * <p>{@code myLabelExpiresAt} 은 본인 저장 라벨의 보존기간 만료 예정 시각이다. @design AC-1068, DFEAT-055
 * <b>저장되지 않는 파생값</b>으로, 조회 시점의 {@code portal.datamart.retention-days} 설정값으로 매번
 * 재계산된다 — 설정이 바뀌면 다음 조회부터 값이 달라지므로 <b>클라이언트는 캐시하지 말 것</b>.
 * 본인 저장 라벨이 없거나 보존기간 설정이 없으면 {@code null}. 판정은
 * {@code PortalRetentionPolicy} 한 곳에서만 한다(재유도 금지).
 *
 * <p>기산점은 그 사용자의 저작 <b>최초</b> 저장 시각이다(DFEAT-055) — 마지막 저장이 아니므로 저장을
 * 반복해도 이 값이 뒤로 밀리지 않는다. 필드명·타입은 무변경(계약면 그대로).
 */
public record DatamartVideoResponse(
        Long rawSn,
        String title,
        String eventName,
        long frameCount,
        Long firstSrcSn,
        LocalDateTime lastUpdatedAt,
        LocalDateTime myLabelExpiresAt
) {
}
