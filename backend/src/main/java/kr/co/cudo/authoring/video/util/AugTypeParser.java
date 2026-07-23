package kr.co.cudo.authoring.video.util;

import kr.co.cudo.authoring.augment.entity.LsDataAug;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 파생 영상(증강/해상도)의 {@code VMS_CLIP_ID} 문자열에서 증강 종류를 정규화 파싱한다.
 *
 * <p>파생 영상의 {@code VMS_CLIP_ID} 는 {@code LsDataRaw.createAugmented}/{@code createResolutionDerivative}
 * 에서 {@code {원본clip}_AUG_{TYPE}_{ts}} / {@code {원본clip}_RESL_{code}_{ts}} 형태로 생성된다.
 * 다만 실데이터(cudo_246)에는 포맷 드리프트가 존재한다 —
 * 이중 접두(_RESL_RESL_480P_), 구형 RES 접두(_RES_RES_480P_) 등. 이 파서는 그 변형을 모두 흡수하여
 * FE 계약값({@code WINTER|NIGHT|RAIN|RESL_1080P|RESL_720P|RESL_480P})으로 정규화한다.
 *
 * <p>정규화 규칙 — <b>마커 위치 기반</b>(원본명 오탐 방지):
 * <ol>
 *   <li>파생 종류는 항상 마커 뒤에 온다 — {@code _AUG_}(증강) / {@code _RESL_}·{@code _RES_}(해상도).
 *       마커 <b>뒤</b> 세그먼트에서만 종류 토큰을 판별하므로 원본 clip 이름(마커 앞)에 우연히 섞인
 *       경쟁 토큰(예: {@code winter-park...}, {@code road480p...})으로 오탐하지 않는다.</li>
 *   <li>마커가 여러 번이면 <b>가장 오른쪽</b> 마커(접미 timestamp 직전 종류)를 채택한다.
 *       이중 접두 {@code _RESL_RESL_480P_}·구형 {@code _RES_RES_480P_} 는 마커 뒤에서 코드 토큰만
 *       뽑으므로 모두 {@code RESL_480P} 로 정규화된다.</li>
 *   <li>{@code _AUG_} 뒤에서 {@code WINTER}/{@code NIGHT}/{@code RAIN} 를 판별.</li>
 *   <li>{@code _RESL_}/{@code _RES_} 뒤에서 해상도 코드({@code 1080P}/{@code 720P}/{@code 480P})를
 *       판별해 {@link LsDataAug#RESL_PREFIX} 접두로 정규화.</li>
 *   <li>마커가 없거나(순수 원본명·비정형) 마커 뒤 토큰 미매칭이면 {@code null} 반환 —
 *       전체 문자열 스캔 폴백은 오탐 위험이 있어 사용하지 않고 {@code null}(안전) 을 택한다.</li>
 *   <li>{@code null}/blank 입력은 {@code null} 반환.</li>
 * </ol>
 *
 * <p>SQL/로그 인젝션 표면 아님 — 순수 문자열 파싱, 외부 쿼리·명령에 사용하지 않는다.
 */
public final class AugTypeParser {

    /** 증강 파생 마커 — {@code LsDataRaw.createFromAugment} 가 {@code {원본}_AUG_{TYPE}_{ts}} 로 생성. */
    private static final String AUG_MARKER = "_AUG_";

    /** 해상도 파생 마커 — {@code LsDataRaw.createFromResolution} 가 {@code {원본}_RESL_{code}_{ts}} 로 생성. */
    private static final String RESL_MARKER = "_RESL_";

    /** 구형 해상도 마커(실데이터 드리프트) — {@code _RES_RES_480P_} 등. */
    private static final String LEGACY_RES_MARKER = "_RES_";

    /** 표준 해상도 코드 토큰 — 정규화 시 {@link LsDataAug#RESL_PREFIX} 를 접두로 붙인다. */
    private static final Pattern RESL_CODE_PATTERN = Pattern.compile("(1080P|720P|480P)");

    private AugTypeParser() {
    }

    /**
     * {@code VMS_CLIP_ID} 에서 증강 종류를 정규화 파싱한다.
     *
     * @param vmsClipId 파생 영상의 VMS_CLIP_ID (nullable)
     * @return {@code WINTER|NIGHT|RAIN|RESL_1080P|RESL_720P|RESL_480P} 중 하나, 매칭 없으면 {@code null}
     */
    public static String parse(String vmsClipId) {
        if (vmsClipId == null || vmsClipId.isBlank()) {
            return null;
        }
        String upper = vmsClipId.toUpperCase(Locale.ROOT);

        int augIdx = upper.lastIndexOf(AUG_MARKER);
        int reslIdx = upper.lastIndexOf(RESL_MARKER);
        int legacyResIdx = upper.lastIndexOf(LEGACY_RES_MARKER);
        int resMarkerIdx = Math.max(reslIdx, legacyResIdx);

        // 가장 오른쪽 마커(접미 timestamp 직전 종류)가 파생 종류를 결정한다.
        if (augIdx >= 0 && augIdx > resMarkerIdx) {
            return matchAugType(upper.substring(augIdx + AUG_MARKER.length()));
        }
        if (resMarkerIdx >= 0) {
            int markerLen = (reslIdx >= legacyResIdx) ? RESL_MARKER.length() : LEGACY_RES_MARKER.length();
            return matchResolution(upper.substring(resMarkerIdx + markerLen));
        }
        return null;
    }

    /** {@code _AUG_} 뒤 세그먼트에서 증강 종류를 판별한다. 미매칭이면 {@code null}. */
    private static String matchAugType(String segment) {
        if (segment.contains(LsDataAug.AUG_WINTER)) {
            return LsDataAug.AUG_WINTER;
        }
        if (segment.contains(LsDataAug.AUG_NIGHT)) {
            return LsDataAug.AUG_NIGHT;
        }
        if (segment.contains(LsDataAug.AUG_RAIN)) {
            return LsDataAug.AUG_RAIN;
        }
        return null;
    }

    /** {@code _RESL_}/{@code _RES_} 뒤 세그먼트에서 해상도 코드를 뽑아 정규화한다. 미매칭이면 {@code null}. */
    private static String matchResolution(String segment) {
        Matcher m = RESL_CODE_PATTERN.matcher(segment);
        if (m.find()) {
            return LsDataAug.RESL_PREFIX + m.group(1);
        }
        return null;
    }
}
