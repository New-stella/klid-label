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
 * <p>미보유 필수 필드(pixel, cctv_height, cctv_azimuth, og_cd, cto, vqa, event_log, frames,
 * type, license_id, cctv_mng_no)는 null 로 두되 {@link NiaVideo} 의 ALWAYS 포함으로 키를 유지한다.
 *
 * <p>{@link ExportKind} 별 {@code anonymity} 오버라이드: ORIGINAL→"N", DEIDENTIFIED→"Y".
 */
@Component
public class VideoMetaMapper {

    private static final String YES = "Y";
    private static final String NO = "N";

    /**
     * @param meta 영상 메타 스냅샷 (필수 — 1차 소스)
     * @param raw  원시 영상 (선택 — null 허용, 메타 null 필드 폴백)
     * @param kind 산출 종류 (anonymity 오버라이드)
     */
    public NiaVideo toVideo(LsDatasetVideoMeta meta, LsDataRaw raw, ExportKind kind) {
        Long rawSn = firstNonNull(meta.getRawSn(), raw == null ? null : raw.getRawSn());
        String rawPath = firstNonNull(meta.getRawFilePathNm(), raw == null ? null : raw.getRawFilePathNm());
        LocalDateTime shtDt = firstNonNull(meta.getShtDt(), raw == null ? null : raw.getShtDt());
        Integer lenSec = firstNonNull(meta.getVdoLenSec(), raw == null ? null : raw.getDurationSec());
        String prvcTypeCd = firstNonNull(meta.getPrvcTypeCd(), raw == null ? null : raw.getPrvcTypeCd());
        String prvcYn = firstNonNull(meta.getPrvcYn(), raw == null ? null : raw.getPrvcYn());

        String basename = basename(rawPath);
        String anonymity = (kind == ExportKind.ORIGINAL) ? NO : YES;
        String pseudonymity = LsDataRaw.PRVC_TYPE_PSDO.equals(prvcTypeCd) ? YES : NO;

        return new NiaVideo(
                rawSn == null ? null : String.valueOf(rawSn),        // id
                basename,                                            // filename
                basename,                                            // orign_filename
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
                meta.getWthrNm(),                                    // weather (현재 null)
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
                meta.getDayNgtCd(),                                  // time_of_day
                meta.getSesnCd(),                                    // season
                null,                                                // cto (미보유)
                null,                                                // vqa (미보유)
                null                                                 // event_log (미보유)
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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
