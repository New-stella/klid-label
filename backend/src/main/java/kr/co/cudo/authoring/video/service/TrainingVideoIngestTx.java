package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.video.entity.LsDataRaw;
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
 * <p>멱등성/안전 처리:
 * <ol>
 *   <li><b>이중 멱등</b> — 적재 전 {@code findByVmsClipId} 조회 skip + UK 위반
 *       ({@link DataIntegrityViolationException}) catch-skip 으로 동시 race 중복 적재를 흡수한다.</li>
 *   <li><b>식별자 가드</b> — {@code vmsClipId} 가 null/blank 면 적재하지 않고 skip(WARN, 식별자만 출력).</li>
 *   <li><b>파일경로 미해결 가드(잠정)</b> — MNG_CLIP_MASTER 에 파일경로 컬럼이 없어 플레이스홀더
 *       ({@link #PENDING_FILE_PATH})로 적재되는 동안에는 {@link VideoIngestedEvent} 를 발행하지 않는다.
 *       존재하지 않는 파일을 비식별 단계가 열다 실패→재시도 폭주하는 것을 차단하기 위한 잠정 조치다
 *       (관제 클립→파일경로 매핑 확정 시 제거 예정). 경로가 정상일 때만 이벤트를 발행해 비식별을 트리거한다.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrainingVideoIngestTx {

    /**
     * MNG_CLIP_MASTER 에 파일 경로 컬럼이 없어 도출 불가한 항목의 기본값. 비식별 유형은 전체 비식별
     * 정책상 ANONY 로 적재한다(파이프라인이 무조건 비식별 수행). 실제 파일 경로/메타는 후속 보완 대상.
     */
    private static final String DEFAULT_PRVC_TYPE = LsDataRaw.PRVC_TYPE_ANONY;

    /** 파일경로 미해결 플레이스홀더(매직값 금지 — 명명 상수). 이 값이면 비식별 트리거를 보류한다. */
    static final String PENDING_FILE_PATH = "PENDING";

    private final VideoRepository videoRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 단일 클립을 독립(REQUIRES_NEW) 트랜잭션으로 적재한다.
     *
     * @return 신규 적재 성공 시 true, 중복/스킵 시 false
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, transactionManager = "controlTransactionManager")
    public boolean ingestOne(MngClipMaster clip) {
        String vmsClipId = clip.getVmsClipId();
        // MEDIUM-5: 식별자 가드 — null/blank 면 findByVmsClipId(null) 오작동을 피해 skip.
        if (!StringUtils.hasText(vmsClipId)) {
            log.warn("[TrainingIngest] skip clip with blank vmsClipId clipSn={}", clip.getClipSn());
            return false;
        }
        // HIGH-1(1차): 멱등성 — 동일 VMS_CLIP_ID 가 이미 적재되어 있으면 skip.
        if (videoRepository.findByVmsClipId(vmsClipId).isPresent()) {
            log.debug("[TrainingIngest] clip already ingested — skip clipSn={}", clip.getClipSn());
            return false;
        }
        try {
            LsDataRaw raw = LsDataRaw.createFromIngest(
                    vmsClipId, clip.getVmsCctvId(),
                    null, null, DEFAULT_PRVC_TYPE,
                    PENDING_FILE_PATH, clip.getRegDt(), null);
            LsDataRaw saved = videoRepository.save(raw);
            publishWhenFilePathResolved(clip, saved);
            log.info("[TrainingIngest] ingested clipSn={} rawSn={}", clip.getClipSn(), saved.getRawSn());
            return true;
        } catch (DataIntegrityViolationException e) {
            // HIGH-1(2차): UK(VMS_CLIP_ID) 위반은 동시 race 의 중복 적재 — 정상 skip 처리.
            log.debug("[TrainingIngest] duplicate ingest race — skip clipSn={}", clip.getClipSn());
            return false;
        }
    }

    /**
     * 파일경로가 정상(플레이스홀더 아님)일 때만 {@link VideoIngestedEvent} 를 발행해 비식별을 트리거한다.
     * 미해결(PENDING) 이면 발행을 보류하고 WARN 1회 기록(식별자만 — 경로 본문 미출력).
     */
    private void publishWhenFilePathResolved(MngClipMaster clip, LsDataRaw saved) {
        if (PENDING_FILE_PATH.equals(saved.getRawFilePathNm())) {
            // HIGH-3: 잠정 가드 — 파일경로 미해결 시 비식별 자동 트리거 보류(재시도 폭주 차단).
            log.warn("[TrainingIngest] file path unresolved — deidentify deferred clipSn={} rawSn={}",
                    clip.getClipSn(), saved.getRawSn());
            return;
        }
        eventPublisher.publishEvent(new VideoIngestedEvent(saved.getRawSn()));
    }
}
