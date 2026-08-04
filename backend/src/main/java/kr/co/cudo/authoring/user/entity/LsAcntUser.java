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
}
