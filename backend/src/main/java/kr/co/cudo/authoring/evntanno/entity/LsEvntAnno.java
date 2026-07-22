package kr.co.cudo.authoring.evntanno.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * LS_EVNT_ANNO: 이벤트 어노테이션(event_annotation, 외부 VLM VQA/CoT) 저장.
 *
 * <p>외부 VLM 이 생성한 event_annotation payload(질의응답 + Chain-of-Thought + evidence)를
 * 영상(RAW_SN) 단위로 원문 보존한다. payload 본문은 jsonb 컬럼 {@code ANNO_CN} 에
 * String(JSON) 으로 저장하며, 구조 파싱은 {@code EventAnnotationPayload} DTO 가 담당한다.
 *
 * <p>포맷 미확정(PR#43 "키 변경 가능")이므로 본 엔티티는 payload 스키마를 강제하지 않고
 * jsonb 원문을 그대로 보관한다 — 키 변경 시 마이그레이션 없이 흡수된다.
 */
@Entity
@Table(name = "LS_EVNT_ANNO")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LsEvntAnno {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "EVNT_ANNO_SN")
    private Long evntAnnoSn;

    @Column(name = "RAW_SN", nullable = false)
    private Long rawSn;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ANNO_CN", columnDefinition = "jsonb", nullable = false)
    private String annoCn;

    @Column(name = "REG_ID", length = 30)
    private String regId;

    @Column(name = "REG_DT", nullable = false)
    private LocalDateTime regDt;

    @Column(name = "MDFCN_ID", length = 30)
    private String mdfcnId;

    @Column(name = "MDFCN_DT")
    private LocalDateTime mdfcnDt;

    /**
     * event_annotation 신규 저장. payload 는 유효한 JSON 문자열이어야 한다.
     *
     * @param rawSn      대상 영상 ID(LS_DATA_RAW.RAW_SN)
     * @param annoCnJson event_annotation payload(JSON 문자열)
     * @param regId      등록자 ID
     */
    public static LsEvntAnno create(Long rawSn, String annoCnJson, String regId) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 은 필수입니다.");
        }
        if (annoCnJson == null || annoCnJson.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "event_annotation payload 는 필수입니다.");
        }
        LsEvntAnno entity = new LsEvntAnno();
        entity.rawSn = rawSn;
        entity.annoCn = annoCnJson;
        entity.regId = regId;
        entity.regDt = LocalDateTime.now();
        return entity;
    }

    /**
     * payload 교체 + 수정 감사정보 갱신. RAW_SN 은 불변.
     *
     * @param annoCnJson 교체할 payload(JSON 문자열)
     * @param mdfcnId    수정자 ID
     */
    public void updatePayload(String annoCnJson, String mdfcnId) {
        if (annoCnJson == null || annoCnJson.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "event_annotation payload 는 필수입니다.");
        }
        this.annoCn = annoCnJson;
        this.mdfcnId = mdfcnId;
        this.mdfcnDt = LocalDateTime.now();
    }
}
