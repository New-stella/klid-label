package kr.co.cudo.authoring.dataset.export.json;

import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.ExportKind;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@link LsDatasetVideoMeta}(+ 선택적 {@link LsDataRaw} 폴백) → {@link NiaVideo} 순수 매퍼.
 *
 * <p>미보유 필수 필드(pixel, cctv_height, cctv_azimuth, og_cd, event_log, vd_description, frames,
 * type, license_id, cctv_mng_no)는 null 로 두되 {@link NiaVideo} 의 ALWAYS 포함으로 키를 유지한다.
 *
 * <p>{@link ExportKind} 별 {@code anonymity} 오버라이드: ORIGINAL→"N", DEIDENTIFIED→"Y".
 */
@Component
public class VideoMetaMapper {

    private static final String YES = "Y";
    private static final String NO = "N";

    /** deid 영상 경로 없이 조립(하위호환 오버로드). DEIDENTIFIED 는 filename 이 null 이 된다(fail-secure). */
    public NiaVideo toVideo(LsDatasetVideoMeta meta, LsDataRaw raw, ExportKind kind) {
        return toVideo(meta, raw, kind, null);
    }

    /**
     * @param meta          영상 메타 스냅샷 (필수 — 1차 소스)
     * @param raw           원시 영상 (선택 — null 허용, 메타 null 필드 폴백)
     * @param kind          산출 종류 (anonymity 오버라이드 + 경로 소스 선택)
     * @param deidVideoPath 비식별 영상 경로(LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM, null 허용) —
     *                      DEIDENTIFIED 산출의 filename/orign_filename 소스. null 이면 fail-secure 로
     *                      원본 파일명 대신 null(원본 경로가 비식별 산출물에 새지 않도록, CWE-359).
     */
    public NiaVideo toVideo(LsDatasetVideoMeta meta, LsDataRaw raw, ExportKind kind, String deidVideoPath) {
        Long rawSn = firstNonNull(meta.getRawSn(), raw == null ? null : raw.getRawSn());
        String rawPath = firstNonNull(meta.getRawFilePathNm(), raw == null ? null : raw.getRawFilePathNm());
        LocalDateTime shtDt = firstNonNull(meta.getShtDt(), raw == null ? null : raw.getShtDt());
        Integer lenSec = firstNonNull(meta.getVdoLenSec(), raw == null ? null : raw.getDurationSec());
        String prvcTypeCd = firstNonNull(meta.getPrvcTypeCd(), raw == null ? null : raw.getPrvcTypeCd());
        String prvcYn = firstNonNull(meta.getPrvcYn(), raw == null ? null : raw.getPrvcYn());

        // 촬영환경(날씨·시간대·계절)만 우선순위가 <b>반대</b>다 — raw(수동 저장값) → meta(동결 파생값).
        // 위 필드들은 승인 시점 동결값이 정본이라 meta 우선이지만, 촬영환경은 작업자가 승인 후에도
        // 정정할 수 있는 수동 입력값이므로 최신 수동값이 export 에 반영돼야 한다(수동값 없으면 기존 동결값 유지).
        // 영상 단위 값이라 ExportKind(ORIGINAL/DEIDENTIFIED)로 분기하지 않는다 — 2벌 산출이 동일해야 한다.
        // blank→null 정규화: 서비스 경유 입력은 정규화되나 DB 직접/레거시 행에 빈 문자열이 있으면
        // export 는 ""(빈값)로, 스냅샷은 null 로 표현이 갈린다(F). 여기서도 blank 를 null 로 맞춰 일치시킨다.
        String weather = firstNonBlank(raw == null ? null : raw.getWthrNm(), meta.getWthrNm());
        String timeOfDay = firstNonBlank(raw == null ? null : raw.getDayNgtCd(), meta.getDayNgtCd());
        String season = firstNonBlank(raw == null ? null : raw.getSesnCd(), meta.getSesnCd());

        // kind 별 영상 경로: ORIGINAL=원본 raw, DEIDENTIFIED=비식별 경로. filename 은 이 경로의
        // basename 이며, deid 경로 미상이면 null 로 두어 원본 파일명 노출을 막는다(fail-secure).
        String kindVideoPath = (kind == ExportKind.ORIGINAL) ? rawPath : deidVideoPath;
        String basename = basename(kindVideoPath);
        String anonymity = (kind == ExportKind.ORIGINAL) ? NO : YES;
        String pseudonymity = LsDataRaw.PRVC_TYPE_PSDO.equals(prvcTypeCd) ? YES : NO;

        return new NiaVideo(
                rawSn == null ? null : String.valueOf(rawSn),        // id
                basename,                                            // filename
                shtDt == null ? null : shtDt.toLocalDate().toString(), // date_created (YYYY-MM-DD)
                null,                                                // type (미보유)
                meta.getFileFmt(),                                   // format
                meta.getFileSz(),                                    // filesize
                composeLocation(meta.getSidoNm(), meta.getSggNm()),  // location
                null,                                                // license_id (미보유)
                lenSec == null ? null : String.valueOf(lenSec),      // length
                asString(meta.getFps()),                             // fps
                null,                                                // frames (미보유)
                asString(meta.getAsprtRt()),                         // aspect_ratio
                meta.getVdoWdth(),                                   // width
                meta.getVdoHgt(),                                    // height
                meta.getResl(),                                      // resolution
                meta.getBitRt() == null ? null : String.valueOf(meta.getBitRt()), // bit
                null,                                                // pixel (미보유)
                weather,                                             // weather (수동값 우선)
                composeCoordinates(meta.getWgs84Lat(), meta.getWgs84Lot()), // coordinates
                null,                                                // og_cd (미보유)
                meta.getCctvNm(),                                    // cctv_name
                null,                                                // cctv_height (미보유)
                null,                                                // cctv_azimuth (미보유)
                null,                                                // cctv_mng_no (미보유)
                anonymity,                                           // anonymity (kind 오버라이드)
                pseudonymity,                                        // pseudonymity
                prvcYn,                                              // privacy_included
                meta.getEvntTypeCd(),                                // event_id
                meta.getEvntNm(),                                    // event_name
                timeOfDay,                                           // time_of_day (수동값 우선)
                season,                                              // season (수동값 우선)
                null,                                                // event_log (미보유)
                null                                                 // vd_description (미보유 — 데이터 출처 없음)
        );
    }

    private static String composeLocation(String sido, String sgg) {
        if (isBlank(sido) && isBlank(sgg)) {
            return null;
        }
        return (nvl(sido) + " " + nvl(sgg)).trim();
    }

    private static String composeCoordinates(BigDecimal lat, BigDecimal lot) {
        if (lat == null && lot == null) {
            return null;
        }
        return (lat == null ? "" : lat.toPlainString()) + "," + (lot == null ? "" : lot.toPlainString());
    }

    private static String asString(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }

    /** 경로에서 파일명만 추출 ('/' '\' 모두 처리). null/blank → null. */
    private static String basename(String path) {
        if (isBlank(path)) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        String name = (idx >= 0) ? normalized.substring(idx + 1) : normalized;
        return name.isBlank() ? null : name;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    /** 첫 번째 non-blank 문자열(공백만 있는 값은 미입력으로 간주). 모두 blank/null 이면 null(스냅샷 표현 일치). */
    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
