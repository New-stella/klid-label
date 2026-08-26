package kr.co.cudo.authoring.portal.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * V2.0 포털 사용자 작업 라벨 (LS_PORTAL_USER_LABEL).
 * 원본(LS_DATA_LBL) 미수정 정책 — 사용자 수정분은 본 테이블에 별도 적재.
 */
@Entity
@Table(name = "LS_PORTAL_USER_LABEL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsPortalUserLabel {

    /**
     * {@code TRCK_ID} 컬럼 폭(V20). 입구 검증이 이 값을 쓰지 않으면 초과 문자열이 INSERT 시점
     * DB 오류로 새어 500 이 된다.
     */
    public static final int TRACK_ID_MAX_LENGTH = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "USER_LBL_SN")
    private Long userLblSn;

    @Column(name = "PORTAL_USER_NO", nullable = false, length = 100)
    private String portalUserNo;

    @Column(name = "SRC_RAW_SN", nullable = false)
    private Long srcRawSn;

    @Column(name = "SRC_DATA_SRC_SN", nullable = false)
    private Long srcDataSrcSn;

    @Column(name = "LBL_TYPE_CD", nullable = false, length = 16)
    private String lblTypeCd;

    @Column(name = "LBL_NM", length = 80)
    private String labelNm;

    @Column(name = "POINT_CN", columnDefinition = "TEXT")
    private String pointCn;

    /**
     * 라벨 마스터(LS_LABEL) 참조 — 산출 어노테이션의 분류 식별자 조달처. 미연결이면 null.
     *
     * <p>FK 를 걸지 않는다(V20 주석) — 포털 전용 저장소는 내부 파이프라인과 분리 운영이라 마스터가
     * 비활성화돼도 포털 사용자의 과거 작업이 사라지면 안 된다. 참조 무결성은 조회 시점 join 으로 본다.
     *
     * <p>컬럼 신설 이전에 저장된 행은 null 이며 <b>백필하지 않는다</b> — 라벨명 소급 매칭은
     * 동명이인·비활성 마스터 오매칭 위험이 있어 채택하지 않았다. 재저장하면 자연 복구된다.
     *
     * @design ERD-018
     */
    @Column(name = "LBL_ID")
    private Long labelId;

    /**
     * 트랙 식별자 — 산출 어노테이션의 트랙 식별자 조달처. 트랙 미소속이면 null.
     *
     * <p>포털 사용자에게 트랙 번호 변경·병합 수단을 주는 값이 <b>아니다</b>. 데이터마트 원본에서
     * 불러온 라벨이 갖고 있던 트랙 연결이 저장 왕복에서 끊기지 않게 보존하는 용도다.
     *
     * @design ERD-018
     */
    @Column(name = "TRCK_ID", length = TRACK_ID_MAX_LENGTH)
    private String trackId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_DT", nullable = false)
    private LocalDateTime mdfcnDt;

    /** 마스터 연결·트랙 연결 없이 저장한다(두 값은 선택이며 없으면 그대로 비운다). */
    public static LsPortalUserLabel create(String portalUserNo, Long srcRawSn, Long srcDataSrcSn,
                                           String lblTypeCd, String labelNm, String pointCn) {
        return create(portalUserNo, srcRawSn, srcDataSrcSn, lblTypeCd, labelNm, pointCn, null, null);
    }

    /**
     * 마스터 연결({@code labelId})·트랙 연결({@code trackId})까지 함께 저장한다.
     *
     * <p>두 값은 <b>서버 판정을 마친 값</b>이어야 한다 — 요청값을 그대로 넘기지 말 것.
     * {@code labelId} 는 활성 라벨 마스터 실재 확인을, {@code trackId} 는 길이 상한 확인을
     * 호출자(저장 서비스)가 끝낸 뒤 넘긴다.
     *
     * @design ERD-018, API-082
     */
    public static LsPortalUserLabel create(String portalUserNo, Long srcRawSn, Long srcDataSrcSn,
                                           String lblTypeCd, String labelNm, String pointCn,
                                           Long labelId, String trackId) {
        LsPortalUserLabel entity = new LsPortalUserLabel();
        entity.portalUserNo = portalUserNo;
        entity.srcRawSn = srcRawSn;
        entity.srcDataSrcSn = srcDataSrcSn;
        entity.lblTypeCd = lblTypeCd;
        entity.labelNm = labelNm;
        entity.pointCn = pointCn;
        entity.labelId = labelId;
        entity.trackId = trackId;
        LocalDateTime now = LocalDateTime.now();
        entity.regDt = now;
        entity.mdfcnDt = now;
        return entity;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.regDt == null) this.regDt = now;
        if (this.mdfcnDt == null) this.mdfcnDt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.mdfcnDt = LocalDateTime.now();
    }
}
