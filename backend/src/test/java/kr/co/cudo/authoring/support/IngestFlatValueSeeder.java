package kr.co.cudo.authoring.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

/**
 * 관제 인입 평면값({@code LS_DATA_INGEST}) 테스트 시드 — <b>CCTV 명의 유일한 조달처</b>.
 *
 * <h3>왜 SQL 시드 파일이 아니라 헬퍼인가</h3>
 * <p>V167 로 CCTV 마스터({@code MNG_RESOURCE_CCTV})가 제거되면서 이름의 조달 축이
 * <b>CCTV({@code VMS_CCTV_ID}) → 영상({@code RAW_SN})</b> 으로 바뀌었다
 * ({@code IngestSourceLink}). 인입 행은 <b>영상 PK 를 알아야</b> 만들 수 있는데 그 PK 는 각 테스트가
 * 영상을 만들 때 생기므로, 구 {@code test-data-video.sql} 처럼 <b>미리</b> 넣어둘 수가 없다.
 *
 * <p>{@link #seedLegacyName}(2-인자)는 구 {@code test-data-video.sql} 의 CCTV 마스터 시드와
 * <b>같은 값</b>을 채워 기존 테스트의 기대값을 그대로 유지한다. 목록·검수 화면의 표시명·검색어
 * 픽스처가 여기에 의존하므로 값을 바꾸지 말 것.
 */
public final class IngestFlatValueSeeder {

    /** 구 {@code test-data-video.sql} CCTV 마스터 시드와 동일한 값(기대값 보존). */
    private static final Map<String, String> LEGACY_CCTV_NAMES = Map.of(
            "CCTV-001", "동대문구 회기로 CCTV",
            "CCTV-002", "강남구 테헤란로 CCTV");

    /**
     * 잘 알려진 CCTV ID({@code CCTV-001}/{@code CCTV-002})면 구 시드와 같은 이름으로 인입 행을 만든다.
     * 그 외 ID 는 <b>아무것도 하지 않는다</b> — 인입 행이 없으면 표시·검색 모두 {@code VMS_CCTV_ID}
     * 폴백이 되는데, 구 시드에서도 마스터에 없던 CCTV 는 정확히 그렇게 동작했다.
     */
    public static void seedLegacyName(JdbcTemplate jdbc, Long rawSn, String vmsCctvId) {
        seedName(jdbc, rawSn, vmsCctvId, LEGACY_CCTV_NAMES.get(vmsCctvId));
    }

    /**
     * 영상 1건에 대한 인입 행을 만들고 CCTV 명을 채운다.
     *
     * @param cctvNm 표시명. {@code null} 이면 인입 행을 만들지 않는다(= 마스터 미등록과 같은 상태).
     *               공백문자만 있는 값은 그대로 넣는다 — 표시·검색의 blank 폴백 판정 대상이다.
     */
    public static void seedName(JdbcTemplate jdbc, Long rawSn, String vmsCctvId, String cctvNm) {
        if (rawSn == null || cctvNm == null) {
            return;
        }
        jdbc.update("INSERT INTO LS_DATA_INGEST "
                        + "(RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, VDO_FILE_NM, RAW_FILE_PATH_NM, SRC_TYPE, "
                        + " RCPTN_DT, PRCS_STTS_CD, CCTV_NM) "
                        + "VALUES (?, ?, ?, 'clip.mp4', '/var/raw/clip.mp4', 'ORIGINAL', "
                        + "        CURRENT_TIMESTAMP, 'DONE', ?)",
                rawSn, "ING-" + rawSn, vmsCctvId, cctvNm);
    }

    private IngestFlatValueSeeder() {
    }
}
