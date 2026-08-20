package kr.co.cudo.authoring.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 산출물 폴더 검사 결과 — <b>미리보기</b>다. 이 응답을 만들면서 저장하는 것은 하나도 없다(AC-041).
 *
 * <h3>경고와 적재 차단 사유는 자리가 다르다</h3>
 * <p>{@link #warnings} 에는 <b>적재를 막지 않는</b> 사항만 담는다(AC-047). 적재를 막는 사유는
 * {@link #unmappedCategories}(대응이 정해지지 않은 분류)와 {@link #duplicate}(이미 가져온 산출물)로
 * 따로 돌려주고, 지금 상태로 적재할 수 있는지는 {@link #importable} 하나로 알린다. 섞어 두면
 * 화면이 "경고가 있으니 못 가져온다"로 잘못 읽는다.
 *
 * @param frameCount         <b>실제로 발견된</b> 프레임 수. 문서가 선언한 수가 아니다
 * @param declaredFrameCount 산출물 문서가 선언한 프레임 수. 문서에 선언이 없으면 0
 * @param labelCount         적재될 도형 라벨 수
 * @param videoFileName      산출물 문서가 알려주는 영상 파일 이름(정제됨). 없으면 {@code null}
 * @param duplicate          이미 가져온 산출물이면 그 영상. 아니면 {@code null}
 * @param unmappedCategories 아직 대응이 정해지지 않은 분류와 추천 후보. 하나라도 남으면 적재할 수 없다
 * @param warnings           적재를 막지 않는 경고
 * @param importable         지금 상태로 적재할 수 있는지 여부
 * @design API-205
 * @design AC-041
 * @design AC-047
 */
@Schema(description = "외부 산출물 폴더 검사 결과(미리보기)")
public record ImportScanResponse(
        int frameCount,
        int declaredFrameCount,
        long labelCount,
        String videoFileName,
        Duplicate duplicate,
        List<UnmappedCategory> unmappedCategories,
        List<Warning> warnings,
        boolean importable) {

    /** 이미 가져온 산출물이 가리키는 영상. */
    @Schema(description = "이미 가져온 산출물이면 그 영상")
    public record Duplicate(long rawSn) {
    }

    /** 적재를 막지 않는 경고 1건. */
    @Schema(description = "적재를 막지 않는 경고")
    public record Warning(String code, String message) {
    }

    /**
     * 대응이 정해지지 않은 분류 1건.
     *
     * @param kind        {@code LABEL}(도형 라벨) 또는 {@code EVNT_TYPE}(이벤트 유형)
     * @param suggestions 이름이 비슷해 추천하는 대상. <b>자동으로 확정하지 않고 사람이 고른다</b>.
     *                    후보가 유일하지 않으면 비어 있다
     */
    @Schema(description = "대응이 정해지지 않은 분류와 추천 후보")
    public record UnmappedCategory(
            String kind,
            String externalCode,
            String externalName,
            List<Suggestion> suggestions) {
    }

    /** 추천 후보 1건 — {@code targetId} 는 라벨이면 라벨 아이디, 이벤트 유형이면 유형 코드다. */
    @Schema(description = "추천 후보")
    public record Suggestion(String targetId, String targetName) {
    }
}
