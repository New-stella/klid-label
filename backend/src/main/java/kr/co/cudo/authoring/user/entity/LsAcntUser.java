package kr.co.cudo.authoring.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 사용자 마스터 엔티티 — <b>저작도구 소유</b> {@code LS_ACNT_USER} (V169).
 *
 * <h3>소유권 전환 (2026-08-04)</h3>
 * <p>구 엔티티(관제 소유 사용자 마스터, V169 로 DROP)는 "관제가 채워 준다"는 전제로
 * {@code @Immutable} READ 전용이었다. 실측 결과 <b>아무도 채우지 않았고</b>(관제 2차 실DB 에 MNG_
 * 접두 테이블 0개, 저작도구 쓰기 경로 0), 신규 사용자는 DBA 가 손으로 넣지 않으면 역할 클레임이
 * 404 로 막혔다. 이제 사용자 정보는 관제가 브라우저 {@code localStorage} 로 인계하는 값
 * ({@code userId}·{@code userNm})을 <b>역할 클레임 시점에 자동등록</b>해 채운다.
 *
 * <p>따라서 <b>{@code @Immutable} 을 붙이지 않는다</b> — 붙이면 갱신이 조용히 무시된다.
 * 다만 실제 쓰기는 {@code UserRepository.upsertUser} <b>원자 upsert</b> 로만 한다(2노드
 * Active-Active 에서 조회 후 INSERT 는 PK 위반 → 트랜잭션 abort). 회귀 가드:
 * {@code MngAcctUserTableRemovalTest}.
 *
 * <p><b>인가 역할은 이 엔티티에 없다</b> — 저작도구 역할의 단일 진실원은 {@link LsUserRole} 이다.
 */
@Entity
@Table(name = "LS_ACNT_USER")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsAcntUser {

    /** 사용자번호(PK). ★JWT subject 에서만 취한다 — 요청 바디의 값을 신뢰하지 않는다. */
    @Id
    @Column(name = "USER_NO")
    private Long userNo;

    /** 사용자아이디(표시용). 관제 미수신이면 null — 지어내지 않는다. */
    @Column(name = "USER_ID", length = 20)
    private String userId;

    /** 사용자명(표시용). 미수신이면 빈 문자열(기존 "이름 없으면 \"\"" 관례 + toMap NPE 방지). */
    @Column(name = "USER_NM", nullable = false, length = 100)
    private String userNm;

    /** 사용자이메일주소. 관제 인계 키에 없어 자동등록 사용자는 null 이다. */
    @Column(name = "USER_EML_ADDR", length = 320)
    private String userEmlAddr;

    /** 사용여부(Y/N). ★자동등록이 갱신하지 않는다 — 비활성화된 사용자가 재클레임으로 되살아나면 안 된다. */
    @Column(name = "USE_YN", nullable = false, length = 1)
    private String useYn;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    /**
     * 최종로그인일시 (V12) — 맨 나중에 접속한 시각. <b>한 번도 접속하지 않았으면 null</b> 이다.
     *
     * <p>물리명·도메인은 행안부 공통표준용어 {@code 최종로그인일시 = LAST_LGN_DT}(연월일시분초D)다.
     *
     * <h3>왜 필요했나</h3>
     * <p>사용자 관리 화면이 「최신 로그인」 컬럼을 그리는데 <b>그 데이터가 없었다</b>. 화면은 값이
     * 없으니 {@link #regDt 등록일}을 폴백으로 넣어, 가입 시각을 "최근 로그인"으로 표시하고 있었다.
     * 두 값은 용도가 다른 별개 축이므로(등록일=가입 이력 / 최종로그인일시=휴면 계정 판단) 서로를
     * 대체할 수 없다.
     *
     * <h3>쓰기 경로는 하나다</h3>
     * <p>{@code UserRepository.touchLastLogin} 조건부 UPDATE 뿐이다. 저작도구에는 독립 로그인 UI 가
     * 없으므로 기록 지점은 <b>JWT 검증을 통과한 INTERNAL 요청</b>이며, 그 경로는 모든 요청에서
     * 돌기 때문에 최소 간격(throttle)을 조건절로 강제한다. 상세는
     * {@link kr.co.cudo.authoring.user.service.LastLoginRecorder}.
     *
     * <p><b>null 을 기본값으로 채우지 않는다</b> — 미접속 계정에 값을 넣으면 "접속한 적 있음"이라는
     * 없던 사실이 생겨 휴면 판단이 무의미해진다. 같은 이유로 기존 행 백필도 하지 않는다(V12).
     *
     * <p>★ 이 엔티티에는 setter 가 없어 Hibernate dirty checking 이 UPDATE 를 만들 수 없다. 그래서
     * 다른 경로가 전체 컬럼 UPDATE 로 이 값을 <b>조용히 되돌리는</b> 사고(과거 실사례)가 구조적으로
     * 발생하지 않는다. setter/수정 메서드를 추가하려면 그 불변식을 먼저 검토할 것.
     */
    @Column(name = "LAST_LGN_DT")
    private LocalDateTime lastLgnDt;
}
