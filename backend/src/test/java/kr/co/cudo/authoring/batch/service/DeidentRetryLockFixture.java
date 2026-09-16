package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.auth.entity.LsAuthWorkLock;
import kr.co.cudo.authoring.auth.repository.LsAuthWorkLockRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 작업 잠금을 메모리에 들고 있는 시험 도구 — 실제 {@link WorkLockService} 가 이 저장소를 쓴다.
 *
 * <p>호출 여부가 아니라 <b>잠금 상태 그 자체</b>(풀렸는가 · 다른 기능 잠금이 남았는가 · 재요청이 다시
 * 수락되는가)를 단언하려고 만든다. 영상 단위 활성 잠금 유일 인덱스도 흉내 낸다(두 번째 활성 INSERT 거부).
 */
public final class DeidentRetryLockFixture {

    public final List<LsAuthWorkLock> locks = new ArrayList<>();
    public final LsAuthWorkLockRepository repository = mock(LsAuthWorkLockRepository.class);
    public final WorkLockService workLockService;

    @SuppressWarnings("unchecked")
    public DeidentRetryLockFixture() {
        when(repository.findAllByLockTargetCdAndDataRawSnAndLockSttsCd(
                eq(LsAuthWorkLock.TARGET_RAW), anyLong(), eq(LsAuthWorkLock.STATUS_LOCKED)))
                .thenAnswer(inv -> activeOf(inv.getArgument(1)));
        when(repository.existsByLockTargetCdAndDataRawSnAndLockSttsCd(
                eq(LsAuthWorkLock.TARGET_RAW), anyLong(), eq(LsAuthWorkLock.STATUS_LOCKED)))
                .thenAnswer(inv -> !activeOf(inv.getArgument(1)).isEmpty());
        when(repository.saveAndFlush(any(LsAuthWorkLock.class))).thenAnswer(inv -> {
            LsAuthWorkLock lock = inv.getArgument(0);
            if (!activeOf(lock.getDataRawSn()).isEmpty()) {
                throw new DataIntegrityViolationException("ux_ls_auth_work_lock_raw_active");
            }
            locks.add(lock);
            return lock;
        });
        workLockService = new WorkLockService(repository, mock(ObjectProvider.class));
    }

    public List<LsAuthWorkLock> activeOf(Long rawSn) {
        return locks.stream()
                .filter(l -> Objects.equals(l.getDataRawSn(), rawSn))
                .filter(l -> LsAuthWorkLock.STATUS_LOCKED.equals(l.getLockSttsCd()))
                .toList();
    }

    public long activeRetryLocks(Long rawSn) {
        return activeOf(rawSn).stream().filter(LsAuthWorkLock::isDeidentRetryLock).count();
    }

    public LsAuthWorkLock seedMergeLock(Long rawSn) {
        LsAuthWorkLock lock = LsAuthWorkLock.lockRaw(rawSn, "1", LsAuthWorkLock.REASON_MERGE);
        locks.add(lock);
        return lock;
    }

    /**
     * 실제 판정 서비스 — 승인 이력·열린 신고·진행 중 위탁은 「없음」으로 둔다(잠금 축만 본다).
     * 영상 형상은 넘겨받은 영상 저장소가 정한다.
     */
    public LeadDeidentRetryService claimService(VideoRepository videoRepository) {
        return new LeadDeidentRetryService(videoRepository, mock(ReviewApprovalGate.class),
                mock(LsDeidentReportRepository.class), mock(LsDeidentProcLogRepository.class), workLockService);
    }
}
