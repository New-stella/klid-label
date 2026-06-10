package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.entity.MngClipEvntLst;
import kr.co.cudo.authoring.video.entity.MngClipMaster;
import kr.co.cudo.authoring.video.event.VideoIngestedEvent;
import kr.co.cudo.authoring.video.repository.MngClipEvntLstRepository;
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
 *   <li>durationSec ← {@code VDO_LEN_SEC / 1000}(관제 실측 단위 ms → 초 변환, null 이면 null 유지)</li>
 *   <li>evntTypeCd ← {@code MNG_CLIP_EVNT_LST.EVNT_TYPE_CD}(EVNT_ID 조인), 미매칭 시 null</li>
 *   <li>prvcTypeCd ← ANONY(전체 비식별 정책)</li>
 * </ul>
 *
 * <p>이벤트 메타 도출: 관제 마스터에 EVNT_TYPE_CD/촬영 일자 직접 컬럼이 없어 {@code EVNT_ID} 로
 * {@link MngClipEvntLstRepository} 를 조인 조회한다(실측 EVNT_ID 당 1행). 미매칭(조회 empty)이어도
 * evntTypeCd=null + shtDt=CRT_DT 폴백으로 적재를 진행한다 — 이벤트리스트 미매칭이 적재를 막지 않는다.
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

    /**
     * 비식별 유형 기본값. 전체 비식별 정책상 ANONY 로 적재한다(파이프라인이 무조건 비식별 수행).
     */
    private static final String DEFAULT_PRVC_TYPE = LsDataRaw.PRVC_TYPE_ANONY;

    /** 관제 VDO_LEN_SEC 실측 단위가 ms 라 초 단위(LS_DATA_RAW.VDO_LEN_SEC)로 변환할 제수. */
    private static final int MILLIS_PER_SECOND = 1000;

    private final VideoRepository videoRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final MngClipEvntLstRepository clipEvntLstRepository;

    /**
     * 단일 클립을 독립(REQUIRES_NEW) 트랜잭션으로 적재한다.
     *
     * @return 신규 적재 성공 시 true, 중복/스킵 시 false
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, transactionManager = "controlTransactionManager")
    public boolean ingestOne(MngClipMaster clip) {
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
        // 이벤트 메타 도출: EVNT_ID 로 이벤트리스트 1회 조회(미매칭이면 폴백). 클립당 1회만 조회한다.
        MngClipEvntLst evntLst = clipEvntLstRepository.findFirstByEvntId(clip.getEvntId()).orElse(null);
        String evntTypeCd = (evntLst != null) ? evntLst.getEvntTypeCd() : null;
        // shtDt: 이벤트리스트 SHT_DT(실제 촬영 일자) 우선, 미매칭/null 이면 CRT_DT 근사 폴백.
        java.time.LocalDateTime shtDt = (evntLst != null && evntLst.getShtDt() != null)
                ? evntLst.getShtDt() : clip.getCrtDt();
        // durationSec: 관제 VDO_LEN_SEC 실측 단위가 ms → 초 변환(null 이면 null 유지).
        Integer durationSec = (clip.getVdoLenSec() != null)
                ? clip.getVdoLenSec() / MILLIS_PER_SECOND : null;
        try {
            LsDataRaw raw = LsDataRaw.createFromIngest(
                    vmsClipId, clip.getVmsCctvId(),
                    evntTypeCd, clip.getLclgvCd(), DEFAULT_PRVC_TYPE,
                    clip.getFilePath(), shtDt, durationSec);
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
}
