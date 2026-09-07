package kr.co.cudo.authoring.marking.service;

/**
 * 이 마킹에 쓸 <b>검증 이벤트 유형</b> 조달 결과 — 값과 <b>어디서 왔는지</b>를 함께 들고 다닌다.
 *
 * <h3>왜 출처를 함께 들고 다니는가</h3>
 * <p>조달 순서는 <b>관제 인입값 → 작업자 선택값 → 없음</b> 이고 <b>관제 인입값이 진실원</b>이다. 그런데
 * 소비처가 둘이라 값 하나로는 부족하다 — ①질문 조달 판정기에는 <b>유효 유형</b>(어디서 왔든)을 넘겨야
 * 하고 ②마킹 원장에는 <b>작업자가 고른 값일 때만</b> 저장해야 한다. 관제 값을 마킹 행에 베껴 두면
 * 인입 원장과 마킹 행 두 곳에 같은 값이 생겨, 한쪽이 바뀔 때 조용히 갈라진다(그 문제를 피하려고
 * 이벤트 유형 코드·영상 경로를 마킹 행에서 이미 걷어낸 전례가 있다).
 *
 * <p>그래서 값 하나(문자열)만 돌려주면 호출부가 「이게 관제 값인가 작업자 값인가」를 <b>다시 판정</b>해야
 * 하고, 그 판정이 복제되는 순간 이 저장소가 반복 경고해 온 사본 결함이 된다.
 *
 * @param typeCd     조달된 유형 코드(정규화 완료). 조달할 것이 없으면 {@code null}
 * @param fromWorker 이 값이 <b>작업자 선택</b>에서 왔는가 — 마킹 원장 저장 여부를 가른다
 * @design API-047
 * @design ERD-013
 */
public record MarkingEventType(String typeCd, boolean fromWorker) {

    /** 조달할 유형이 없다 — 관제도 안 보냈고 작업자도 고르지 않았다(또는 그 축이 없는 채널). */
    public static final MarkingEventType NONE = new MarkingEventType(null, false);

    /**
     * 관제 인입값에서 조달 — <b>진실원</b>이라 마킹 원장에 베끼지 않는다.
     *
     * @param normalizedTypeCd 정규화가 끝난 유형 코드
     */
    public static MarkingEventType fromControl(String normalizedTypeCd) {
        return new MarkingEventType(normalizedTypeCd, false);
    }

    /**
     * 작업자가 마킹 화면에서 고른 값에서 조달 — <b>관제 값이 없을 때만</b> 여기까지 온다.
     *
     * @param normalizedTypeCd 정규화가 끝난 유형 코드
     */
    public static MarkingEventType selectedByWorker(String normalizedTypeCd) {
        return new MarkingEventType(normalizedTypeCd, true);
    }

    /**
     * 마킹 원장({@code LS_MARKING.VRFC_EVNT_TYPE_CD})에 저장할 값 — <b>작업자 선택값일 때만</b> 값이 있다.
     *
     * <p>관제 값은 여기서 {@code null} 이다. 「저장하지 않는다」를 호출부의 if 문이 아니라 <b>이 자리</b>가
     * 소유해야 판정이 한 곳에 남는다.
     */
    public String persistableTypeCd() {
        return fromWorker ? typeCd : null;
    }
}
