package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * R4·R5 — 프레임 폐기·복원 <b>상태 전이 + 감사</b>의 단일 적용 지점.
 *
 * <h3>왜 별도 컴포넌트인가</h3>
 * 폐기 여부는 라벨 저장 계약에 실려 들어오지만(D8), "상태를 어떻게 안전하게 바꾸고 무엇을 감사에
 * 남기는가"는 라벨 본문 저장과 <b>독립된 관심사</b>다. 여기 한 곳에 두면 나중에 영상 단위 저장이
 * 추가돼도 같은 규칙(원자 UPDATE · 멱등 · 감사)을 재사용하게 되고, 호출부마다 다시 구현해 한쪽만
 * 감사를 빠뜨리는 일이 생기지 않는다.
 *
 * <h3>호출 규약 (Critical)</h3>
 * 호출자는 <b>프레임 행 락을 이미 획득한 상태</b>여야 한다
 * ({@code LsDataSrcRepository.lockAndReadLabelVersion} 의 {@code FOR UPDATE}). 이 컴포넌트는 새 락을
 * 잡지 않으므로 기존 잠금 순서(<b>프레임 행 → 라벨 행</b>)에 새 간선을 만들지 않는다. {@code LS_DATA_RAW}
 * 도 {@code LS_RAW_DATA_STATUS} 도 건드리지 않아 배치와의 교착(40P01) 축과 무관하다.
 *
 * <h3>논리 폐기</h3>
 * 프레임 행·이미지 파일·라벨을 <b>지우지 않는다</b>. 표시만 바꾸고, 산출물·데이터마트 노출에서만 빠진다.
 * 그래서 복원이 성립하고, 이미 승인·통지된 산출물의 근거도 남는다.
 *
 * @design D1
 * @design D8
 * @req R4
 * @req R5
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FrameDiscardApplier {

    private final LsDataSrcRepository srcRepository;
    private final LsTaskEventLogRepository taskEventLogRepository;

    /** 상태 전이 결과 — 호출부가 통지 변경종류를 고르는 축이기도 하다. */
    public enum Outcome {
        /** 요청에 폐기여부가 없었거나 이미 그 상태 — 아무것도 바뀌지 않았다(멱등). */
        UNCHANGED,
        /** 산출물에서 제외됨(R4). */
        DISCARDED,
        /** 다시 산출 대상이 됨(R5). */
        RESTORED;

        public boolean isChanged() {
            return this != UNCHANGED;
        }
    }

    /**
     * 요청에 실린 폐기여부를 적용한다.
     *
     * <p><b>보내지 않으면(null) 현재 값을 그대로 둔다</b> — 폐기를 모르는 기존 호출자가 저장할 때마다
     * 폐기 상태가 조용히 초기화되면 안 되기 때문이다(하위호환). 값은 요청 DTO 가 {@code Y}/{@code N} 으로
     * 이미 강제했으므로 여기서 형식을 다시 검사하지 않는다.
     *
     * <p>판정 기준은 <b>행 락 이후의 DB 현재 값</b>이다(1차 캐시 금지 —
     * {@code LsDataSrcRepository.readDiscardFlag} 주석 참조). 실제로 값이 바뀐 경우에만 감사 이력을 남겨,
     * 같은 상태를 재전송하는 저장이 이력을 부풀리지 않게 한다.
     *
     * @param srcSn     대상 프레임 PK — <b>호출부가 인가를 통과시킨 그 식별자</b>를 그대로 받는다.
     *                  엔티티에서 다시 꺼내지 않는 이유는, 인가·행 락·상태 변경이 모두 <b>같은 식별자</b>
     *                  를 대상으로 했음이 시그니처에서 드러나야 하기 때문이다(엔티티 식별자 적재 여부에
     *                  의존하지 않는다).
     * @param frame     대상 프레임(호출부가 인가 검사로 이미 확보한 엔티티) — 영상 식별자와 in-memory
     *                  상태 갱신에 쓴다
     * @param requested 요청 폐기여부({@code Y}/{@code N}) — {@code null} 이면 현재 값 유지
     * @param actorNo   수행자 사용자 번호(감사 actor)
     * @return 실제 전이 결과
     */
    public Outcome apply(Long srcSn, LsDataSrc frame, String requested, Long actorNo) {
        if (requested == null) {
            return Outcome.UNCHANGED;
        }
        // 행 락을 쥔 상태에서 읽은 DB 현재 값이 유일한 판정 기준이다.
        String current = srcRepository.readDiscardFlag(srcSn).orElse(LsDataSrc.DSCD_NO);
        if (requested.equals(current)) {
            return Outcome.UNCHANGED;
        }
        // 조건부 원자 UPDATE — 반환 행수가 곧 "실제로 바뀌었는가"다(엔티티 dirty checking 은 스냅샷이
        //   락 이전 값이라 복원 방향에서 UPDATE 를 만들지 않는 창이 있다. 리포지토리 주석 참조).
        if (srcRepository.applyDiscardFlag(srcSn, requested) == 0) {
            return Outcome.UNCHANGED;
        }
        // 영속 인스턴스도 같은 값으로 맞춘다 — 저장 응답이 방금 확정한 상태를 그대로 보여줘야 한다.
        //   (P1 이 만든 상태 전이 메서드가 유일한 in-memory 쓰기 통로다 — @Setter 금지 규약.)
        boolean discarded = LsDataSrc.DSCD_YES.equals(requested);
        if (discarded) {
            frame.discard();
        } else {
            frame.restore();
        }
        // OWASP A09 — 누가·언제·어느 프레임을 어느 방향으로 바꿨는가. 방향은 이벤트 타입 코드가
        //   구분하며, RSN 에는 식별자 한 토큰만 싣는다(자유 문구·경로·PII 금지 — CWE-359/117).
        taskEventLogRepository.save(discarded
                ? LsTaskEventLog.frameDiscarded(frame.getRawSn(), srcSn, actorNo)
                : LsTaskEventLog.frameRestored(frame.getRawSn(), srcSn, actorNo));
        // 식별자만 출력한다(라벨·경로 등 본문 미출력).
        log.info("[FrameDiscard] {} rawSn={} srcSn={} actor={}",
                discarded ? "discarded" : "restored", frame.getRawSn(), srcSn, actorNo);
        return discarded ? Outcome.DISCARDED : Outcome.RESTORED;
    }
}
