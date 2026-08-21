package kr.co.cudo.authoring.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.co.cudo.authoring.transfer.entity.LsOtsdCtgryMpng;

/**
 * 분류 대응 1건.
 *
 * <h3>연결이 비어 있어도 감추지 않는다</h3>
 * <p>{@link #labelId} 나 {@link #evntTypeCd} 가 비었거나 라벨 마스터에서 이름을 찾지 못한 행도 그대로
 * 돌려준다. 감추면 화면에서 사라진 채 표에 남아, 왜 그 분류가 계속 처음 보는 분류로 나오는지 알 수
 * 없게 된다. (라벨 프리셋이 마스터에 없는 코드를 오류 없이 '미연결'로 보여 주는 것과 같은 취지.)
 *
 * @param labelName 연결된 라벨의 이름. 라벨이 없거나 비활성이면 {@code null}
 * @design API-209
 */
@Schema(description = "분류 대응 1건")
public record ImportMappingResponse(
        long mpngSn,
        String kind,
        String externalCode,
        String externalName,
        Long labelId,
        String labelName,
        String evntTypeCd,
        String useYn) {

    /**
     * 엔티티 → 응답 변환.
     *
     * @param labelName 호출부가 <b>한 번에 모아 조회한</b> 라벨 이름(없으면 {@code null}) — 항목마다
     *                  마스터를 다시 읽으면 목록 크기만큼 조회가 늘어난다(N+1)
     */
    public static ImportMappingResponse from(LsOtsdCtgryMpng mapping, String labelName) {
        return new ImportMappingResponse(
                mapping.getMpngSn(),
                mapping.getMpngKndCd(),
                mapping.getOtsdCtgryCd(),
                mapping.getOtsdCtgryNm(),
                mapping.getLblId(),
                labelName,
                mapping.getEvntTypeCd(),
                mapping.getUseYn());
    }
}
