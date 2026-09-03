package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.transfer.config.MarkingImportProperties;
import kr.co.cudo.authoring.transfer.entity.LsEblcUldJob;
import kr.co.cudo.authoring.transfer.repository.LsEblcUldJobArtclRepository;
import kr.co.cudo.authoring.transfer.repository.LsEblcUldJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 멈춘 일괄 적재를 다시 굴리는 <b>실제 동작</b> — 주기 잡은 이 빈을 부르기만 한다(AC-1033).
 *
 * <h3>★ 왜 잡과 나누는가 (같은 실수가 이 저장소에 이미 있었다)</h3>
 * <p>{@code @Scheduled} 가 붙은 잡 안에서 자기 자신의 {@code @Transactional} 메서드를 부르면
 * <b>프록시를 우회해 트랜잭션이 적용되지 않는다</b>. 그러면 되돌리기·마감 UPDATE 가 트랜잭션 없이
 * 실행돼 실패하거나(읽기 전용 경계) 조용히 반영되지 않는다. 이 저장소가 같은 이유로 스윕 잡과
 * 트랜잭션 빈을 나눠 둔 선례가 있어 그대로 따른다.
 *
 * <p>부수 효과로 <b>시험이 잡 토글에 기대지 않는다</b> — 잡 빈은 토글이 꺼진 형상에서 아예 등록되지
 * 않지만, 이 빈은 언제나 있으므로 복구 논리를 직접 불러 검증할 수 있다.
 *
 * @design DOMAIN-017
 * @design API-217
 * @design AC-1033
 * @design SEQ-030
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarkingImportRecoveryTxService {

    /** 한 번에 다시 굴릴 작업 수 상한 — 한 순번이 원장을 통째로 훑지 않게 한다. */
    private static final int JOB_SCAN_LIMIT = 50;

    /** 다시 시도할 여지를 다 쓴 항목의 마감 사유 — 내부 구조를 드러내지 않는다(CWE-209). */
    static final String EXHAUSTED_REASON = "다시 시도할 수 있는 횟수를 넘겨 적재하지 못한 채 마감했다.";

    private final LsEblcUldJobRepository jobRepository;
    private final LsEblcUldJobArtclRepository artclRepository;
    private final MarkingImportWorkerDispatcher dispatcher;
    private final MarkingImportProperties properties;

    /**
     * 처리 중인 채로 오래 멈춘 항목을 다시 집을 수 있게 되돌린다.
     *
     * <p>노드가 처리 도중 다시 뜨면 그 항목은 아무도 손대지 않는 채 처리중으로 남는다. 되돌리지 않으면
     * 그 작업은 <b>영원히 끝나지 않는다</b>. 다시 집힌 횟수를 함께 올려 영원히 맴돌지 않게 한다.
     *
     * <p>⚠ 임계를 짧게 잡으면 <b>지금 돌고 있는 처리</b>를 빼앗아 같은 영상을 두 건이 동시에 적재하려
     * 든다. 마지막 방어선은 영상 식별자의 유일 제약이라 두 번 적재되지는 않지만, 정상 항목 하나가
     * 「이미 있음」으로 건너뛰어져 사람이 원인을 알기 어렵다. 그래서 설정에 하한을 둔다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int reclaimStale() {
        LocalDateTime now = LocalDateTime.now();
        return artclRepository.reclaimStale(staleThreshold(now), properties.effectiveMaxRetry(), now);
    }

    /**
     * 다시 집을 횟수를 다 쓴 항목을 실패로 마감한다.
     *
     * <p>되돌리기만 있고 이 마감이 없으면 같은 항목이 <b>처리중과 대기 사이를 영원히 오간다</b>.
     *
     * <p>이 조건과 되돌리기 조건은 재시도 횟수로 <b>갈라져 서로 겹치지 않는다</b>. 겹치면 어느 쪽이
     * 먼저 도느냐에 따라 결과가 달라져 같은 형상에서 실행마다 다른 답이 나온다.
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int failExhausted() {
        LocalDateTime now = LocalDateTime.now();
        return artclRepository.failExhausted(
                staleThreshold(now), properties.effectiveMaxRetry(), EXHAUSTED_REASON, now);
    }

    /**
     * 진행중인 작업에 일꾼을 다시 띄운다.
     *
     * <p>노드가 다시 뜨면 작업 행은 남지만 일꾼은 사라진다. 띄우지 않으면 대기 항목이 그대로 남는다.
     * 접수 시 자리가 꽉 차 일꾼을 못 띄운 경우도 여기서 회수된다.
     *
     * <p>이미 일꾼이 붙어 있는 작업에 더 띄워도 해롭지 않다 — 항목 소유권은 원장이 주므로 남는 일꾼은
     * 집을 것이 없어 곧바로 물러난다. 반대로 안 띄우면 <b>아무도 처리하지 않는 작업</b>이 남는다.
     *
     * <p>일꾼을 띄우는 것은 트랜잭션 밖의 일이라 조회만 트랜잭션으로 감싼다 — 띄우기까지 감싸면
     * 일꾼이 <b>아직 커밋되지 않은 상태</b>를 보고 출발한다.
     *
     * @return 일꾼을 띄운 작업 수
     */
    public int resumeRunningJobs() {
        int resumed = 0;
        for (Long jobSn : runningJobSns()) {
            if (dispatcher.dispatch(jobSn, properties.effectiveConcurrency()) > 0) {
                resumed++;
            }
        }
        return resumed;
    }

    /**
     * 아직 진행중인 작업 식별번호 — 오래된 것부터.
     *
     * <p>새 작업이 계속 들어오는 동안 먼저 멈춘 작업이 뒤로 밀려 굶지 않게 한다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<Long> runningJobSns() {
        return jobRepository.findRunning(PageRequest.of(0, JOB_SCAN_LIMIT)).stream()
                .map(LsEblcUldJob::getEblcUldJobSn)
                .toList();
    }

    private LocalDateTime staleThreshold(LocalDateTime now) {
        return now.minusMinutes(properties.effectiveStaleTimeoutMinutes());
    }
}
