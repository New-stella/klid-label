package kr.co.cudo.authoring.portal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 포털 데이터셋 정리 트리거 접수 원장 ({@code LS_DATST_ARNGMT_TRGR}) — <b>읽기 모델</b>.
 *
 * <p>포털이 학습데이터셋의 새 버전을 받으면 정리 삭제 트리거를 보내고, 그 접수 기록이 여기 앉는다.
 * 한 행이 하나의 (데이터셋 코드, 버전) 짝에 대응하며 정리 유예의 <b>기산점</b>을 보관한다.
 *
 * <h3>★ 이 표는 보존 규칙의 정본이 아니다</h3>
 * <p>보존 기간·기산점 규칙·정리 절차의 정본은 포털 작업 데이터 보존기간 만료 자동 삭제 기능
 * (DFEAT-055)이다. 그 규칙을 여기에 복제하지 않는다 — 이 표는 그 규칙이 읽어 갈 <b>값</b>을 담는
 * 자리일 뿐이라, 유예 일수 같은 상수를 이 클래스에 두면 규칙의 두 번째 진실원이 된다.
 *
 * <h3>★ 쓰기는 이 엔티티로 하지 않는다</h3>
 * <p>적재는 {@code LsDatstArngmtTrgrRepository} 의 원자적 {@code ON CONFLICT DO NOTHING} 삽입으로
 * 한다. 조회한 뒤 저장하는 방식은 두 트리거가 <b>같은 순간에</b> 도착하면 둘 다 조회를 통과해 행이
 * 둘 생기고 기산점이 어느 쪽인지 알 수 없게 된다. 판정은 조회가 아니라 <b>실행문 자체</b>에 있다.
 *
 * <h3>★ 등록일시를 따로 두지 않는 것은 의도다</h3>
 * <p>행이 생기는 시각과 트리거를 받은 시각이 <b>같은 사실</b>이라, 둘을 나란히 두면 두 번째
 * 진실원이 되고 나중에 한쪽만 갱신되면 어느 쪽이 기산점인지 갈린다. 필드를 더하지 말 것.
 *
 * @design ERD-035
 * @design API-244
 */
@Entity
@Table(name = "LS_DATST_ARNGMT_TRGR")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsDatstArngmtTrgr {

    /**
     * {@code DATST_CD} 컬럼 폭.
     *
     * <p>⚠ 이 값은 <b>표현 형식의 확정이 아니라 컬럼 폭</b>이다. 포털의 데이터셋 코드 표현 형식
     * (길이·문자집합)은 아직 회신되지 않았고, ERD 가 코드값 표준도메인을 따라 잠정으로 20 을 두었다.
     * 입구에서 이 폭을 넘는 값을 400 으로 거부하는 이유는 형식을 정하기 위해서가 아니라, 그러지
     * 않으면 INSERT 시점 <b>DB 오류가 500 으로 새어</b> 저장 구조를 드러내기 때문이다.
     */
    public static final int DATASET_CODE_MAX_LENGTH = 20;

    /** {@code VER_NO} 컬럼 폭 — 표준도메인 번호V50. 위와 같은 이유로 입구에서 함께 막는다. */
    public static final int VERSION_MAX_LENGTH = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "DATST_ARNGMT_TRGR_SN")
    private Long datstArngmtTrgrSn;

    /**
     * 데이터셋코드 — 정리 대상을 가리키는 학습데이터셋 코드.
     *
     * <p>포털이 소유한 식별자라 이 저장소에 대응하는 부모 표가 없고 <b>외래키를 두지 않는다.</b>
     * 그래서 이 배포본이 알지 못하는 코드도 그대로 접수된다(창구가 202 로 답한다).
     */
    @Column(name = "DATST_CD", nullable = false, length = DATASET_CODE_MAX_LENGTH)
    private String datstCd;

    /**
     * 버전번호 — 포털이 새로 받은 학습데이터셋 버전.
     *
     * <p>★ 숫자로 파싱하지 않고 <b>문자열로 보존</b>한다. 창구가 받는 값이 문자열이고 버전 표기법이
     * 회신되지 않아, 숫자로 해석하면 표기법에 따라 값이 달라지거나 접수 자체가 실패한다.
     */
    @Column(name = "VER_NO", nullable = false, length = VERSION_MAX_LENGTH)
    private String verNo;

    /**
     * 수신일시 — 이 (데이터셋 코드, 버전) 트리거를 <b>처음 받은 시각</b>이며 정리 유예의 기산점이다.
     *
     * <p>★ 재수신으로 갱신하지 않는다. 갱신하면 중복 수신이 반복될 때마다 정리가 무한히 미뤄져
     * <b>영영 일어나지 않는다.</b> 값은 DB 기본값({@code CURRENT_TIMESTAMP})이 채운다.
     */
    @Column(name = "RCPTN_DT", nullable = false, insertable = false, updatable = false)
    private LocalDateTime rcptnDt;
}
