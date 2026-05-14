package kr.co.cudo.authoring.auth.service;

import kr.co.cudo.authoring.auth.entity.LsAuthWorkLock;
import kr.co.cudo.authoring.auth.repository.LsAuthWorkLockRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager")
public class WorkLockService {

    private final LsAuthWorkLockRepository repository;

    public void lockRawForRedeident(Long rawSn, String ownerId) {
        if (isRawLocked(rawSn)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 비식별 재처리 중인 영상입니다.");
        }
        repository.save(LsAuthWorkLock.lockRawForRedeident(rawSn, ownerId));
    }

    @Transactional(value = "controlTransactionManager", readOnly = true)
    public boolean isRawLocked(Long rawSn) {
        if (rawSn == null) {
            return false;
        }
        return repository.existsByLockTargetCdAndDataRawSnAndLockSttsCd(
                LsAuthWorkLock.TARGET_RAW, rawSn, LsAuthWorkLock.STATUS_LOCKED);
    }

    public int releaseRaw(Long rawSn, String actorId, String reason) {
        var locks = repository.findAllByLockTargetCdAndDataRawSnAndLockSttsCd(
                LsAuthWorkLock.TARGET_RAW, rawSn, LsAuthWorkLock.STATUS_LOCKED);
        for (LsAuthWorkLock lock : locks) {
            lock.release(actorId, reason);
        }
        return locks.size();
    }
}
