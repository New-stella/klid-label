package kr.co.cudo.authoring.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 관리자 공유 자격(비밀번호 해시) — <b>행이 최대 하나인 표</b>. [@design ADR-046] [@design ERD-029]
 *
 * <h3>왜 일반 설정 저장소가 아닌가</h3>
 * <p>시스템 설정 저장소는 편집 가능한 키를 <b>목록으로 그대로 돌려준다</b>. 자격을 거기 두면 설정을
 * 볼 수 있는 사람 전원이 해시를 손에 넣어 오프라인 대입 대상으로 삼을 수 있다. 그래서 자격만을 위한
 * 별도 자리를 둔다.
 *
 * <h3>행이 하나뿐인 것이 계약이다</h3>
 * <p>공유 자격은 하나다. 행이 둘이 되면 어느 것이 현재 자격인지 애플리케이션이 "가장 최근 것" 같은
 * 규칙으로 정하기 시작하고, 그 규칙이 곧 두 번째 진실원이 된다. DB 는 기본키와 체크 제약 두 겹으로
 * 이를 강제하며({@code mngr_pswd_sn = 1}) 이 엔티티는 그 값을 {@link #SINGLE_ROW_SN} 으로 고정한다.
 *
 * <h3>세대·판수 컬럼이 없는 것은 결정이다</h3>
 * <p>자격을 교체하면 그 전에 발급된 유효창은 무효가 되어야 한다. 그것을 세대 번호로 이루면 상태가
 * 하나 늘고 그 상태가 토큰 형식과 결합한다. 대신 <b>유효창 서명이 현재 자격에 의존</b>하게 해서 같은
 * 결과를 얻는다 — 자격이 바뀌면 과거 토큰의 서명이 더는 맞지 않는다.
 * ⇒ "무효화 세대가 필요하다"는 생각이 들면 그것은 이 결정을 되돌리는 것이다.
 *
 * <h3>평문은 담기지 않는다</h3>
 * <p>{@code PSWD_HASH} 는 BCrypt 해시만 담는다 (CWE-256).
 */
@Entity
@Table(name = "LS_MNGR_PSWD")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsMngrPswd {

    /** 유일한 행의 일련번호 — DB 체크 제약이 이 값만 허용한다. */
    public static final long SINGLE_ROW_SN = 1L;

    @Id
    @Column(name = "MNGR_PSWD_SN")
    private Long mngrPswdSn;

    @Column(name = "PSWD_HASH", nullable = false, length = 100)
    private String pswdHash;

    @Column(name = "MDFR_ID", length = 30)
    private String mdfrId;

    @Column(name = "PSWD_MDFCN_DT")
    private LocalDateTime pswdMdfcnDt;

    private LsMngrPswd(String pswdHash, String mdfrId, LocalDateTime pswdMdfcnDt) {
        this.mngrPswdSn = SINGLE_ROW_SN;
        this.pswdHash = pswdHash;
        this.mdfrId = mdfrId;
        this.pswdMdfcnDt = pswdMdfcnDt;
    }

    /**
     * 정적 팩토리 — 자격을 처음 저장한다.
     *
     * @param pswdHash BCrypt 해시 (★평문 금지)
     * @param mdfrId   교체한 사람의 식별자. 공유 자격이라 "지금 누가 아는가"는 알 수 없고
     *                 "누가 마지막으로 바꿨는가"만 남는다.
     */
    public static LsMngrPswd of(String pswdHash, String mdfrId, LocalDateTime pswdMdfcnDt) {
        return new LsMngrPswd(pswdHash, mdfrId, pswdMdfcnDt);
    }

    /** 자격 교체 — {@code @Setter} 대신 의미 있는 비즈니스 메서드로만 상태를 바꾼다. */
    public void changeHash(String pswdHash, String mdfrId, LocalDateTime pswdMdfcnDt) {
        this.pswdHash = pswdHash;
        this.mdfrId = mdfrId;
        this.pswdMdfcnDt = pswdMdfcnDt;
    }
}
