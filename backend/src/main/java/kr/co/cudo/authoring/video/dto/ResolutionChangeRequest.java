package kr.co.cudo.authoring.video.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 해상도 변경 요청 — Phase 3 (RQ-SFR-06-03 파생영상 전환).
 *
 * <p><b>계약</b>: 검수 완료(APPROVED) 원본 1건에서 표준 하위 해상도 프리셋마다 파생영상(새 RAW_SN)을
 * 생성한다. 요구는 "1080/720/480 세 종 고정 생성"이므로 {@code presets} 는 <b>선택(optional)</b>이다.
 * <ul>
 *   <li>미지정(null 또는 빈 목록·바디 생략) → 표준 3종(RES_1080P/RES_720P/RES_480P) 전체 생성(기본).</li>
 *   <li>지정 → 지정한 프리셋만 생성(향후 부분 선택 확장용).</li>
 * </ul>
 * 목록 원소는 enum 바인딩으로 화이트리스트(RES_1080P/RES_720P/RES_480P)를 강제한다. 그 외 값/형식은
 * Jackson 역직렬화 단계에서 400(INVALID_INPUT) 으로 거부된다(CWE-20 입력 검증 — 자유 입력 해상도 차단).
 * 원본과 동일한 해상도 프리셋은 실행 시점에 스킵된다.
 */
public record ResolutionChangeRequest(
        @Schema(description = "생성할 표준 하위 해상도 프리셋 목록(선택). 미지정 시 표준 3종 전체 생성",
                example = "[\"RES_720P\", \"RES_480P\"]",
                allowableValues = {"RES_1080P", "RES_720P", "RES_480P"})
        List<ResolutionPreset> presets
) {

    /**
     * 미지정(null/빈 목록)이면 기본 프리셋 전체, 지정되면 순서·중복 제거한 지정 목록을 반환한다.
     *
     * @param defaults 미지정 시 사용할 표준 프리셋 전체
     */
    public List<ResolutionPreset> resolvePresets(List<ResolutionPreset> defaults) {
        if (presets == null || presets.isEmpty()) {
            return defaults;
        }
        return presets.stream().distinct().toList();
    }
}
