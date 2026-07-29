package kr.co.cudo.authoring.controlnotify.debounce;

import kr.co.cudo.authoring.controlnotify.service.FrameChangeSet;

import java.util.List;
import java.util.Set;

/**
 * 클레임된 디바운스 윈도우 스냅샷 — 한 노드가 flush 소유권을 얻은 시점의 누적 변경 전량.
 *
 * <p>영속 행({@link LsMonNotiAcml})을 발송 계약이 쓰는 형태로 옮긴 읽기 전용 값객체다. 발송 후
 * {@code acmlSn} 으로 그 행만 정확히 제거한다(발송 중 들어온 새 윈도우는 건드리지 않는다).
 *
 * @param acmlSn                 누적 행 PK — 발송 완료 후 이 행만 삭제한다
 * @param rawSn                  영상 단위 작업 식별자(통지 단위)
 * @param frameChanges           프레임↔변경종류 페어(D-ISSUE-42 보존)
 * @param videoLevelChangeTypes  영상 단위 변경(srcSn=null)의 변경 종류(D-ISSUE-43 분리 축적)
 * @param exportRegenerated      윈도우 내 변경 중 하나라도 export 폴더 전량 재생성을 동반했는가(OR 누적)
 */
public record DebounceWindow(
        Long acmlSn,
        Long rawSn,
        List<FrameChangeSet> frameChanges,
        Set<String> videoLevelChangeTypes,
        boolean exportRegenerated
) {

    public DebounceWindow {
        frameChanges = frameChanges == null ? List.of() : List.copyOf(frameChanges);
        videoLevelChangeTypes = videoLevelChangeTypes == null ? Set.of() : Set.copyOf(videoLevelChangeTypes);
    }
}
