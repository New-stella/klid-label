package kr.co.cudo.authoring.label.dto;

import kr.co.cudo.authoring.version.entity.LabelChange;
import kr.co.cudo.authoring.version.entity.LabelSnapshot;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import kr.co.cudo.authoring.version.util.LabelHistoryDiffSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 프레임 단위 라벨 <b>저장 이벤트</b> 이력 응답 (GET /v1/frames/{srcSn}/label-history).
 *
 * <p>V114 재구조화: "라벨 1건=1행" → "저장 이벤트=1행". 한 번의 저장/삭제 행위를 1건으로 노출하고
 * 종류별 건수(add/mdfcn/del) + before→after 변경 상세(diff)를 담는다.
 *
 * <ul>
 *   <li>{@code lblHstrySn} : 이력 PK (2차 정렬 tiebreaker 노출).</li>
 *   <li>{@code srcSn}      : 저장 이벤트가 발생한 프레임 SRC_SN.</li>
 *   <li>{@code regDt}      : 변경 일시.</li>
 *   <li>{@code actor}      : 작업자 <b>식별자(사번, REG_ID)</b>. nullable(시스템 이력 행은 미기록).
 *       <b>하위호환을 위해 계속 사번을 담는다</b> — 이름으로 바꿔치지 않는다(FE 폴백 원값).</li>
 *   <li>{@code actorName}  : 작업자 <b>표시명</b>({@code LS_ACNT_USER.USER_NM}). 비숫자 사번·마스터
 *       미존재·사번 null 이면 {@code null} 이며, 그때 화면은 {@code actor}(사번)로 폴백한다.</li>
 *   <li>{@code addCnt}     : 추가(ADDED) 라벨 건수.</li>
 *   <li>{@code mdfcnCnt}   : 수정(UPDATED) 라벨 건수.</li>
 *   <li>{@code delCnt}     : 삭제(DELETED) 라벨 건수.</li>
 *   <li>{@code changes}    : 변경 상세(종류 + before/after 스냅샷) 목록.</li>
 *   <li>{@code rollbackToVersionHash} : 이 이벤트가 <b>버전 롤백</b>이면 되돌린 대상 버전 해시(D-ISSUE-21).
 *       일반 저장 이벤트는 null.</li>
 * </ul>
 *
 * 보안(CWE-209): 내부 경로/스택트레이스/토큰 등 기술·민감정보를 포함하지 않는다.
 */
public record LabelHistoryResponse(
        Long lblHstrySn,
        Long srcSn,
        LocalDateTime regDt,
        String actor,
        String actorName,
        Integer addCnt,
        Integer mdfcnCnt,
        Integer delCnt,
        List<LabelChangeView> changes,
        String rollbackToVersionHash
) {

    private static final Logger log = LoggerFactory.getLogger(LabelHistoryResponse.class);

    /**
     * 저장 이벤트 행 → 응답 매핑. {@code CHG_DTL_CN}(diff JSON)을 명시 타입으로만 역직렬화한다(CWE-502).
     *
     * <p>HIGH #9 — 손상/구포맷 diff 는 레코드 단위로 격리한다: 파싱 실패 시 빈 changes 로 폴백하고
     * {@code log.warn}(스택트레이스/내부경로 미노출, CWE-209) 만 남긴다. 단일 손상 레코드가 페이지 전체를
     * 500 으로 만들지 않도록 한다.
     *
     * <p>⚠ 이 1-인자 오버로드는 {@code actorName} 이 <b>항상 null</b> 이다(표시명 미해석). 화면에 작성자를
     * 노출하는 경로에서는 쓰지 말고 {@link #from(LsDataLblHstry, String)} 에 배치 해석 결과를 넘길 것 —
     * 그러지 않으면 사번이 그대로 찍히던 결함이 되살아난다.
     */
    public static LabelHistoryResponse from(LsDataLblHstry h) {
        return from(h, null);
    }

    /**
     * 저장 이벤트 행 + <b>해석된 작성자 표시명</b> → 응답 매핑.
     *
     * <p>{@code actorName} 은 호출부(서비스)가 페이지 단위 <b>배치 조회 1회</b>로 해석해 넘긴다
     * (N+1 금지). 해석 실패는 {@code null} 이며 {@code actor}(사번)는 언제나 원값 그대로 유지된다.
     */
    public static LabelHistoryResponse from(LsDataLblHstry h, String actorName) {
        List<LabelChangeView> views;
        String rollbackTo;
        try {
            views = LabelHistoryDiffSerializer.deserialize(h.getChgDtlCn()).stream()
                    .map(LabelChangeView::from)
                    .toList();
            rollbackTo = LabelHistoryDiffSerializer.readRollbackTargetHash(h.getChgDtlCn());
        } catch (RuntimeException e) {
            log.warn("[LabelHistory] diff 파싱 실패 — 빈 changes 로 폴백 lblHstrySn={}", h.getLblHstrySn());
            views = List.of();
            rollbackTo = null;
        }
        return new LabelHistoryResponse(
                h.getLblHstrySn(),
                h.getSrcSn(),
                h.getRegDt(),
                h.getRegId(),
                actorName,
                h.getAddCnt(),
                h.getMdfcnCnt(),
                h.getDelCnt(),
                views,
                rollbackTo);
    }

    /** 변경 1건 — 종류(ADDED/UPDATED/DELETED) + before/after 스냅샷. */
    public record LabelChangeView(
            Long lblSn,
            String changeKind,
            String labelName,
            LabelSnapshotView before,
            LabelSnapshotView after
    ) {
        public static LabelChangeView from(LabelChange c) {
            return new LabelChangeView(
                    c.lblSn(),
                    c.kind() == null ? null : c.kind().name(),
                    c.labelName(),
                    LabelSnapshotView.from(c.before()),
                    LabelSnapshotView.from(c.after()));
        }
    }

    /** 라벨 1건의 변경 대상 스냅샷 값. */
    public record LabelSnapshotView(
            String lblTypeCd,
            Long labelId,
            String labelNm,
            String pointCn
    ) {
        public static LabelSnapshotView from(LabelSnapshot s) {
            return s == null ? null
                    : new LabelSnapshotView(s.lblTypeCd(), s.labelId(), s.labelNm(), s.pointCn());
        }
    }
}
