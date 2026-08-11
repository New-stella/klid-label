package kr.co.cudo.authoring.controlnotify.event;

import java.util.Set;

/**
 * 관제서버 outbound TASK_MODIFIED 통지의 변경 종류(changeType) 표준 값 집합.
 *
 * <p>계약값은 {@code LABEL_ADDED | LABEL_UPDATED | LABEL_DELETED | META_UPDATED} 이며,
 * {@link TaskModifiedEvent#changeType()} 로 발행되어 {@code ControlNotifyDebouncer} 가 프레임↔변경종류
 * 페어({@code FrameChangeSet.changeTypes})로 축적한다. 관제 전송 계약
 * ({@link kr.co.cudo.authoring.controlnotify.dto.TaskModifiedPayload})는 변경 <b>파일명 목록</b>만 담으므로
 * 변경종류 자체는 페이로드에 실리지 않고 flush 요약 로그(감사)로만 관측된다(LOW-1). 매직스트링 발행으로
 * 인한 계약 위반을 막기 위해 발행 측(LabelService, MetaService)·테스트가 본 상수를 공유한다.
 */
public final class ChangeType {

    /** 프레임에 라벨이 신규 추가됨. */
    public static final String LABEL_ADDED = "LABEL_ADDED";
    /** 프레임의 기존 라벨이 수정됨. */
    public static final String LABEL_UPDATED = "LABEL_UPDATED";
    /** 프레임의 라벨이 삭제됨. */
    public static final String LABEL_DELETED = "LABEL_DELETED";
    /** 프레임의 시계열 메타가 수정됨. */
    public static final String META_UPDATED = "META_UPDATED";
    /**
     * 프레임이 학습데이터 산출물에서 제외됨(폐기, R4).
     *
     * <p><b>관제와 아직 합의되지 않은 신규 값이다 (협의 대상).</b> 다만 <b>이 값이 관제로 전송되지는
     * 않는다</b> — 위 클래스 주석대로 {@code TaskModifiedPayload} 는 변경 <b>파일명 목록</b>만 담고
     * 변경종류는 디바운서의 축적 키와 flush 요약 로그(감사)로만 쓰인다. 관제가 관측하는 변화는
     * "그 프레임의 파일이 산출물에서 사라진다"이며, 그건 {@code V_COMPLETED_FRAME}·산출 폴더에서
     * 이미 드러난다. 그럼에도 기존 값에 욱여넣지 않는 이유는, 폐기는 라벨 수정과 <b>다른 사실</b>이라
     * 감사 로그에서 구분되지 않으면 나중에 원인을 되짚을 수 없기 때문이다.
     *
     * @req R4
     */
    public static final String FRAME_DISCARDED = "FRAME_DISCARDED";
    /**
     * 폐기했던 프레임이 다시 산출 대상이 됨(복원, R5). 계약상 지위는 {@link #FRAME_DISCARDED} 와 동일하다.
     *
     * <p>폐기와 복원을 한 값으로 합치지 않는 이유: 두 방향은 산출물에 정반대 영향을 주므로 감사에서
     * 구분돼야 한다(전이 방향을 사유 문구로 유추하게 두지 않는다).
     *
     * @req R5
     */
    public static final String FRAME_RESTORED = "FRAME_RESTORED";

    /** 계약상 허용되는 표준 changeType 전체 집합 (검증용). */
    public static final Set<String> ALL = Set.of(LABEL_ADDED, LABEL_UPDATED, LABEL_DELETED,
            META_UPDATED, FRAME_DISCARDED, FRAME_RESTORED);

    private ChangeType() {
    }
}
