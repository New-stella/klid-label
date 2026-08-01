package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.entity.MngClipEvntLst;
import kr.co.cudo.authoring.video.entity.MngClipMaster;
import kr.co.cudo.authoring.video.event.VideoIngestedEvent;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 클립 1건 적재의 트랜잭션 경계 빈.
 *
 * <p>각 클립을 {@link Propagation#REQUIRES_NEW} 독립 트랜잭션({@code controlTransactionManager})으로
 * 적재한다 — 한 클립의 JPA 예외(예: {@link DataIntegrityViolationException})로 트랜잭션이
 * rollback-only 마킹되더라도 그 롤백이 해당 클립 트랜잭션에만 한정되어, 같은 스캔의 다른 클립
 * 적재(커밋)를 오염시키지 않는다. (private 메서드는 프록시 미적용이므로 별도 빈으로 분리한다.)
 *
 * <p>적재 매핑(관제 실제 스키마 MNG_CLIP_MASTER → LS_DATA_RAW):
 * <ul>
 *   <li>vmsClipId ← {@code CLIP_ID}(UUID) — LS_DATA_RAW.VMS_CLIP_ID(UK) 멱등키</li>
 *   <li>vmsCctvId ← {@code VMS_CCTV_ID}</li>
 *   <li>lclgvCd   ← {@code LCLGV_CD}</li>
 *   <li>rawFilePathNm ← {@code FILE_PATH}(NAS 절대경로)</li>
 *   <li>shtDt     ← {@code MNG_CLIP_EVNT_LST.SHT_DT}(이벤트리스트 촬영 일자), 미매칭 시 {@code CRT_DT} 폴백</li>
 *   <li>durationSec ← {@code VDO_LEN_SEC}(관제 실측 단위 ms)를 초로 반올림. null 이면 null 유지,
 *       1초 미만(절삭·단위 이질)이면 0 을 영속하지 않고 null 로 두어 적재 직후 ffprobe back-fill
 *       ({@code VideoMetaService.upsertVideoMeta})이 실제 파일 길이로 채우게 위임한다.</li>
 *   <li>evntTypeCd ← {@code MNG_CLIP_EVNT_LST.EVNT_TYPE_CD}(EVNT_ID 조인), 미매칭 시 null</li>
 *   <li>prvcTypeCd ← {@code MNG_CLIP_EVNT_LST.PRVC_TYPE_CD}(허용값이면 채택), 미제공·미매칭 시 PRVC</li>
 *   <li>dayNgtCd/sesnCd ← {@code MNG_CLIP_EVNT_LST.HR_TYPE_CD/SESN_CD}
 *       (허용 어휘 매칭 시에만 채택), 미제공·미매칭 시 null(미상)</li>
 *   <li>wthrNm — <b>관제에서 받지 않는다</b>. 적재 시 항상 null 이며 저작도구 수동 입력
 *       ({@code EnvironmentMetaService})만이 채운다(2026-07-31 사용자 확정)</li>
 * </ul>
 *
 * <p><b>촬영환경·개인정보유형 해석</b>은 {@link ControlClipMetaResolver} 한 곳에만 둔다 — 관제 코드값↔
 * 저작도구 코드도메인 대응표가 미확정이라, 허용 어휘와 일치하는 값만 채택하고 미매칭은 채택하지 않고
 * WARN 으로 드러낸다(미검증 문자열이 동결·export 로 새는 것을 막는다). 관제가 값을 주지 않는 현행
 * 데이터에서는 촬영환경이 null(미상)로 적재되고, 개인정보유형은 <b>{@code PRVC}</b>(fail-closed —
 * "입력이 없으면 개인정보가 있고 익명처리되지 않은 원천영상으로 본다")로 적재된다.
 *
 * <p>이벤트 메타 도출: 관제 마스터에 EVNT_TYPE_CD/촬영 일자 직접 컬럼이 없어 {@code EVNT_ID} 로
 * 이벤트리스트를 조인 조회한다(실측 EVNT_ID 당 1행). 미매칭(null)이어도 evntTypeCd=null +
 * shtDt=CRT_DT 폴백으로 적재를 진행한다 — 이벤트리스트 미매칭이 적재를 막지 않는다.
 *
 * <p><b>B-ISSUE-04</b>: 이벤트리스트 조회는 클립당 개별 조회에서 <b>스캔 단계의 IN 조회 1회</b>
 * ({@code TrainingVideoIngestService.loadEventListsFor})로 옮겼다. 본 빈은 조회 결과를 파라미터로
 * 받기만 한다 — 매 tick N 회의 point lookup 이 관제 공유 DB 에 발생하던 것을 제거한다.
 *
 * <p>멱등성/안전 처리:
 * <ol>
 *   <li><b>이중 멱등</b> — 적재 전 {@code findByVmsClipId} 조회 skip + UK 위반
 *       ({@link DataIntegrityViolationException}) catch-skip 으로 동시 race 중복 적재를 흡수한다.</li>
 *   <li><b>식별자 가드</b> — {@code CLIP_ID}(vmsClipId) 가 null/blank 면 적재하지 않고 skip(WARN).</li>
 *   <li><b>CCTV 식별자 가드</b> — {@code VMS_CCTV_ID} 가 null/blank 면 skip(WARN). 관제는 nullable
 *       이나 LS_DATA_RAW.VMS_CCTV_ID 는 NOT NULL 이라, 가드 없이 save 시
 *       {@link DataIntegrityViolationException} 이 중복 race catch 로 흡수되어 원인 식별이 불가하다.</li>
 *   <li><b>파일경로 가드</b> — {@code FILE_PATH} 가 null/blank 인 클립은 깨진 적재 방지를 위해 skip(WARN).
 *       정상 경로면 적재 후 {@link VideoIngestedEvent} 를 발행해 비식별 선두 파이프라인을 트리거한다.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainingVideoIngestTx {

    /** 관제 VDO_LEN_SEC 실측 단위가 ms 라 초 단위(LS_DATA_RAW.VDO_LEN_SEC)로 변환할 제수. */
    private static final int MILLIS_PER_SECOND = 1000;

    private final VideoRepository videoRepository;
    private final ApplicationEventPublisher eventPublisher;
    /** 관제 이벤트리스트 코드값(촬영환경·개인정보유형) 해석의 단일 지점 — 순수 함수(DB 접근 없음). */
    private final ControlClipMetaResolver metaResolver;

    /**
     * 단일 클립을 독립(REQUIRES_NEW) 트랜잭션으로 적재한다.
     *
     * @param clip    적재 대상 관제 클립
     * @param evntLst 스캔 단계가 IN 조회 1회로 확보한 이벤트리스트 행 (미매칭이면 {@code null} — 폴백 적재)
     * @return 신규 적재 성공 시 true, 중복/스킵 시 false
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, transactionManager = "controlTransactionManager")
    public boolean ingestOne(MngClipMaster clip, MngClipEvntLst evntLst) {
        String vmsClipId = clip.getClipId();
        // 식별자 가드 — CLIP_ID 가 null/blank 면 findByVmsClipId(null) 오작동을 피해 skip.
        if (!StringUtils.hasText(vmsClipId)) {
            log.warn("[TrainingIngest] skip clip with blank clipId evntId={}", clip.getEvntId());
            return false;
        }
        // CCTV 식별자 가드 — VMS_CCTV_ID(관제 nullable) 가 없으면 LS_DATA_RAW.VMS_CCTV_ID(NOT NULL)
        // 위반이 중복 race catch 로 흡수되어 원인 식별이 불가하다. 사전 skip 으로 구분 가능한 WARN 남긴다.
        if (!StringUtils.hasText(clip.getVmsCctvId())) {
            log.warn("[TrainingIngest] skip clip with blank vmsCctvId evntId={} clipId={}",
                    clip.getEvntId(), vmsClipId);
            return false;
        }
        // 파일경로 가드 — FILE_PATH 가 없으면 비식별이 열 파일이 없어 적재 자체를 skip(깨진 적재 방지).
        if (!StringUtils.hasText(clip.getFilePath())) {
            log.warn("[TrainingIngest] skip clip with blank filePath evntId={} clipId={}",
                    clip.getEvntId(), vmsClipId);
            return false;
        }
        // 이중 멱등(1차): 동일 CLIP_ID 가 이미 적재되어 있으면 skip.
        if (videoRepository.findByVmsClipId(vmsClipId).isPresent()) {
            log.debug("[TrainingIngest] clip already ingested — skip clipId={}", vmsClipId);
            return false;
        }
        // 이벤트 메타 도출: 스캔 단계 IN 조회 1회의 결과를 그대로 사용한다(미매칭이면 폴백).
        String evntTypeCd = (evntLst != null) ? evntLst.getEvntTypeCd() : null;
        // shtDt: 이벤트리스트 SHT_DT(실제 촬영 일자) 우선, 미매칭/null 이면 CRT_DT 근사 폴백.
        java.time.LocalDateTime shtDt = (evntLst != null && evntLst.getShtDt() != null)
                ? evntLst.getShtDt() : clip.getCrtDt();
        // durationSec: 관제 VDO_LEN_SEC 실측 단위가 ms → 초 반올림(null/1초 미만이면 null, ffprobe back-fill 위임).
        Integer durationSec = toDurationSec(clip.getVdoLenSec());
        // 촬영환경(시간대·계절)·개인정보유형: 관제 코드값을 해석기 한 곳에서 판정
        // (채택 / 미매칭 폴백+WARN / 미제공 폴백). 관제값이 없으면 시간대·계절은 미상(null),
        // 개인정보유형은 PRVC(fail-closed) 로 적재된다.
        ControlClipMetaResolver.ShootingEnv env = metaResolver.resolve(evntLst);
        String prvcTypeCd = metaResolver.resolvePrvcType(evntLst);
        try {
            LsDataRaw raw = LsDataRaw.createFromIngest(
                    vmsClipId, clip.getVmsCctvId(),
                    evntTypeCd, clip.getLclgvCd(), prvcTypeCd,
                    clip.getFilePath(), shtDt, durationSec,
                    // 날씨(WTHR_NM)는 인자에 없다 — 관제에서 받지 않아 항상 null 이던 죽은 인자를
                    // 팩토리에서 제거했다(2026-07-31). EnvironmentMetaService(작업자 수동 입력)만이 채운다.
                    env.dayNgtCd(), env.sesnCd());
            LsDataRaw saved = videoRepository.save(raw);
            // 가드 제거: 정상 FILE_PATH 면 비식별 선두 트리거 이벤트 발행.
            eventPublisher.publishEvent(new VideoIngestedEvent(saved.getRawSn()));
            log.info("[TrainingIngest] ingested clipId={} rawSn={}", vmsClipId, saved.getRawSn());
            return true;
        } catch (DataIntegrityViolationException e) {
            // 이중 멱등(2차): UK(VMS_CLIP_ID) 위반은 동시 race 의 중복 적재 — 정상 skip 처리.
            log.debug("[TrainingIngest] duplicate ingest race — skip clipId={}", vmsClipId);
            return false;
        }
    }

    /**
     * 관제 {@code VDO_LEN_SEC}(ms 전제) → {@code LS_DATA_RAW.VDO_LEN_SEC}(초) 변환.
     *
     * <p>ms 를 초로 반올림하되, 결과가 <b>1초 미만</b>(정수 절삭·초 단위 이질 등)이면 {@code 0} 을
     * 영속하지 않고 {@code null} 로 둔다 — 적재 직후 이미 수행되는 ffprobe back-fill
     * ({@code VideoMetaService.upsertVideoMeta})이 실제 파일 길이로 채우게 위임한다. {@code 0} 을 저장하면
     * "길이 0" 오값으로 굳어져(통계·표시 오염) back-fill 가드(NULL/≤0)만으로는 원인 추적이 어려워지므로,
     * 불명확한 값은 null 로 두는 편이 보수적이다. 관제가 준 유효한 초값(≥1)은 그대로 신뢰·유지한다.
     *
     * @param vdoLenMs 관제 {@code VDO_LEN_SEC}(실측 단위 ms). null 이면 null.
     * @return 초 단위 길이(≥1) 또는 null(미상/1초 미만)
     */
    private static Integer toDurationSec(Integer vdoLenMs) {
        if (vdoLenMs == null) {
            return null;
        }
        int sec = Math.round(vdoLenMs / (float) MILLIS_PER_SECOND);
        return sec >= 1 ? sec : null;
    }
}
