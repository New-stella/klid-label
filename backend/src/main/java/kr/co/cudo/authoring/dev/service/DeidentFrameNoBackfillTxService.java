package kr.co.cudo.authoring.dev.service;

import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * [개발/검수 전용] {@code LS_DATA_SRC.VDO_FRM_NO}(영상 내 실제 프레임 위치) <b>복원 전용 트랜잭션</b>.
 *
 * <h3>왜 별도 빈인가 (Critical — 이걸 합치면 기능이 조용히 아무것도 안 한다)</h3>
 * <p>복원 직후 호출되는 {@code DeidentFrameAttacher#attachDeidentFrames} 는
 * {@code @Transactional(REQUIRES_NEW)} 라 <b>자기 트랜잭션에서 프레임 행을 다시 읽는다</b>.
 * 복원이 아직 커밋되지 않았으면 그 새 트랜잭션은 {@code VDO_FRM_NO} 를 여전히 NULL 로 보고,
 * attacher 는 값 없는 프레임을 <b>순번 폴백 없이 전부 skip</b> 한다(의도된 fail-closed).
 * 즉 <b>복원 → 커밋 → 재추출</b> 순서가 강제되어야 하므로, 복원은 자기 {@code REQUIRES_NEW}
 * 트랜잭션에서 끝나고 커밋된 뒤 재추출로 넘어간다.
 *
 * <p>같은 빈 안의 {@code @Transactional} 자기호출은 Spring AOP 프록시를 우회해 경계가 생기지
 * 않으므로({@code DeidentStageResumeService} 와 동일 사유) 오케스트레이터와 <b>다른 빈</b>이어야 한다.
 *
 * <h3>쓰기 방식</h3>
 * <p>엔티티 dirty checking 이 아니라 조건부 native UPDATE
 * ({@code LsDataSrcRepository#restoreVideoFrameNo})를 쓴다 — 전 컬럼 UPDATE 로 다른 경로가 바꾼
 * 컬럼을 낡은 스냅샷으로 되돌리는 lost update 를 피하고, {@code WHERE VDO_FRM_NO IS NULL} 로
 * 재실행이 멱등이 된다.
 *
 * <p>보안: 식별자·건수만 로그에 남긴다(경로·PII 미출력 — CWE-359).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeidentFrameNoBackfillTxService {

    private final LsDataSrcRepository srcRepository;

    /**
     * 복원 지시 1건 — 프레임 행 PK 와 그 행이 가리켜야 할 영상 내 프레임 위치.
     *
     * @param srcSn        프레임 행 PK
     * @param videoFrameNo 영상 내 실제 프레임 위치(0-base, 음수 불가)
     */
    public record FrameNoFix(long srcSn, long videoFrameNo) {
    }

    /**
     * 지시 목록을 커밋 경계 하나로 적용한다.
     *
     * @return 실제로 값이 채워진 행 수 (이미 값이 있던 행은 0행이라 세지 않는다 — 멱등)
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int restore(long rawSn, List<FrameNoFix> fixes) {
        if (fixes == null || fixes.isEmpty()) {
            return 0;
        }
        int restored = 0;
        for (FrameNoFix fix : fixes) {
            restored += srcRepository.restoreVideoFrameNo(fix.srcSn(), fix.videoFrameNo());
        }
        log.info("[DevRecovery] VDO_FRM_NO restored rawSn={} requested={} applied={}",
                rawSn, fixes.size(), restored);
        return restored;
    }
}
