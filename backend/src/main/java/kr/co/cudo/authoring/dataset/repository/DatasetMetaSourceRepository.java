package kr.co.cudo.authoring.dataset.repository;

import kr.co.cudo.authoring.common.datasource.ControlRepo;
import kr.co.cudo.authoring.eventtype.policy.EventTypeDisplayNamePolicy;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.IngestSourceLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 통합 메타 materialize 용 <b>동결 소스 조회</b> 리포지토리.
 *
 * <p>검수 승인(APPROVED) 시점에 영상 1건({@code RAW_SN})의 메타를 한 행으로 flatten 조회한다.
 * {@code LS_DATA_RAW}(원시) + {@code LS_DATA_META}(ffprobe {@code video.*} KV) + <b>관제 인입 평면값</b>
 * ({@code LS_DATA_INGEST} — CCTV명·좌표·파일형식) + 저작도구 소유 {@code LS_EVNT_TYPE}(이벤트명)
 * 을 결합한다.
 *
 * <p><b>CCTV명·좌표·파일형식 소스 전환(V167)</b>: 구 조달처였던 {@code MNG_RESOURCE_CCTV}
 * ·{@code MNG_CLIP_MASTER} 는 제거됐다. 연결 규칙(파생영상 {@code ORGNL_RAW_SN} 1단계 폴백 ·
 * LATERAL 단건 보장)은 {@link kr.co.cudo.authoring.video.repository.IngestSourceLink} 단일 진실원에서
 * 온다.
 *
 * <p><b>{@code sidoNm}/{@code sggNm} 은 상수 null 이다 — 값을 지어내지 않는다.</b> 구 조달처
 * {@code MNG_EX_LOCAL_GOV} 는 실DB <b>0행</b>이라 이 두 값은 <b>전환 전에도 이미 항상 null</b> 이었다
 * (동작 회귀 0). 인입이 주는 것은 지역명 <b>1필드</b>({@code LCLGV_NM}) 뿐이고 그 입도(시도인지
 * 시도+시군구인지)가 계약으로 확정되지 않았으므로, 시도 전용 필드에 넣으면 <b>틀린 값으로 확정</b>된다
 * — 이 저장소의 "null 보다 나쁜 대용값" 금지 규칙이다. 필드 자체는 하위호환(응답 키·타입 불변)을
 * 위해 유지한다. 관제와 입도를 합의하면 그때 채운다(협의 항목).
 *
 * <p><b>이벤트명(EVNT_NM) 소스 전환(V168)</b>: 구 조달처였던 관제 공유 마스터 2종은 제거됐다.
 * 이제 저작도구 소유 마스터({@code LS_EVNT_TYPE} + {@code LS_EVNT_CTGRY})에서 <b>표시명 4단
 * 폴백</b>으로 해석한다 — {@code COALESCE(운영자 표시명, 관제 수신 유형명, 카테고리명, 유형코드)}.
 * <p>★ <b>판정식은 {@link EventTypeDisplayNamePolicy#SQL_COALESCE} 를 재사용</b>한다(복제 금지).
 * 화면이 보는 표시명과 <b>승인 시점 동결값</b>이 갈라지면 export JSON {@code NiaVideo.event_name}
 * 이 화면과 다른 이름을 싣게 된다 — 그 드리프트를 구조적으로 막는다. 유형 PK 조회 + 카테고리
 * LEFT JOIN 이라 단건이 보장되고 fan-out 이 없다.
 *
 * <p><b>촬영환경(WTHR_NM·DAY_NGT_CD·SESN_CD)</b>: 작업자가 영상 단위로 수동 입력한 값(V130). 동결 시점에
 * 이 값이 있으면 {@code SHT_DT} 파생값보다 우선해 스냅샷에 담긴다({@code DatasetVideoMetaSnapshotService}).
 * 미입력(null)이면 기존 파생 규칙을 그대로 사용한다.
 *
 * <p>보안/정합:
 * <ul>
 *   <li>CWE-89: {@code RAW_SN} 은 {@code :rawSn} 파라미터 바인딩만 사용(문자열 결합 없음).</li>
 *   <li>이벤트 마스터 값과 video.* KV 는 상관 서브쿼리({@code ORDER BY ... LIMIT 1}), 인입 평면값은
 *       {@code LEFT JOIN LATERAL ... LIMIT 1} 로 조회해 조인 fan-out(행 증식)을 원천 차단한다 →
 *       영상 1건당 정확히 1행. 소스가 없으면 해당 컬럼만 null(부분 동결 허용).
 *       동일 키에 복수 행이 있어도 <b>결정적 ORDER BY</b>(PK/유니크 컬럼 DESC · LS_DATA_META 는 META_SN
 *       DESC=최신 · LS_DATA_INGEST 는 RCPTN_SN DESC=최신 수신)로 항상 같은 행을 선택해 해시
 *       비결정성을 배제한다.</li>
 *   <li>native 별칭은 큰따옴표로 감싸 camelCase 라벨을 보존 → 인터페이스 프로젝션 정확 매핑.</li>
 * </ul>
 *
 * <p>이 인터페이스는 조회 전용 소스 리포지토리라 매핑 엔티티는 사용하지 않지만, Spring Data 계약상
 * 도메인 타입이 필요하여 기존 {@link LsDataRaw}(LS_DATA_RAW 매핑) 를 도메인 타입으로 재사용한다.
 */
@ControlRepo
public interface DatasetMetaSourceRepository extends JpaRepository<LsDataRaw, Long> {

    /**
     * 영상 1건의 동결 소스 행을 조회한다. 존재하지 않으면 null.
     *
     * @param rawSn 대상 영상 PK
     * @return flatten 소스 프로젝션(1행) 또는 null
     */
    @Query(value = """
            SELECT
              r.RAW_SN            AS "rawSn",
              r.ORGNL_RAW_SN      AS "orgnlRawSn",
              r.VMS_CLIP_ID       AS "vmsClipId",
              r.VMS_CCTV_ID       AS "vmsCctvId",
              r.RAW_FILE_PATH_NM  AS "rawFilePathNm",
              r.SHT_DT            AS "shtDt",
              r.VDO_LEN_SEC       AS "vdoLenSec",
              r.LCLGV_CD          AS "lclgvCd",
              r.PRVC_YN           AS "prvcYn",
              r.PRVC_TYPE_CD      AS "prvcTypeCd",
              r.DE_IDENT_YN       AS "deIdentYn",
              r.EVNT_TYPE_CD      AS "evntTypeCd",
              r.SRC_TYPE          AS "srcType",
              r.WTHR_NM           AS "wthrNm",
              r.DAY_NGT_CD        AS "dayNgtCd",
              r.SESN_CD           AS "sesnCd",
              i.CCTV_NM                 AS "cctvNm",
              i.WGS84_LAT               AS "wgs84Lat",
              i.WGS84_LOT               AS "wgs84Lot",
              CAST(NULL AS VARCHAR)     AS "sidoNm",
              CAST(NULL AS VARCHAR)     AS "sggNm",
              i.FILE_FMT                AS "fileFmt",
              (SELECT
            """
            + EventTypeDisplayNamePolicy.SQL_COALESCE
            + """
                 FROM LS_EVNT_TYPE et
                 LEFT JOIN LS_EVNT_CTGRY ec
                        ON ec.EVNT_CLSF_CD = et.EVNT_CLSF_CD
                       AND ec.EVNT_CTGRY_CD = et.EVNT_CTGRY_CD
                WHERE et.EVNT_TYPE_CD = r.EVNT_TYPE_CD LIMIT 1) AS "evntNm",
              (SELECT m.META_VL FROM LS_DATA_META m WHERE m.RAW_SN = r.RAW_SN AND m.META_KEY = 'video.codec' ORDER BY m.META_SN DESC LIMIT 1) AS "videoCodec",
              (SELECT m.META_VL FROM LS_DATA_META m WHERE m.RAW_SN = r.RAW_SN AND m.META_KEY = 'video.fps' ORDER BY m.META_SN DESC LIMIT 1) AS "videoFps",
              (SELECT m.META_VL FROM LS_DATA_META m WHERE m.RAW_SN = r.RAW_SN AND m.META_KEY = 'video.bit_rate' ORDER BY m.META_SN DESC LIMIT 1) AS "videoBitRate",
              (SELECT m.META_VL FROM LS_DATA_META m WHERE m.RAW_SN = r.RAW_SN AND m.META_KEY = 'video.duration_ms' ORDER BY m.META_SN DESC LIMIT 1) AS "videoDurationMs",
              (SELECT m.META_VL FROM LS_DATA_META m WHERE m.RAW_SN = r.RAW_SN AND m.META_KEY = 'video.filesize' ORDER BY m.META_SN DESC LIMIT 1) AS "videoFilesize",
              (SELECT m.META_VL FROM LS_DATA_META m WHERE m.RAW_SN = r.RAW_SN AND m.META_KEY = 'video.resolution' ORDER BY m.META_SN DESC LIMIT 1) AS "videoResolution"
            FROM LS_DATA_RAW r
            """
            + IngestSourceLink.SQL_LATERAL_JOIN
            + """
            WHERE r.RAW_SN = :rawSn
            """, nativeQuery = true)
    DatasetMetaSourceRow findSnapshotSource(@Param("rawSn") Long rawSn);
}
