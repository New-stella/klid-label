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

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
