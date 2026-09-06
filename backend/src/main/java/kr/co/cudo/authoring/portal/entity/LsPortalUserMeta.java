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
 * 포털 사용자 메타 오버레이 ({@code LS_PORTAL_USER_META}) — <b>단방향</b>.
 *
 * <p>포털 사용자가 데이터마트에서 불러온 영상의 메타를 확인·수정·추가한 결과가 여기에만 쌓인다.
 * 원본({@code LS_DATA_META})과 승인 시점 동결 스냅샷은 <b>수정하지 않는다</b> — 데이터마트로
 * 되돌아가지 않으며 관제 통지·산출물 재생성을 일으키지 않는다.
 *
 * <h3>★ 이 표를 메타 원장에 합치거나 소유자 구분으로 섞지 않는다</h3>
 * <p>합치면 포털에서의 저장이 원본을 덮어써 단방향 불변식이 깨진다. 소유자 구분으로 한 표에
 * 섞으면 그 구분을 한 번만 잊는 순간 <b>남의 오버레이가 정본으로 읽히는 fail-open</b> 이 되고,
 * 그 누락은 새 조회 경로가 생길 때마다 다시 열려 한 번의 점검으로 닫히지 않는다. 그래서 분리는
 * 조회 필터가 아니라 <b>표 자체</b>로 지킨다({@link LsPortalUserLabel} 과 같은 근거).
 *
 * <h3>★ 본인이 올린 자산은 이 표를 쓰지 않는다</h3>
 * <p>가려야 할 남의 원본이 없으므로 병합할 것이 없고, 그 자산의 메타 원장에 그대로 앉는다.
 * 저장처를 가르는 판정은 화면이 아니라 서버가 <b>자산 출처</b>로 한다
 * ({@code PortalWorkMetaService}).
 *
 * <h3>★ 프레임 참조는 영상 축 메타일 때 비운다</h3>
 * <p>{@link #srcDataSrcSn} 은 프레임 축 메타일 때만 채운다. 영상 축 메타에 채우면
 * <b>없는 프레임을 가리킨다.</b>
 *
 * <p>쓰기는 이 엔티티가 아니라 원자적 {@code ON CONFLICT} upsert
 * ({@code LsPortalUserMetaRepository}) 로 한다 — 같은 키에 대한 동시 저장(더블클릭 등)에서
 * 중복 행·값 유실이 생기지 않게 하기 위함이다. 이 클래스는 <b>읽기 모델</b>이다.
 *
 * @design ERD-018
 * @design API-234
 * @design API-235
 */
@Entity
@Table(name = "LS_PORTAL_USER_META")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsPortalUserMeta {

    /** {@code META_KEY} 컬럼 폭 — 내부 원장({@code LS_DATA_META.META_KEY})과 같다. */
    public static final int META_KEY_MAX_LENGTH = 64;

    /** {@code META_VL} 컬럼 폭 — 내부 원장({@code LS_DATA_META.META_VL})과 같다. */
    public static final int META_VALUE_MAX_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "USER_META_SN")
    private Long userMetaSn;

    @Column(name = "PORTAL_USER_NO", nullable = false, length = 100)
    private String portalUserNo;

    @Column(name = "SRC_RAW_SN", nullable = false)
    private Long srcRawSn;

    /** 프레임 축 메타일 때만 채운다. 영상 축은 {@code null}. */
    @Column(name = "SRC_DATA_SRC_SN")
    private Long srcDataSrcSn;

    @Column(name = "META_KEY", nullable = false, length = META_KEY_MAX_LENGTH)
    private String metaKey;

    @Column(name = "META_VL", length = META_VALUE_MAX_LENGTH)
    private String metaVl;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT", nullable = false)
    private LocalDateTime mdfcnDt;
}
