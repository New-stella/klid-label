package kr.co.cudo.authoring.marking.service;

import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.event.MarkingCompletedEvent;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * <b>예약 마킹({@link LsMarking#STATUS_RESERVED})의 활성화·마감 전용 트랜잭션 서비스</b> (ADR-052).
 * [design: ADR-052] [design: SEQ-030]
 *
 * <h3>무엇을 하는가</h3>
 * <p>외부에서 이벤트 마킹까지 끝난 영상을 적재할 때, 그 시점에는 영상이 아직 비식별되지 않아 마킹을
 * 곧바로 활성화할 수 없다. 적재 경로는 외부가 준 시점 배열을 {@code RESERVED} 로 담아 두고, 비식별이
 * 끝나 영상이 마킹 가능 상태가 되면 이 서비스가 그 예약을 {@code PENDING} 으로 깨워 이후 처리를
 * 시작한다. 비식별이 끝내 실패하면 {@link #closeReservations(Long, String)} 로 예약을 마감한다.
 *
 * <h3>★ 호출 배선은 비식별 도메인이 갖는다 — 이 서비스는 창구만 제공한다</h3>
 * <p>호출자는 비식별 완료/실패를 확정한 쪽(D012)이다. 두 가지를 지켜야 한다.
 * <ol>
 *   <li><b>비식별 완료가 영속된 뒤에 부른다.</b> 이 서비스가 발행하는 {@link MarkingCompletedEvent} 는
 *       {@code MarkingBatchBridge} 가 {@code AFTER_COMMIT} 으로 받아 <b>그 시점의 영상 행</b>을 다시
 *       읽는다. 비식별 결과({@code DE_IDENT_YN='Y'} · 배치 단계 {@code MARKING_READY})가 아직 커밋되지
 *       않았으면 브리지가 자기 가드에 막혀 skip 으로 판정하고, 그 skip 은 방금 깨운 마킹을
 *       {@code SKIPPED} 로 종결시킨다(활성 마킹 고아 방지 규약). 즉 <b>순서를 뒤집으면 예약이 조용히
 *       사라진다</b>.</li>
 *   <li><b>예약이 없어도 정상이다.</b> 사람이 직접 마킹하는 통상 경로의 영상에는 예약이 없으므로
 *       {@link Optional#empty()} 가 반환되며, 이는 오류가 아니다.</li>
 * </ol>
 *
 * <h3>왜 별도 빈의 {@code REQUIRES_NEW} 인가</h3>
 * <p>{@code MarkingSkipTxService} 와 같은 이유다. 호출자가 비트랜잭션 컨텍스트(@Async 러너 ·
 * {@code AFTER_COMMIT} 리스너)일 수 있어 dirty checking 이 작동하지 않고, 같은 빈 내부의
 * {@code @Transactional} 자기호출은 프록시를 우회한다. 또 호출자의 트랜잭션에 얹히면 그쪽이 나중에
 * 롤백될 때 활성화까지 함께 되돌아가는데, 그때는 이미 {@code AFTER_COMMIT} 판정이 끝나 있을 수 없다.
 *
 * <h3>사전 조건 가드를 완화하지 않는다</h3>
 * <p>{@code MarkingGuards.requirePreconditions} 는 손대지 않는다(ADR-052 가 그 안을 명시적으로 기각했다
 * — 평가 순서까지 고정된 계약이라 한 곳을 열면 비식별되지 않은 영상에 마킹하는 길이 열린다).
 * 예약은 그 가드를 <b>우회하는 것이 아니라 가드가 보는 활성 축 밖에 있는 것</b>이며, 활성으로 올라오는
 * 시점은 정확히 「비식별이 끝난 뒤」라 가드가 지키려던 조건이 그대로 지켜진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarkingActivationTxService {

    private final LsMarkingRepository markingRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 영상의 <b>예약 마킹을 활성({@code PENDING})으로 깨우고 이후 처리를 시작</b>한다.
     *
     * <p>순서: ① 예약 조회(최신 먼저) → ② <b>조건부 UPDATE 로 원자 클레임</b> → ③ 잉여 예약 마감 →
     * ④ 클레임에 성공한 경우에만 {@link MarkingCompletedEvent} 발행.
     *
     * <h3>②가 조건부 UPDATE 여야 하는 이유 (CWE-362)</h3>
     * <p>2노드 Active-Active 라 두 노드가 같은 예약을 동시에 집을 수 있다. 조회 후 변경 방식이면 둘 다
     * 통과해 잔여 배치가 두 번 기동한다. DB 가 직렬화하는 단일 UPDATE 로 <b>영향 행수 1을 받은 쪽만</b>
     * 소유권을 갖게 하고, 그쪽에서만 이벤트를 발행한다(중복 기동 금지).
     *
     * <h3>③ 잉여 예약을 함께 마감하는 이유</h3>
     * <p>영상당 활성 마킹은 1건뿐이라, 예약이 2건 이상이면 나머지는 <b>영영 깨어날 수 없는 행</b>이 된다
     * (아무도 전이시키지 않는 고아 — B-ISSUE-41 이 배치 skip 경로에서 겪은 것과 같은 형태). 활성으로
     * 올린 1건 외의 예약은 {@code SKIPPED} 로 마감한다. 통상 경로에서는 예약이 1건이라 0건 마감이다.
     *
     * <p>발행하는 이벤트는 사람이 마킹을 완료했을 때와 <b>같은 것</b>이다 — 배치 트리거의 가드(비식별
     * 여부·배치 단계 역전·큐 클레임)를 그대로 통과시키기 위해 별도 경로를 만들지 않는다.
     *
     * @param rawSn 비식별이 끝나 마킹 가능 상태가 된 영상 PK (nullable — null 이면 no-op)
     * @return 활성화된 마킹 PK. 예약이 없거나 다른 노드가 먼저 집었으면 {@link Optional#empty()}
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Optional<Long> activateReserved(Long rawSn) {
        if (rawSn == null) {
            return Optional.empty();
        }

        List<LsMarking> reserved = markingRepository.findByRawSnAndSttsCdOrderByRegDtDescMarkingSnDesc(
                rawSn, LsMarking.STATUS_RESERVED);
        if (reserved.isEmpty()) {
            // 예약이 없는 영상 — 사람이 직접 마킹하는 통상 경로다. 오류가 아니다.
            return Optional.empty();
        }

        Long markingSn = reserved.get(0).getMarkingSn();
        int claimed = markingRepository.transitionMarkingIfStatus(
                markingSn, LsMarking.STATUS_RESERVED, LsMarking.STATUS_PENDING);
        if (claimed != 1) {
            log.info("[MarkingActivation] reservation already claimed by another node rawSn={} markingSn={}",
                    rawSn, markingSn);
            return Optional.empty();
        }

        // 활성으로 올린 1건 외의 잉여 예약은 깨어날 자리가 없다 — 고아로 남기지 않고 함께 마감한다.
        int swept = markingRepository.transitionAllByRawSnIfStatus(
                rawSn, LsMarking.STATUS_RESERVED, LsMarking.STATUS_SKIPPED);
        if (swept > 0) {
            log.warn("[MarkingActivation] surplus reservations closed rawSn={} count={}", rawSn, swept);
        }

        // 이후 처리 시작 — 사람이 마킹을 다시 찍지 않는다. 커밋 이후 브리지가 잔여 배치를 기동한다.
        eventPublisher.publishEvent(new MarkingCompletedEvent(rawSn, markingSn));
        log.info("[MarkingActivation] reserved marking activated rawSn={} markingSn={}", rawSn, markingSn);
        return Optional.of(markingSn);
    }

    /**
     * 영상의 <b>예약 마킹을 적용하지 못한 채 마감</b>한다({@code RESERVED → SKIPPED}).
     *
     * <p>비식별이 끝내 실패해 그 예약을 쓸 수 없게 됐을 때 호출한다. <b>적재 자체는 되돌리지 않는다</b> —
     * 영상 행과 복사된 파일은 그대로 두고 마킹만 마감한다. 마감된 마킹은 활성 집합
     * ({@link LsMarking#ACTIVE_STATUSES}) 밖이므로 <b>사람이 그 영상을 다시 마킹할 수 있다</b>.
     *
     * <p>조건부 UPDATE 라 <b>예약이 아닌 마킹은 절대 건드리지 않는다</b>. 활성화와 마감이 겹쳐도 방금
     * 깨어난 {@code PENDING} 마킹을 덮지 않으며, 예약이 없으면 0건으로 조용히 끝난다(멱등).
     *
     * @param rawSn  대상 영상 PK (nullable — null 이면 no-op)
     * @param reason 마감 사유 — 운영 로그용. 외부 원문이 섞일 수 있어 정제해 기록한다(CWE-117)
     * @return 실제로 마감된 예약 수
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public int closeReservations(Long rawSn, String reason) {
        if (rawSn == null) {
            return 0;
        }

        int closed = markingRepository.transitionAllByRawSnIfStatus(
                rawSn, LsMarking.STATUS_RESERVED, LsMarking.STATUS_SKIPPED);
        if (closed > 0) {
            log.warn("[MarkingActivation] reservations closed unapplied rawSn={} count={} reason={}",
                    rawSn, closed, LogSanitizer.sanitize(reason));
        }
        return closed;
    }
}
