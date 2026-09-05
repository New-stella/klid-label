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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * 포털 사용자 이벤트 어노테이션 오버레이 ({@code LS_PORTAL_USER_EVNT_ANNO}) — <b>단방향</b>.
 *
 * <p>원본({@code LS_EVNT_ANNO})과 승인 시점 동결본을 <b>수정하지 않는다</b> — 데이터마트로
 * 되돌아가지 않으며 관제 통지·산출물 재생성을 일으키지 않는다. 적재 키는 (포털사용자, 영상)이라
 * 영상당 한 벌이고 다시 저장하면 덮어쓴다.
 *
 * <h3>★ 메타 오버레이와 한 표로 합치지 않는다</h3>
 * <p>메타는 키/값이고 이것은 구조체다. 한 표에 섞으면 본문 폭과 조회 모양이 서로를 제약한다.
 * 본문을 키/값으로 펴는 안도 채택하지 않았다 — 산출 문서와 모양이 갈려 내보낼 때마다 재조립이
 * 필요하고 사고 단계 같은 중첩이 키 이름으로 들어가 무너진다. 그래서 {@code jsonb} 원문이다.
 *
 * <h3>★ 본인이 올린 자산은 이 표를 쓰지 않는다</h3>
 * <p>가려야 할 남의 원본이 없어 병합할 것이 없고, 그 자산의 어노테이션 원장에 그대로 앉는다.
 *
 * <p>영상 축이라 <b>프레임 참조를 두지 않는다.</b>
 *
 * @design ERD-018
 * @design API-236
 * @design API-237
 */
@Entity
@Table(name = "LS_PORTAL_USER_EVNT_ANNO")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsPortalUserEvntAnno {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "USER_EVNT_ANNO_SN")
    private Long userEvntAnnoSn;

    @Column(name = "PORTAL_USER_NO", nullable = false, length = 100)
    private String portalUserNo;

    @Column(name = "SRC_RAW_SN", nullable = false)
    private Long srcRawSn;

    /** 내부 원장과 같은 구조체를 그대로 담는다(JSON 문자열). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ANNO_CN", columnDefinition = "jsonb", nullable = false)
    private String annoCn;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT", nullable = false)
    private LocalDateTime mdfcnDt;
}
