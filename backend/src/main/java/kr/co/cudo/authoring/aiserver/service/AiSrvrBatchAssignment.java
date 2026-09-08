package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrUsageType;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvrAltmnt;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrAltmntRepository;
import kr.co.cudo.authoring.common.client.PinnedTarget;
import kr.co.cudo.authoring.common.client.VlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * <b>같은 영상의 프레임은 같은 장비로</b> — 배치 경로의 영상 고정. [@design ADR-057]
 *
 * <h3>왜 고정하는가 (이 고정이 근거 결정의 채택 사유 그 자체다)</h3>
 * <p>추론 서버는 상태가 없지 않다 — 영상 단위로 추적 상태를 <b>메모리에</b> 들고 있어 같은 영상의
 * 프레임이 두 장비로 흩어지면 <b>객체 식별자가 어긋난다</b>. 앞단 중계 방식이 기각된 이유가 바로
 * 이 고정을 할 수 없다는 것이었으므로, 분산만 배선하고 고정을 빼면 <b>기각 사유였던 결함을 그대로
 * 들여오는 셈</b>이다.
 *
 * <h3>★ 배치만 기록한다 — 화면은 기록하지 않는다</h3>
 * <p>화면에서 쓰는 요청은 <b>요청 하나가 한 장비로 가면 그 안에서 연속성이 지켜진다</b>. 기록하면
 * 사람이 한 번 눌러 본 영상이 영구히 한 장비에 묶여, 배치가 그 영상을 다룰 때 부하를 반영할 수
 * 없게 된다. 그래서 이 자리는 <b>배치 스텝만</b> 부른다.
 *
 * <h3>★ 시계열은 고정하지 않는다 (여기로 끌어오지 말 것)</h3>
 * <p>영상 하나에 위탁 한 번이라 고정할 대상이 없고, 고정하면 죽은 장비에 묶인 영상이 영영 다른
 * 장비로 가지 못한다. 그 축이 배정 표에 기록하지 않는 이유이며 그대로 둔다.
 *
 * <h3>고정된 장비를 지금 쓸 수 없으면 — <b>재배정한다</b> (2026-09-08 판단)</h3>
 * <p>설계에 명시된 처리가 없어 아래 근거로 정했다.
 * <ol>
 *   <li>근거 결정이 객체 식별자가 끊기는 원인 넷 가운데 <b>「장비 이탈로 인한 재배정」</b>을
 *       명시적으로 다루고 <i>「재배정은 기록을 남긴다」</i>고 적는다 — 재배정이 일어나는 일로
 *       이미 전제돼 있다.</li>
 *   <li>재배정하지 않으면 그 영상은 <b>영구히</b> 죽은 장비에 묶인다. 배정은 영상당 한 건이라
 *       갈아탈 자리가 없고, 「장비 복귀 → 밀린 영상 자동 재개」는 이번 범위 밖이라 <b>사람이
 *       저장소를 직접 고쳐야</b> 풀린다.</li>
 *   <li>고정이 지키려는 <b>추적기 기억은 그 장비가 이용불가로 관측된 시점에 이미 사라졌다</b> —
 *       그 프로세스의 로컬 메모리였기 때문이다. 지킬 연속성이 남아 있지 않다.</li>
 *   <li>옮기는 조건은 <b>「지금 고를 수 없다」 하나뿐</b>이다. 부하가 기울었다고 옮기지 않으므로
 *       살아 있는 장비에서 처리 중인 영상은 절대 갈아타지 않는다.</li>
 * </ol>
 *
 * <h3>★★ 정비중 장비는 <b>이 조항의 대상이 아니다</b> (2026-09-08 정정) [@design AC-1100]</h3>
 * <p>정비중은 「신규 배정만 막고 진행 중인 작업은 끝까지」라는 뜻이고 그것이 무중단 정비의 정의다.
 * 그 장비는 <b>살아 있으므로</b> 위 근거 셋 가운데 <b>③이 성립하지 않는다</b> — 추적기 기억이 온전히
 * 남아 있어 지킬 연속성이 있다.
 *
 * <p>⚠ <b>구 동작 폐기</b> — 판정을 {@link AiSrvrSelector#selectPinned} 에 맡겼는데 그 얼굴이 「신규
 * 배정 대상」과 <b>같은 후보 목록</b>을 보고 있어 정비중 장비에 고정된 영상이 곧바로 재배정됐다.
 * 실제 도달 경로: YOLO 가 gpu01 에 고정 → 관리자가 gpu01 을 정비로 내림 → 이어지는 SAM2 가 gpu02 로
 * 옮겨 가 앞 단계의 추적 상태를 이어받지 못한다. 지금은 그 얼굴의 <b>모집단이 갈려</b> 있다.
 *
 * <p>⚠ <b>이용불가·비활성은 종전대로 재배정한다</b> — 그것은 정당한 동작이고 위 근거 셋이 그대로
 * 성립한다.
 *
 * <p>⚠ 그래도 <b>남는 대가</b>: 재배정된 영상은 그 지점에서 객체 식별자가 끊긴다. 근거 결정이
 * 「재배정은 기록을 남긴다」고 적은 이유가 이것이며, 배정 행의 <b>사유·장비·시각</b>이 그 기록이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiSrvrBatchAssignment {

    private final AiSrvrSelector selector;
    private final LsAiSrvrAltmntRepository altmntRepository;

    /**
     * 이 영상의 배치 추론을 보낼 장비 <b>기준 주소</b>를 정한다(없으면 배정하고 기록한다).
     *
     * <p>돌려주는 값은 세 갈래다 — 주소가 있으면 그 장비로 고정,
     * <b>{@link PinnedTarget#DEPLOY_DEFAULT_TARGET}</b> 은 「고르지 못했으나 막지 않는다」(원장·부하
     * <b>조회 실패</b> → 분산만 포기하고 배포 기본 주소로 나간다), <b>예외</b>는 「쓸 수 있는 후보가
     * 0」이다(폴백 없음). 후보 0 판정은 {@link AiSrvrSelector} 가 소유하며 여기서 다시 쓰지 않는다.
     * [@design AC-1093]
     *
     * <p>★ <b>「고르지 못했다」를 {@code null} 로 돌려주지 않는다</b>(2026-09-08 정정) —
     * 클라이언트는 {@code null} 을 「내가 고르라」로 읽어 <b>프레임마다 다시 고른다</b>. 그러면 한
     * 영상의 프레임이 여러 장비로 흩어져 추적이 <b>프레임 경계에서 조용히 끊긴 채 적재</b>되고,
     * 그 구간에는 배정 기록도 없어 사후에 알아낼 수도 없다. 그래서 「정하지 못했다」도 <b>영상 단위로
     * 한 번</b> 정한 답으로 만들어 표식으로 내려보낸다. [@design ADR-057] [@design AC-1100]
     *
     * <h3>★★ 고정된 배정을 <b>먼저</b> 확인한다 — 순서가 곧 사양이다 [@design AC-1100]</h3>
     * <p>구 동작은 새 장비 선택을 <b>무조건 먼저</b> 불렀다. 그러면 그 자리에서 「쓸 수 있는 후보가
     * 0」 거부가 나 <b>이미 고정돼 있고 그 장비가 살아 있는 영상까지</b> 함께 막힌다 — 살아 있는
     * 정비중 장비에 고정된 영상이 같은 계통의 다른 장비가 내려간 순간 거부되는 것이 그 조합이다.
     * 「신규 배정만 막고 진행 중인 작업은 끝까지」는 <b>술어를 가르는 것만으로는 지켜지지 않고
     * 순서로도 지켜져야 한다</b>. ⚠ 술어를 다시 합치지 말 것 — 깨진 것은 구조가 아니라 호출 순서였다.
     *
     * <p><b>넣기와 되읽기가 같은 트랜잭션</b>이어야 멱등 배정이 성립하므로 경계를 여기서 연다.
     *
     * @param rawSn 배정 대상 영상. {@code null} 이면 고정할 대상이 없으므로 고정 없이 고르기만 한다
     */
    @Transactional("controlTransactionManager")
    public String resolveAddress(Long rawSn) {
        // ★① 이미 고정된 배정이 있고 그 장비를 지금 쓸 수 있으면 <그대로> 간다 — 새로 고르지 않는다.
        //   여기서 selector.select 를 먼저 부르면 후보 0 거부가 이 영상까지 막는다(위 javadoc).
        String pinnedAddr = pinnedAddressOf(rawSn);
        if (pinnedAddr != null) {
            return pinnedAddr;
        }
        Optional<LsAiSrvr> chosen = selector.select(LsAiSrvr.SrvrType.INFERENCE, AiSrvrUsageType.BATCH);
        if (chosen.isEmpty() || rawSn == null) {
            // empty = 조회 실패(분산만 포기 → 표식). rawSn 이 없으면 기록할 축이 없어 고정하지 않는다.
            return chosen.map(LsAiSrvr::getSrvrAddr).orElse(PinnedTarget.DEPLOY_DEFAULT_TARGET);
        }
        LsAiSrvr picked = chosen.get();
        LsAiSrvrAltmnt assigned = altmntRepository.assignIfAbsent(
                rawSn, picked.getSrvrId(), LocalDateTime.now());
        String assignedSrvrId = assigned.getSrvrId();
        if (picked.getSrvrId().equals(assignedSrvrId)) {
            // 우리가 배정했거나, 이미 같은 장비로 배정돼 있었다.
            return picked.getSrvrAddr();
        }

        // 기존 배정이 이겼다 — <그 장비로> 보내는 것이 고정이다. 방금 고른 장비로 보내면 기록과
        // 목적지가 갈린다.
        Optional<LsAiSrvr> pinned = selector.selectPinned(LsAiSrvr.SrvrType.INFERENCE, assignedSrvrId);
        return pinned.map(LsAiSrvr::getSrvrAddr)
                .orElseGet(() -> reassign(rawSn, assignedSrvrId, picked));
    }

    /**
     * 이미 고정된 배정의 <b>기준 주소</b> — 없거나 지금 쓸 수 없으면 {@code null}.
     *
     * <p>유지 판정은 {@link AiSrvrSelector#selectPinned} 가 <b>단독으로</b> 갖는다(정비중은 유지,
     * 이용불가·비활성은 유지하지 않는다). 여기서 그 규칙을 다시 쓰면 사본이 두 번째 진실원이 된다.
     *
     * <p>⚠ <b>조회가 실패하면 막지 않고 넘어간다</b>. 이 자리는 「빨리 끝내는 지름길」이지 판정
     * 지점이 아니다 — 원장을 읽지 못한 것을 여기서 예외로 올리면, 그 경우 분산만 포기하고 배포 기본
     * 주소로 나간다는 규약이 <b>이 지름길 때문에</b> 깨진다. 뒤따르는 선택기가 같은 실패를 다시
     * 만나 그 규약대로 처리한다.
     */
    private String pinnedAddressOf(Long rawSn) {
        if (rawSn == null) {
            return null;
        }
        try {
            return altmntRepository.findByRawSn(rawSn)
                    .map(LsAiSrvrAltmnt::getSrvrId)
                    .flatMap(srvrId -> selector.selectPinned(LsAiSrvr.SrvrType.INFERENCE, srvrId))
                    .map(LsAiSrvr::getSrvrAddr)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("[AiSrvr] 고정 배정 확인에 실패했습니다(선택 단계로 넘어갑니다). rawSn={} cause={}",
                    rawSn, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 배정된 장비를 지금 쓸 수 없어 옮긴다 — 위 클래스 주석 §고정된 장비를 지금 쓸 수 없으면.
     *
     * <p>조건부 UPDATE 가 0 을 돌려주면 <b>그 사이 다른 노드가 먼저 옮겼다</b>. 그때는 우리가 고른
     * 장비를 우기지 않고 <b>이긴 쪽의 결정을 다시 읽는다</b> — 두 노드가 같은 영상을 서로 다른
     * 장비로 갈라 보내지 않게 하는 지점이다. 그 결정마저 지금 쓸 수 없으면(드문 경합) 우리가 고른
     * 장비로 진행한다 — 기록은 이긴 쪽 것이라 갈리지만, 그 대안은 「보내지 않는 것」뿐이다.
     *
     * <h3>★ 사유를 <b>원장에</b> 남긴다 — 응용 로그는 사후 진단의 근거가 되지 못한다 [@design AC-1100]</h3>
     * <p>수용기준이 요구하는 것은 「다시 정했다는 <b>사유</b>와 새 장비, 그 시각」 셋이다. 장비와 시각만
     * 갈아 끼우면 원장만 보고는 최초 배정과 재배정을 구분할 수 없다(응용 로그는 보존 기간이 짧고 노드마다
     * 흩어져 있다). 문구의 소유자는 {@link LsAiSrvrAltmnt#reassignReason(String)} 이며 여기서 조립하지
     * 않는다 — 조립하면 같은 사건이 호출부마다 다른 문장으로 남는다. <b>새 컬럼은 만들지 않았다</b>
     * (사유 칸은 원장에 이미 있다).
     */
    private String reassign(Long rawSn, String fromSrvrId, LsAiSrvr to) {
        int moved = altmntRepository.reassignIfCurrent(
                rawSn, fromSrvrId, to.getSrvrId(), LocalDateTime.now(),
                LsAiSrvrAltmnt.reassignReason(fromSrvrId));
        if (moved > 0) {
            // ★주소 「값」은 싣지 않는다 — 내부 토폴로지다(CWE-497). 식별자만 남긴다.
            log.warn("[AiSrvr] 배정된 장비를 지금 쓸 수 없어 영상을 재배정했습니다 — 이 지점에서 객체"
                            + " 식별자가 끊길 수 있습니다. rawSn={} from={} to={}",
                    rawSn, VlmClient.safeForLog(fromSrvrId), VlmClient.safeForLog(to.getSrvrId()));
            return to.getSrvrAddr();
        }
        String current = altmntRepository.findByRawSn(rawSn)
                .map(LsAiSrvrAltmnt::getSrvrId)
                .orElse(null);
        return selector.selectPinned(LsAiSrvr.SrvrType.INFERENCE, current)
                .map(LsAiSrvr::getSrvrAddr)
                .orElseGet(to::getSrvrAddr);
    }
}
