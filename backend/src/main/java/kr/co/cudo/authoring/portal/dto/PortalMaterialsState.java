package kr.co.cudo.authoring.portal.dto;

/**
 * 조달 진행 상태 — 착수·상태 조회 창구의 응답 값역.
 *
 * <p>진실원이 축마다 다르다는 것을 그대로 반영한다: {@link #READY} 는 <b>파일 시스템</b>(공개된
 * 해제본 디렉터리의 존재)이 정하고, {@link #IN_PROGRESS} 와 {@link #FAILED} 는 <b>이 배포본의
 * 메모리</b>가 갖는다. 그래서 재기동하면 뒤의 둘은 사라지고 앞의 하나만 남는다 — 의도된 성질이다
 * (고아 잠금으로 영영 막히지 않는다).
 *
 * @design INT-014
 */
public enum PortalMaterialsState {

    /** 조달한 적이 없거나, 실패 기록이 상한에 밀려 사라졌다 — 착수할 수 있다. */
    NOT_PROVISIONED,

    /** 이 배포본에서 조달이 진행 중이다 — 다시 착수해도 새로 시작하지 않는다. */
    IN_PROGRESS,

    /** 해제본이 공개돼 있다 — 다시 착수해도 새로 시작하지 않는다. */
    READY,

    /** 직전 조달이 실패했다 — 사유는 함께 실리며, 다시 착수할 수 있다. */
    FAILED
}
