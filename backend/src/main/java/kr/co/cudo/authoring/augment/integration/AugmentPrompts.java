package kr.co.cudo.authoring.augment.integration;

import kr.co.cudo.authoring.augment.entity.LsDataAug;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 증강 유형별 {@code prompt} 조립 — 「생성형 AI API 연동명세서 v1.1」 §4.1 {@code prompt}(자유 구조 dict).
 *
 * <p>프롬프트 문자열을 서비스/클라이언트 곳곳에 흩뿌리지 않고 <b>이 한 곳</b>에서만 관리한다.
 * 증강 유형(WINTER/NIGHT/RAIN)은 SFR-07 외부 증강 3종이며, 그 외 코드(해상도 파생 RESL_* 등)는
 * 외부 위탁 대상이 아니므로 여기 오지 않는다(호출부가 차단).
 */
public final class AugmentPrompts {

    /** prompt 키 — 증강 스타일 식별자. */
    static final String KEY_STYLE = "style";
    /** prompt 키 — 자연어 변환 지시문. */
    static final String KEY_INSTRUCTION = "instruction";
    /** prompt 키 — 변환 대상(원본 구도/객체 보존 요구). */
    static final String KEY_PRESERVE = "preserve";

    /** 라벨 좌표를 그대로 복사해 쓰므로 구도·객체 위치 보존이 계약상 전제다. */
    private static final String PRESERVE_GEOMETRY = "geometry";

    private AugmentPrompts() {
    }

    /**
     * 증강 유형에 대응하는 prompt dict 를 만든다.
     *
     * @param augType WINTER/NIGHT/RAIN
     * @throws IllegalArgumentException 외부 증강 3종이 아닌 코드
     */
    public static Map<String, Object> of(String augType) {
        String instruction = switch (augType == null ? "" : augType) {
            case LsDataAug.AUG_WINTER ->
                    "Convert the scene to a winter environment with snow cover and cold lighting.";
            case LsDataAug.AUG_NIGHT ->
                    "Convert the scene to night time with low ambient light and artificial illumination.";
            case LsDataAug.AUG_RAIN ->
                    "Convert the scene to rainy weather with wet surfaces and rain streaks.";
            default -> throw new IllegalArgumentException(
                    "외부 증강 대상이 아닌 증강 유형입니다: " + augType);
        };
        Map<String, Object> prompt = new LinkedHashMap<>();
        prompt.put(KEY_STYLE, augType);
        prompt.put(KEY_INSTRUCTION, instruction);
        prompt.put(KEY_PRESERVE, PRESERVE_GEOMETRY);
        return prompt;
    }

    /** 외부 위탁 대상(SFR-07 3종)인지 판별한다. */
    public static boolean isExternalAugType(String augType) {
        return LsDataAug.AUG_WINTER.equals(augType)
                || LsDataAug.AUG_NIGHT.equals(augType)
                || LsDataAug.AUG_RAIN.equals(augType);
    }
}
