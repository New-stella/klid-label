package kr.co.cudo.authoring.dataset.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * {@link DatasetMetaSourceRepository#findSnapshotSource} 결과 행(영상 1건 flatten 소스)의
 * Spring Data 인터페이스 프로젝션.
 *
 * <p>getter 이름은 native 쿼리의 큰따옴표 별칭과 정확히 일치한다. ffprobe {@code video.*} 값은
 * {@code LS_DATA_META.META_VL}(VARCHAR) 원본 그대로 문자열로 노출되며, 숫자/해상도 파싱과 파생은
 * {@code DatasetVideoMetaSnapshotService} 가 담당한다("값 0"과 "미상"을 파싱 단계에서 구분).
 */
public interface DatasetMetaSourceRow {

    Long getRawSn();

    Long getOrgnlRawSn();

    String getVmsClipId();

    String getVmsCctvId();

    String getRawFilePathNm();

    LocalDateTime getShtDt();

    Integer getVdoLenSec();

    String getLclgvCd();

    String getPrvcYn();

    String getPrvcTypeCd();

    String getDeIdentYn();

    String getEvntTypeCd();

    /**
     * 영상 출처 유형({@code LS_DATA_RAW.SRC_TYPE}) — {@code AI_CRT_YN} 동결의 <b>판정축</b>이다(R10).
     *
     * <p>{@code ORIGINAL·RELAY·USER_ULD·GENERATED·AUGMENTED}(인입 allowlist) 중 하나이거나, 컬럼 도입
     * 이전 레거시 행이면 null 이다. 판정은 {@code LsDataRaw.genAiYnOf} 한 곳이 소유한다.
     */
    String getSrcType();

    // ---- 촬영환경 수동입력(V130, 미입력이면 null → SHT_DT 파생 폴백) ----
    String getWthrNm();

    String getDayNgtCd();

    String getSesnCd();

    // ---- MNG 동결 ----
    String getCctvNm();

    BigDecimal getWgs84Lat();

    BigDecimal getWgs84Lot();

    String getSidoNm();

    String getSggNm();

    String getFileFmt();

    String getEvntNm();

    // ---- ffprobe video.* (LS_DATA_META, 문자열 원본) ----
    String getVideoCodec();

    String getVideoFps();

    String getVideoBitRate();

    String getVideoDurationMs();

    String getVideoFilesize();

    String getVideoResolution();
}
