package kr.co.cudo.authoring.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 산출물 폴더 검사 결과 — <b>미리보기</b>다. 이 응답을 만들면서 저장하는 것은 하나도 없다(AC-041).
 *
 * <h3>알림은 한 목록이고 판정 지점은 하나다</h3>
 * <p>{@link #warnings} 에는 <b>적재를 막는 사항과 막지 않는 사항이 함께</b> 담기고, 지금 상태로
 * 적재할 수 있는지의 판정은 {@link #importable} <b>하나</b>가 한다.
 *
 * <p><b>왜 자리를 나누지 않는가</b> — 나누면 판정 지점이 둘이 되고, 두 자리가 어긋났을 때
 * <b>어느 쪽이 진실인지 알 수 없다</b>. 부르는 쪽이 판단하지 못하게 되는 것은 섞여서가 아니라
 * 판정 지점이 둘이어서다. 그래서 담는 자리는 하나로 두고 판정만 단일 필드로 분리한다.
 *
 * <p>⚠ <b>알림 개수를 세어 판정하지 말 것</b> — 막지 않는 알림만 있어도 개수는 0 이 아니다.
 *
 * <p>⚠ <b>구 서술 폐기(2026-08-20)</b> — 이 자리에 <i>"경고에는 적재를 막지 않는 사항만 담는다.
 * 적재를 막는 사유는 따로 돌려준다"</i> 라고 적혀 있었으나 <b>사실과 다르다</b>. 실제로는 훑기 상한
 * 초과·식별자 생성 불가·프레임 0건이 이 목록에 담기면서 적재를 막는다(예시이며 전수 목록이 아니다).
 * 그 서술대로 읽으면 화면이 <b>막힌 산출물을 진행 가능으로 표시</b>한다.
 *
 * @param frameCount         <b>실제로 발견된</b> 프레임 수. 문서가 선언한 수가 아니다
 * @param declaredFrameCount 산출물 문서가 선언한 프레임 수. 문서에 선언이 없으면 0
 * @param labelCount         적재될 도형 라벨 수. <b>발견된 도형 수가 아니다</b> — 경계상자·키포인트만
 *                           있는 도형은 해석 규칙이 확정되지 않아 라벨이 되지 않으므로 세지 않는다
 *                           (그 사실은 경고로 알린다). 이 수는 적재 결과의 라벨 수와 같아야 한다
 * @param videoFileName      산출물 문서가 알려주는 영상 파일 이름(정제됨). 없으면 {@code null}
 * @param duplicate          이미 가져온 산출물이면 그 영상. 아니면 {@code null}
 * @param unmappedCategories 아직 대응이 정해지지 않은 분류와 추천 후보. 하나라도 남으면 적재할 수 없다
 * @param warnings           알려야 하는 사항. <b>일부는 적재를 막는다</b> — 판정은 {@code importable} 이 한다
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
