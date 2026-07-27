package kr.co.cudo.authoring.controlnotify.service;

import java.util.List;
import java.util.Set;

/**
 * 프레임 1건의 변경 묶음 — 프레임 식별자와 그 프레임에서 발생한 변경 종류의 <b>페어</b>.
 *
 * <p>D-ISSUE-42 대응: 구 구조는 {@code frameIds}/{@code changeTypes} 를 평행한 별개 Set 으로 보관해
 * "어느 프레임이 어떤 변경인지" 를 복원할 수 없었다. 본 값객체가 상관관계를 보존한다.
 *
 * <p><b>LOW-1(Phase 5C)</b>: 관제 전송 계약({@code TaskModifiedPayload})은 변경 <b>파일명 목록</b>만 담고
 * 변경 종류를 싣지 않는다. {@code changeTypes} 는 전송되지 않지만 죽은 필드가 아니라
 * {@code ControlNotifyDebouncer} flush 요약 로그에서 프레임↔변경종류 페어를 <b>감사 관측</b>하는 데 쓰인다.
 * (전송 계약에 변경 종류를 실으려면 관제팀 협의가 필요하므로 현재는 관측으로만 반영한다.)
 *
 * @param srcSn       프레임 PK ({@code LS_DATA_SRC.SRC_SN})
 * @param changeTypes 해당 프레임에서 발생한 변경 종류 (ChangeType 상수)
 */
public record FrameChangeSet(Long srcSn, Set<String> changeTypes) {

    public FrameChangeSet {
        changeTypes = changeTypes == null ? Set.of() : Set.copyOf(changeTypes);
    }

    public static List<Long> srcSnsOf(List<FrameChangeSet> changes) {
        return changes.stream().map(FrameChangeSet::srcSn).toList();
    }
}
