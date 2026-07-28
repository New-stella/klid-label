package kr.co.cudo.authoring.marking.service;

import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마킹 생성의 <b>프로브 이전 사전 인가·프리컨디션 확인</b> 협력자 (HIGH — CWE-862/400 회귀 봉인).
 *
 * <p>{@link MarkingService} 의 <b>비트랜잭션 오케스트레이션</b>이 고비용 ffprobe(최대 수십 초 서브프로세스)를
 * 트리거하기 <b>전에</b> 이 값싼 readonly read 로 인가·프리컨디션을 먼저 확인한다. 위반 시 프로브 이전에
 * 즉시 거부하여, 미배정 WORKER 가 유효 역할 토큰만으로 403 이전에 임의 rawSn 프로브를 무제한 트리거하는
 * 리소스 소모 공격 표면(OWASP API4)을 제거한다.
 *
 * <p><b>커넥션 격리:</b> 이 read 는 짧은 {@code REQUIRES_NEW}(readOnly) 트랜잭션으로 수행되어 리턴 즉시
 * 커넥션을 풀에 반납한다. 따라서 이후 프로브 시점에는 이 read 의 커넥션을 보유하지 않는다(HikariCP 풀
 * 고갈 방지). 별도 빈으로 둔 이유: 자기호출(self-invocation)은 트랜잭션 프록시를 우회하므로 오케스트레이션이
 * <b>다른 빈</b>인 이 메서드를 호출해야 {@code REQUIRES_NEW} 전파가 적용된다.
 *
 * <p>가드 로직 자체는 {@link MarkingGuards} 로 단일화하여 persist 트랜잭션 내부의 방어적 재확인과
 * <b>동일 규칙·순서·상태코드</b>를 보장한다.
 */
@Component
@RequiredArgsConstructor
public class MarkingPrecheckReader {

    private final VideoRepository videoRepository;
    private final LsTaskAssignmentRepository assignmentRepository;
    private final LsMarkingRepository markingRepository;

    /**
     * 프로브 이전 사전 인가·프리컨디션 확인. 위반 시 {@link MarkingGuards} 규칙대로 즉시 예외를 던진다
     * (평가 순서: 인가 → 존재 → 비식별 완료 → MARKING_READY → 이벤트 유형 → 활성 마킹 중복).
     *
     * @param rawSn 영상 PK
     * @param actor 인증된 사용자
     */
    @Transactional(value = "controlTransactionManager", readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public void precheck(Long rawSn, TokenClaims actor) {
        MarkingGuards.requireAssignedOrReviewer(rawSn, actor, assignmentRepository);
        LsDataRaw raw = videoRepository.findById(rawSn).orElse(null);
        MarkingGuards.requirePreconditions(raw);
        MarkingGuards.requireNoActiveMarking(rawSn, markingRepository);
    }
}
