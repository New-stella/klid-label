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

import java.time.LocalDateTime;
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

    /** {@code LS_DATA_AUG_JOB.ERR_MSG_CN} 컬럼 길이(내용V1000) — 엔티티와 같은 값. */
    private static final int ERR_MSG_MAX = 1000;

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

    /**
     * <b>비동기 제출 ACK 기록</b> — 조건부 원자 UPDATE (Phase C-3).
     *
     * <p>{@link #markAccepted} 와 달리 엔티티를 읽어 무조건 덮어쓰지 않는다. 논블로킹 제출에서는
     * 벤더 콜백이 ACK 보다 먼저 도착할 수 있어(저지연 벤더·목), 무조건 덮으면 이미 SUCCEEDED 된 job 을
     * RECEIVED 로 <b>강등</b>시킨다. 판정 술어는 리포지토리 주석 참조.
     *
     * @return true = 이번 호출이 기록함 / false = 이미 콜백·다른 노드가 선점(기록하지 않음)
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean markSubmitAccepted(Long augJobSn, String externalJobId) {
        return jobRepository.claimSubmitAck(augJobSn, externalJobId, LocalDateTime.now()) == 1;
    }

    /**
     * <b>비동기 제출 실패 기록</b> — 조건부 원자 UPDATE (Phase C-3).
     *
     * @return true = 이번 호출이 실패로 종결함 / false = 그 사이 콜백이 선점(강등하지 않음)
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean markSubmitFailed(Long augJobSn, String errorCode, String errorMessage) {
        return jobRepository.claimSubmitFailure(
                augJobSn, errorCode, truncateErrorMessage(errorMessage), LocalDateTime.now()) == 1;
    }

    /**
     * {@code ERR_MSG_CN}(내용V1000) 길이 보호 — 네이티브 UPDATE 는 엔티티 setter 의 절단을 타지 않으므로
     * 여기서 자른다(초과 시 DB 예외로 기록 자체가 유실되는 것을 막는다).
     */
    private static String truncateErrorMessage(String value) {
        if (value == null || value.length() <= ERR_MSG_MAX) {
            return value;
        }
        return value.substring(0, ERR_MSG_MAX);
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
