package kr.co.cudo.authoring.dataset.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.entity.LsMetaReplOutbox;
import kr.co.cudo.authoring.dataset.repository.DatasetMetaSourceRepository;
import kr.co.cudo.authoring.dataset.repository.DatasetMetaSourceRow;
import kr.co.cudo.authoring.dataset.repository.LsDatasetVideoMetaRepository;
import kr.co.cudo.authoring.dataset.repository.LsMetaReplOutboxRepository;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnno;
import kr.co.cudo.authoring.evntanno.entity.LsEvntAnnoReview;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoRepository;
import kr.co.cudo.authoring.evntanno.repository.LsEvntAnnoReviewRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 검수 승인(APPROVED) 시점에 영상 메타를 통합 물리 테이블 {@code LS_DATASET_VIDEO_META} 에
 * <b>동결(materialize)</b> 하는 어댑터.
 *
 * <p>{@code ReviewService.approve()} 의 {@code controlTransactionManager} 트랜잭션에 편승(REQUIRED)한다.
 * 따라서 APPROVED 전이 + {@code LS_LABEL_VERSION} 스냅샷 + 통합 메타 동결 + outbox 이벤트가
 * <b>하나의 커밋</b>으로 원자 확정된다. materialize 중 예외가 나면 승인 전체가 함께 롤백된다(정합성 우선).
 *
 * <p>동시성/불변식(CWE-362) — "활성 스냅샷은 RAW_SN 당 항상 정확히 1건":
 * <ol>
 *   <li>{@code acquireRawLock(rawSn)} — rawSn 단위 advisory 트랜잭션 락으로 동시 승인 직렬화.</li>
 *   <li>deactivate-then-insert 순서 — 기존 활성('Y')을 먼저 'N' 으로 내린 뒤 신규 활성 삽입
 *       (insert-then-deactivate 는 순간 2 활성으로 부분 유니크 인덱스 위반).</li>
 *   <li>{@code activateByHash} — 과거와 동일 해시 재승인(A→B→A)으로 대상 행이 이미 'N' 으로 존재하면
 *       {@code ON CONFLICT DO NOTHING} 이 되살리지 못하므로 명시 재활성 → 활성 0건 방지.</li>
 *   <li>DB fail-safe: 부분 유니크 인덱스(V99)가 활성 2건을 원천 차단.</li>
 * </ol>
 *
 * <p>보안:
 * <ul>
 *   <li>인가: 본 서비스는 REVIEWER 승인 트랜잭션 내부 전용 호출이라 자체 우회 인가 가드를 추가하지 않는다
 *       (approve 경로의 기존 REVIEWER 가드 유지). rawSn 파라미터는 파라미터 바인딩(CWE-89).</li>
 *   <li>페이로드/로그: 동결 대상은 비식별 메타(경로/좌표/코드값)만으로 원본 비-비식별 이미지·PII·토큰을
 *       포함하지 않는다. 로그는 rawSn·hash prefix 등 식별자만 남긴다(CWE-359/209).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetVideoMetaSnapshotService {

    private final DatasetMetaSourceRepository sourceRepository;
    private final LsDatasetVideoMetaRepository metaRepository;
    private final LsMetaReplOutboxRepository outboxRepository;
    private final LsEvntAnnoRepository evntAnnoRepository;
    private final LsEvntAnnoReviewRepository evntAnnoReviewRepository;
    private final SnapshotHasher snapshotHasher;
    private final ObjectMapper objectMapper;

    /**
     * 영상 1건의 통합 메타를 동결 적재한다(승인 트랜잭션 편승) — 승인 확정 시각은 {@code now()}.
     *
     * @param rawSn 검수 승인된 영상 PK
     */
    @Transactional("controlTransactionManager")
    public void materialize(Long rawSn) {
        materialize(rawSn, null);
    }

    /**
     * 영상 1건의 통합 메타를 동결 적재한다(승인 트랜잭션 편승).
     *
     * <p>{@code reviewCompletedAt} 오버로드는 <b>백필</b>(배포 이전 기존 APPROVED 영상 소급 동결)에서
     * 실제 과거 승인 시각(예: {@code LS_RAW_DATA_STATUS.UPD_DT})을 {@code RVW_CMPL_DT} 로 보존하려고
     * 사용한다. {@code RVW_CMPL_DT} 는 관리 컬럼이라 멱등 해시({@link #buildHashFields})에 포함되지
     * 않으므로 승인 시각이 달라도 동일 페이로드는 동일 해시로 멱등 식별된다.
     *
     * @param rawSn             검수 승인된 영상 PK
     * @param reviewCompletedAt 승인 확정 시각(null 이면 {@code now()} — 실시간 승인 경로)
     */
    @Transactional("controlTransactionManager")
    public void materialize(Long rawSn, LocalDateTime reviewCompletedAt) {
        if (rawSn == null) {
            throw new IllegalArgumentException("rawSn 은 필수입니다.");
        }
        // 1) rawSn 직렬화 락 — 동시 승인 race 방지(트랜잭션 종료 시 자동 해제).
        metaRepository.acquireRawLock(rawSn);

        // 2) 동결 소스 조회(LS_DATA_RAW + LS_DATA_META video.* + MNG_*).
        DatasetMetaSourceRow src = sourceRepository.findSnapshotSource(rawSn);
        if (src == null) {
            // 승인 대상 영상이 조회되지 않는 비정상 상태 — 동결 스킵(승인 자체는 상위 가드에서 이미 검증됨).
            log.warn("[Dataset] materialize skipped — no source rawSn={}", rawSn);
            return;
        }

        // 3) 파생/파싱 — 해상도(WxH) → 가로/세로/화면비, video.* 문자열 → 숫자.
        Resolution resolution = parseResolution(src.getVideoResolution());
        BigDecimal aspectRatio = deriveAspectRatio(resolution);
        // 촬영환경 3필드의 원천은 축마다 다르다(2026-07-31 정정 — 구 "3필드 모두 수동값이 유일한 원천" 폐기):
        //   · 날씨(WTHR_NM)      : <작업자 수동 저장값(LS_DATA_RAW, V130)이 유일한 원천>. 관제는 WTHR_CD 를
        //                          주지만 코드값↔표시명 대응표가 없어 저작도구가 소비하지 않는다(사용자 확정).
        //   · 시간대·계절        : 수동 저장값 <또는 관제 적재 시 채택값>(관제 공유 이벤트리스트 시간대/계절이
        //                          저작도구 허용 어휘와 그대로 일치할 때만 채택 — ControlClipMetaResolver).
        // 어느 쪽이든 <관측값>이며 추정값이 아니라는 점은 동일하다.
        // 미입력이면 null(미상)을 그대로 동결한다 — SHT_DT 기반 추정(self-fill)을 하지 않는다(E-ISSUE-42).
        //   · 이 동결값은 export JSON(video.time_of_day/season/weather)과 데이터마트 뷰
        //     (export 산출 JSON 의 촬영환경)으로 <출처 구분자 없이> 전파되므로, 추정값을 실으면
        //     관제/데이터마트가 관측값과 구분 없이 소비한다. 실증: 여름 18:00 촬영분이 구 규칙
        //     (hour>=18 → NGT)에서 야간으로 오분류됐다(한국 7월 일몰 ≈ 19:50).
        //   · "동결된 non-null 값은 전부 <관측값>(수동 입력 또는 관제 채택)" 이라 출처 구분 컬럼이
        //     불필요하다 — 단 이 단언은 <레거시 정정 백필 완료를 전제로 한 참>이다. 파생 폴백 폐기
        //     <이전>에 이미 NGT/SUMMER 로 동결된 스냅샷 행이 남아 있는 동안에는 거짓이었다(관제가
        //     추정값을 관측값과 구분 못 함). 그 행들은
        //     DatasetVideoMetaBackfillService#correctDerivedShootingEnvironment 가 재동결로 null 정정하며,
        //     정정 후에는 이 경로가 관측값만 동결하므로 단언이 다시 참이 된다.
        //   · 조회 API(EnvironmentMetaService)의 파생 폴백은 <화면 프리필>이며 응답에 source(MANUAL/DERIVED)
        //     를 함께 내려 투명하므로 유지한다(동결·산출 경로만 추정을 제거).
        // 공백은 미입력(null)으로 정규화해 동결값·해시 표현을 일치시킨다(3필드 공통 규칙).
        String dayNight = nullIfBlank(src.getDayNgtCd());
        String season = nullIfBlank(src.getSesnCd());
        String weather = nullIfBlank(src.getWthrNm());
        boolean derivative = (src.getOrgnlRawSn() != null);
        // AI 생성 여부(R10, 2026-08-05 정정) — 판정축은 SRC_TYPE 하나이며 단일 원천은 LsDataRaw.genAiYnOf 다.
        //   구 도출식은 <파생 여부(ORGNL_RAW_SN != null)>로만 계산해, 관제가 SRC_TYPE='GENERATED' 로 인입한
        //   <AI 생성 원본>을 'N' 으로 잘못 동결했다(원본이라 ORGNL_RAW_SN 이 null 이기 때문).
        //   같은 규칙이 완료 통지(ControlNotifyPayloadFactory)와 뷰 V_COMPLETED_VIDEO.GEN_AI_YN(설계 D2)에도
        //   있으므로 <자바 쪽은 이 헬퍼 한 곳으로 수렴>시킨다 — 판정을 여기에 복제하지 말 것.
        //   ⚠ 파생영상은 생성 팩토리(createFromAugment/createFromResolution)가 항상 'AUGMENTED' 를 넣으므로
        //     결과가 종전과 같다. SRC_TYPE 컬럼 도입 이전 레거시 파생 행(null)만 'N' 이 되는데, 이는
        //     "생성형 AI 산출물이라는 근거가 없으면 아니다"라는 확정 판정식(뷰 D2 와 동일)의 귀결이다.
        String aiCreatedYn = LsDataRaw.genAiYnOf(src.getSrcType());
        // 파생영상(증강·해상도)에는 <원본 영상이 존재하지 않는다> — 비식별 사본 한 벌만 있다. 따라서
        // "원본 영상 경로"로 동결·노출할 값 자체가 없으므로 null 로 동결한다(관제 뷰
        // V_COMPLETED_VIDEO.ORGNL_VDO_PATH_NM = m.RAW_FILE_PATH_NM 이 파생 행에서 NULL 이 된다).
        // 이 한 곳이 관제 노출의 단일 진입점이라 증강/해상도 두 파생 경로가 동시에 정합된다.
        // 비식별 영상 경로는 뷰의 DE_IDNTF_FILE_PATH_NM(procLog 적재값, V138)으로 여전히 제공된다.
        // 주의: 산출물 co-locate base 는 라이브 LS_DATA_RAW.RAW_FILE_PATH_NM 을 쓰므로(export 우선순위)
        // 이 동결값을 비워도 파생영상 export 는 그대로 동작한다.
        String frozenRawFilePathNm = derivative ? null : src.getRawFilePathNm();

        BigDecimal fps = parseBigDecimal(src.getVideoFps());
        Long bitRate = parseLong(src.getVideoBitRate());
        Long fileSize = parseLong(src.getVideoFilesize());

        // 3-1) event_annotation 동결(C2) — 이 시점에 승인(APPROVED)된 event_annotation payload 원문.
        //      미승인/부재 시 null(동결 대상 없음). 동결 내용이므로 멱등 해시에 포함해, event_annotation 만
        //      바뀐 뒤 재승인해도 새 스냅샷 버전이 append 되게 한다(버전-per-내용 정합).
        String frozenEventAnno = resolveApprovedEventAnnotation(rawSn);

        // 4) 멱등키 — 동결 내용(관리 컬럼 제외)의 정규화 해시.
        String hash = snapshotHasher.hash(buildHashFields(
                src, resolution, aspectRatio, dayNight, season, weather, aiCreatedYn, fps, bitRate, fileSize,
                frozenEventAnno, frozenRawFilePathNm));

        // 5) 엔티티 조립. RVW_CMPL_DT 는 백필이면 과거 승인 시각(소급), 실시간 승인이면 now().
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime rvwCmplDt = (reviewCompletedAt != null) ? reviewCompletedAt : now;
        LsDatasetVideoMeta snapshot = LsDatasetVideoMeta.builder()
                .rawSn(rawSn)
                .snpshtHash(hash)
                .activeYn(LsDatasetVideoMeta.ACTIVE_YES)
                .orgnlRawSn(src.getOrgnlRawSn())
                .vmsClipId(src.getVmsClipId())
                .vmsCctvId(src.getVmsCctvId())
                .rawFilePathNm(frozenRawFilePathNm) // 파생영상은 원본 부재 → null 동결(관제 미노출).
                .shtDt(src.getShtDt())
                .vdoLenSec(src.getVdoLenSec())
                .lclgvCd(src.getLclgvCd())
                .prvcYn(src.getPrvcYn())
                .prvcTypeCd(src.getPrvcTypeCd())
                .deIdentYn(src.getDeIdentYn())
                .aiCrtYn(aiCreatedYn)
                .evntTypeCd(src.getEvntTypeCd())
                .cctvNm(src.getCctvNm())
                .wgs84Lat(src.getWgs84Lat())
                .wgs84Lot(src.getWgs84Lot())
                .sidoNm(src.getSidoNm())
                .sggNm(src.getSggNm())
                .fileFmt(src.getFileFmt())
                .evntNm(src.getEvntNm())
                .vdoCdc(src.getVideoCodec())
                .fps(fps)
                .bitRt(bitRate)
                .asprtRt(aspectRatio)
                .resl(src.getVideoResolution())
                .vdoWdth(resolution.width())
                .vdoHgt(resolution.height())
                .fileSz(fileSize)
                .dayNgtCd(dayNight)
                .sesnCd(season)
                .wthrNm(weather) // 작업자 수동 입력값(미입력이면 null).
                .evntAnnoCn(frozenEventAnno) // 승인된 event_annotation 동결(없으면 null).
                .rvwCmplDt(rvwCmplDt)
                .regDt(now)
                .regId(null) // materialize(rawSn) 계약상 등록자 미전달 — 감사자는 승인 이벤트/버전 스냅샷으로 추적.
                .build();

        // 6) deactivate-then-insert(+reactivate) — 활성 1건 불변식(부분 유니크 인덱스 정합).
        metaRepository.deactivatePrevious(rawSn, hash);
        int inserted = metaRepository.upsertSnapshot(snapshot);
        if (inserted == 0) {
            // 이미 존재하는 (rawSn, hash) — 과거와 동일 페이로드 재승인. 대상 행을 활성으로 되살린다.
            metaRepository.activateByHash(rawSn, hash);
        }

        // 7) outbox 이벤트 insert(같은 트랜잭션 커밋) — 포털 복제(Phase 3)용.
        //    직전에 같은 rawSn 의 기존 PENDING outbox 를 SUPERSEDED 로 coalescing 한다(주 방어):
        //    advisory 락으로 직렬화된 이 구간에서 옛(오래된 해시) outbox 재전달을 원천 차단해 포털이
        //    stale 해시로 되살아나는 순서 역전(CWE-362)을 막는다. rawSn 당 PENDING 최대 1건 보장.
        outboxRepository.supersedePending(rawSn);
        outboxRepository.save(LsMetaReplOutbox.create(rawSn, hash, toPayload(snapshot)));

        log.info("[Dataset] materialized rawSn={} hashPrefix={} inserted={}",
                rawSn, hash.substring(0, Math.min(8, hash.length())), inserted == 1);
    }

    /**
     * 해시 입력 필드 맵(컬럼명 → 정규화 문자열) — 동결 <b>내용</b>만 포함하고 관리 컬럼
     * (ACTIVE_YN·RVW_CMPL_DT·REG_DT·REG_ID·PK·해시)은 제외한다(재승인 멱등성).
     */
    private Map<String, String> buildHashFields(DatasetMetaSourceRow src, Resolution resolution,
                                                BigDecimal aspectRatio, String dayNight, String season,
                                                String weather, String aiCreatedYn, BigDecimal fps,
                                                Long bitRate, Long fileSize,
                                                String frozenEventAnno, String frozenRawFilePathNm) {
        Map<String, String> f = new TreeMap<>();
        f.put("EVNT_ANNO_CN", frozenEventAnno);
        f.put("RAW_SN", str(src.getRawSn()));
        f.put("ORGNL_RAW_SN", str(src.getOrgnlRawSn()));
        f.put("VMS_CLIP_ID", src.getVmsClipId());
        f.put("VMS_CCTV_ID", src.getVmsCctvId());
        // 해시 입력도 <동결값>이어야 한다 — 소스값(src)을 넣으면 파생영상에서 "동결된 내용"과 "해시가
        // 대표하는 내용"이 어긋나 멱등 식별이 깨진다.
        f.put("RAW_FILE_PATH_NM", frozenRawFilePathNm);
        f.put("SHT_DT", str(src.getShtDt()));
        f.put("VDO_LEN_SEC", str(src.getVdoLenSec()));
        f.put("LCLGV_CD", src.getLclgvCd());
        f.put("PRVC_YN", src.getPrvcYn());
        f.put("PRVC_TYPE_CD", src.getPrvcTypeCd());
        f.put("DE_IDENT_YN", src.getDeIdentYn());
        f.put("AI_CRT_YN", aiCreatedYn);
        f.put("EVNT_TYPE_CD", src.getEvntTypeCd());
        f.put("CCTV_NM", src.getCctvNm());
        f.put("WGS84_LAT", str(src.getWgs84Lat()));
        f.put("WGS84_LOT", str(src.getWgs84Lot()));
        f.put("SIDO_NM", src.getSidoNm());
        f.put("SGG_NM", src.getSggNm());
        f.put("FILE_FMT", src.getFileFmt());
        f.put("EVNT_NM", src.getEvntNm());
        f.put("VDO_CDC", src.getVideoCodec());
        f.put("FPS", str(fps));
        f.put("BIT_RT", str(bitRate));
        f.put("ASPRT_RT", str(aspectRatio));
        f.put("RESL", src.getVideoResolution());
        f.put("VDO_WDTH", str(resolution.width()));
        f.put("VDO_HGT", str(resolution.height()));
        f.put("FILE_SZ", str(fileSize));
        // 주야간/계절은 <키를 항상 유지>한다(WTHR_NM 과 달리 구 스킴에도 키가 있었으므로 인코딩 불변).
        // 파생 폐지로 미입력 영상의 값이 "NGT/SUMMER" → null 로 바뀌므로 해시가 달라지는데, 이는
        // 동결 <내용>이 실제로 달라진 것이라 재승인 시 새 버전이 append 되는 것이 정상이다.
        f.put("DAY_NGT_CD", dayNight);
        f.put("SESN_CD", season);
        // 촬영환경 수동값도 동결 '내용'이라 해시에 포함 — 날씨만 정정 후 재승인해도 새 버전이 append 된다.
        // 단, 미입력(null)이면 <b>키 자체를 생략</b>한다(하위호환): 해시는 null 도 "-1:" 토큰으로 인코딩하므로
        // 키를 넣으면 WTHR_NM 도입 이전에 승인된(내용 무변경) 영상이 재동결될 때 해시가 달라져
        // 동일 내용 중복 버전 + 불필요한 포털 복제 outbox 가 생긴다("동일 페이로드=동일 해시" 멱등 불변식 위반).
        if (weather != null) {
            f.put("WTHR_NM", weather);
        }
        return f;
    }

    /**
     * 승인 시점에 <b>동결할 event_annotation</b> payload 원문을 조회한다 — 영상(rawSn)의
     * event_annotation 이 존재하고 그 검토가 {@code APPROVED} 인 경우에만 payload(jsonb 원문)를 반환한다.
     *
     * <p>승인 안 됐거나(AUTO_GENERATED/PENDING/REJECTED) event_annotation 자체가 없으면 null(동결 대상 없음).
     * 조회는 파생 쿼리 파라미터 바인딩만 사용하고(CWE-89), 로그는 rawSn·상태 식별자만 남긴다(CWE-359).
     *
     * @param rawSn 검수 승인된 영상 PK
     * @return 승인된 event_annotation payload(JSON 문자열), 없으면 null
     */
    private String resolveApprovedEventAnnotation(Long rawSn) {
        LsEvntAnno anno = evntAnnoRepository.findByRawSn(rawSn).orElse(null);
        if (anno == null) {
            return null;
        }
        // 최신 검토(RVW_SN DESC)를 결정적으로 선택해 그 상태가 APPROVED 일 때만 동결한다 —
        // 승인/자동확정 경로와 동일한 최신-검토 기준(중복 row 비결정 선택 방지, CWE 미해당 정합성).
        boolean approved = evntAnnoReviewRepository.findByEvntAnnoSn(anno.getEvntAnnoSn()).stream()
                .findFirst()
                .map(r -> LsEvntAnnoReview.STTS_APPROVED.equals(r.getRvwSttsCd()))
                .orElse(false);
        if (!approved) {
            log.info("[Dataset] event_annotation not approved — freeze skipped rawSn={}", rawSn);
            return null;
        }
        return anno.getAnnoCn();
    }

    /** 공백 문자열을 미입력(null)으로 정규화 — 동결값과 해시 입력의 "미입력" 표현을 일치시킨다. */
    private static String nullIfBlank(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /** BigDecimal 은 표기 편차(지수/후행 0)를 없애 결정성을 확보한다. */
    private static String str(BigDecimal v) {
        return v == null ? null : v.stripTrailingZeros().toPlainString();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 직렬화 페이로드(포털 복제 전달용) — 비식별 메타만. 컬럼형 JSON.
     *
     * <p>@design INT-009 「복제 범위 — 원본과 동형이며 전 컬럼을 옮긴다」 — 이 메서드가 <b>무엇을 포털로
     * 복제할지 고르는 자리</b>다. 동결 스냅샷에 컬럼이 추가되면 여기와 {@code MetaReplicationPayload},
     * 그리고 복원 빌더({@code MetaReplicationWorker.toSnapshot})가 함께 따라와야 한다.
     * 관리 컬럼(PK·ACTIVE_YN·REG_DT·REG_ID)은 복제 시 워커가 채우므로 의도적으로 제외한다.
     */
    private String toPayload(LsDatasetVideoMeta m) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("rawSn", m.getRawSn());
        p.put("snpshtHash", m.getSnpshtHash());
        p.put("orgnlRawSn", m.getOrgnlRawSn());
        p.put("vmsClipId", m.getVmsClipId());
        p.put("vmsCctvId", m.getVmsCctvId());
        p.put("rawFilePathNm", m.getRawFilePathNm());
        p.put("shtDt", m.getShtDt() == null ? null : m.getShtDt().toString());
        p.put("vdoLenSec", m.getVdoLenSec());
        p.put("lclgvCd", m.getLclgvCd());
        p.put("prvcYn", m.getPrvcYn());
        p.put("prvcTypeCd", m.getPrvcTypeCd());
        p.put("deIdentYn", m.getDeIdentYn());
        p.put("aiCrtYn", m.getAiCrtYn());
        p.put("evntTypeCd", m.getEvntTypeCd());
        p.put("cctvNm", m.getCctvNm());
        p.put("wgs84Lat", m.getWgs84Lat());
        p.put("wgs84Lot", m.getWgs84Lot());
        p.put("sidoNm", m.getSidoNm());
        p.put("sggNm", m.getSggNm());
        p.put("fileFmt", m.getFileFmt());
        p.put("evntNm", m.getEvntNm());
        p.put("vdoCdc", m.getVdoCdc());
        p.put("fps", m.getFps());
        p.put("bitRt", m.getBitRt());
        p.put("asprtRt", m.getAsprtRt());
        p.put("resl", m.getResl());
        p.put("vdoWdth", m.getVdoWdth());
        p.put("vdoHgt", m.getVdoHgt());
        p.put("fileSz", m.getFileSz());
        p.put("dayNgtCd", m.getDayNgtCd());
        p.put("sesnCd", m.getSesnCd());
        p.put("wthrNm", m.getWthrNm()); // 촬영환경 수동값 — 포털 복제 반영(비식별 메타).
        // @design INT-009 — event_annotation 동결값. 동결·해시에는 이미 반영돼 있었으나 이 직렬화 입구에서만
        // 빠져 있어 포털 복제본의 EVNT_ANNO_CN 이 영구히 비어 있었다. 값은 jsonb 원문 문자열 그대로 싣는다.
        p.put("evntAnnoCn", m.getEvntAnnoCn());
        p.put("rvwCmplDt", m.getRvwCmplDt() == null ? null : m.getRvwCmplDt().toString());
        try {
            return objectMapper.writeValueAsString(p);
        } catch (JsonProcessingException e) {
            // 직렬화 실패는 내부 오류 — 승인 전체를 안전하게 롤백(fail-closed)한다. 본문/PII 미출력.
            log.error("[Dataset] outbox payload serialize failed rawSn={}", m.getRawSn());
            throw new IllegalStateException("outbox payload 직렬화 실패", e);
        }
    }

    /** "WIDTHxHEIGHT" 문자열을 (width, height) 로 파싱한다. 형식 불일치/미상 시 (null, null). */
    private static Resolution parseResolution(String resl) {
        if (resl == null || resl.isBlank()) {
            return Resolution.EMPTY;
        }
        String[] parts = resl.toLowerCase().split("x");
        if (parts.length != 2) {
            return Resolution.EMPTY;
        }
        Integer w = parsePositiveInt(parts[0]);
        Integer h = parsePositiveInt(parts[1]);
        return new Resolution(w, h);
    }

    /** 화면비(종횡비) = width / height (소수 6자리). 둘 중 하나라도 없거나 0 이면 null. */
    private static BigDecimal deriveAspectRatio(Resolution resolution) {
        Integer w = resolution.width();
        Integer h = resolution.height();
        if (w == null || h == null || h == 0) {
            return null;
        }
        return new BigDecimal(w).divide(new BigDecimal(h), 6, RoundingMode.HALF_UP);
    }

    private static Integer parsePositiveInt(String s) {
        try {
            int v = Integer.parseInt(s.trim());
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parseBigDecimal(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 파싱된 해상도(가로/세로). 미상은 null. */
    private record Resolution(Integer width, Integer height) {
        static final Resolution EMPTY = new Resolution(null, null);
    }
}
