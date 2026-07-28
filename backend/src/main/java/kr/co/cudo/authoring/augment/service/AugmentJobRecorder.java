package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.entity.LsDataAugJobFile;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobFileRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 증강 위탁 job 행({@code LS_DATA_AUG_JOB}) 기록 전용 서비스 — Phase 7-A1.
 *
 * <p>모든 메서드가 {@link Propagation#REQUIRES_NEW} 다. 위탁은 AFTER_COMMIT 리스너에서
 * 수행되고 청크마다 외부 HTTP 왕복이 끼므로, 기록을 하나의 긴 트랜잭션에 묶으면
 * (1) 커넥션을 외부 응답 대기 동안 점유하고 (2) 뒤 청크의 실패가 앞 청크의 기록을 롤백시킨다.
 * <b>건별 격리</b>가 이 클래스의 존재 이유다.
 *
 * <p>별도 빈으로 분리한 이유: 같은 빈 안에서 호출하면 self-invocation 으로 프록시를 타지 않아
 * {@code REQUIRES_NEW} 가 조용히 무시된다(프로젝트 선행 사고 이력).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AugmentJobRecorder {

    private final LsDataAugJobRepository jobRepository;
    private final LsDataAugJobFileRepository jobFileRepository;

    /**
     * 위탁 직전 선기록 — 멱등키와 <b>순서↔프레임 대응</b>을 한 트랜잭션에 확보한다.
     *
     * <p>대응({@code LS_DATA_AUG_JOB_FILE})을 job 행과 <b>같은 트랜잭션</b>에 남기는 것이 핵심이다.
     * 나눠 쓰면 job 만 남고 대응이 없는 상태로 콜백이 도착해 결과를 되붙일 대상을 잃는다
     * (그때 프레임 정렬로 재계산하면 위탁 후 프레임 변동에 조용히 어긋난다).
     *
     * @param files 이 job 이 실어 보낼 입력 항목의 순서↔프레임 대응(위탁 건수 = {@code files.size()})
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Long recordIssued(Long dataAugSn, int jobSeq, String idempotencyKey,
                             List<AugmentJobFileRef> files) {
        LsDataAugJob job = jobRepository.save(
                LsDataAugJob.createIssued(dataAugSn, jobSeq, idempotencyKey, files.size()));
        jobFileRepository.saveAll(files.stream()
                .map(f -> LsDataAugJobFile.issued(job.getAugJobSn(), f.fileSeq(), f.srcSn()))
                .toList());
        return job.getAugJobSn();
    }

    /** 외부 202 수락 — 외부가 발급한 job_id 를 적재한다. */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markAccepted(Long augJobSn, String externalJobId) {
        jobRepository.findById(augJobSn).ifPresent(job -> job.markAccepted(externalJobId));
    }

    /** 위탁 실패 — 사유와 함께 남긴다(조용한 유실 금지). */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long augJobSn, String errorCode, String errorMessage) {
        jobRepository.findById(augJobSn).ifPresent(job -> job.markFailed(errorCode, errorMessage));
    }

    /** 위탁하지 않고 거부 사실만 남긴다(비식별 경로 부재 등 fail-closed). */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void recordRejected(Long dataAugSn, String idempotencyKey,
                               String errorCode, String errorMessage) {
        jobRepository.save(LsDataAugJob.createRejected(
                dataAugSn, 1, idempotencyKey, errorCode, errorMessage));
    }
}
