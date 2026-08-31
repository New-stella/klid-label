package kr.co.cudo.authoring.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 저작도구 소유 사용자 역할 매핑 (역할 분리 리팩토링 Phase 1).
 *
 * <p>저작도구 고유 역할(REVIEWER/WORKER/PORTAL_USER)을 구 구조의 관제 소유 권한 매핑 테이블에서
 * 분리해 저작도구 자체 LS 테이블로 보관한다. 읽기/쓰기 경로 전환(Phase 2~3)이 끝난 뒤 구 권한
 * 테이블 2종은 참조가 0 이 되어 V165 로 삭제됐고, 지금은 <b>이 테이블이 저작도구 인가 역할의
 * 단일 진실원</b>이다.
 *
 * <p>비즈니스 규칙:
 * <ul>
 *   <li>USER_NO 단일 PK — {@code LS_ACNT_USER.USER_NO} 를 ID 로만 참조(@ManyToOne/FK 미설정,
 *       Aggregate 간 ID 참조 원칙).</li>
 *   <li>ROLE_CD 는 REVIEWER / WORKER / PORTAL_USER 중 하나. 코드값 자체는 문자열이되 유효성
 *       검증은 상위 레이어(Service/DTO)가 담당한다(매직값 하드코딩 금지).</li>
 *   <li>상태 변경은 {@link #changeRole(String)} 비즈니스 메서드로만 — {@code @Setter} 금지.</li>
 * </ul>
 */
@Entity
@Table(name = "LS_USER_ROLE")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsUserRole {

    @Id
    @Column(name = "USER_NO")
    private Long userNo;

    @Column(name = "ROLE_CD", nullable = false, length = 32)
    private String roleCd;

    @Column(name = "REG_DT", nullable = false, updatable = false)
    private LocalDateTime regDt;

    @Column(name = "UPD_DT")
    private LocalDateTime updDt;

    /**
     * <b>수정자아이디 — 이 사용자의 역할을 마지막으로 바꾼 사람</b> (V22, @design AC-1018).
     *
     * <p>권한 상승은 감사에서 가장 중요한 사건인데 그동안 이 표에는 <b>대상만 있고 주체가
     * 없었다</b>. 값의 출처는 인계 토큰의 subject 하나이며 <b>요청 바디에서 오지 않는다</b>
     * — 바디 값은 위조 가능하다({@code UserService.update} 참조).
     *
     * <p>{@code null} 은 결함이 아니라 사실이다: ①이 컬럼이 생기기 전에 바뀐 행 ②인증 주체를
     * 알 수 없는 경로(운영 배치·시험 하네스). 지어낸 값으로 채우지 않는다.
     *
     * <p>★ 운영 쓰기 경로는 {@code LsUserRoleRepository.upsertRole(userNo, roleCd, mdfrId)}
     * 네이티브 upsert <b>한 곳</b>이다. {@link #changeRole(String)} 은 이 값을 건드리지 않는다
     * — 그 메서드는 현재 시험 하네스에서만 쓰이며, 운영 경로를 둘로 늘리지 않기 위해 주체
     * 인자를 붙이지 않았다.
     */
    @Column(name = "MDFR_ID", length = 30)
    private String mdfrId;

    private LsUserRole(Long userNo, String roleCd) {
        this.userNo = userNo;
        this.roleCd = roleCd;
    }

    /**
     * 정적 팩토리 — 새 역할 매핑 생성.
     *
     * @param userNo 사용자 번호 (LS_ACNT_USER.USER_NO, PK)
     * @param roleCd 역할 코드 (REVIEWER / WORKER / PORTAL_USER — 검증은 상위 레이어)
     */
    public static LsUserRole of(Long userNo, String roleCd) {
        return new LsUserRole(userNo, roleCd);
    }

    /** 비즈니스 메서드 — 역할 변경. Setter 대신 의미 있는 메서드명 사용. */
    public void changeRole(String roleCd) {
        this.roleCd = roleCd;
        this.updDt = LocalDateTime.now();
    }

    @PrePersist
    void onCreate() {
        if (this.regDt == null) {
            this.regDt = LocalDateTime.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updDt = LocalDateTime.now();
    }
}
