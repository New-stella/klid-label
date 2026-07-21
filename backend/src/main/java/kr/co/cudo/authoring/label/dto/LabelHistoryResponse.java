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
 *   <li>{@code actor}      : 작업자 식별자(REG_ID). nullable(신고 삭제 경로는 미기록).</li>
 *   <li>{@code addCnt}     : 추가(ADDED) 라벨 건수.</li>
 *   <li>{@code mdfcnCnt}   : 수정(UPDATED) 라벨 건수.</li>
 *   <li>{@code delCnt}     : 삭제(DELETED) 라벨 건수.</li>
 *   <li>{@code changes}    : 변경 상세(종류 + before/after 스냅샷) 목록.</li>
 * </ul>
 *
 * 보안(CWE-209): 내부 경로/스택트레이스/토큰 등 기술·민감정보를 포함하지 않는다.
 */
public record LabelHistoryResponse(
        Long lblHstrySn,
        Long srcSn,
        LocalDateTime regDt,
        String actor,
        Integer addCnt,
        Integer mdfcnCnt,
        Integer delCnt,
        List<LabelChangeView> changes
) {

    private static final Logger log = LoggerFactory.getLogger(LabelHistoryResponse.class);

    /**
     * 저장 이벤트 행 → 응답 매핑. {@code CHG_DTL_CN}(diff JSON)을 명시 타입으로만 역직렬화한다(CWE-502).
     *
     * <p>HIGH #9 — 손상/구포맷 diff 는 레코드 단위로 격리한다: 파싱 실패 시 빈 changes 로 폴백하고
     * {@code log.warn}(스택트레이스/내부경로 미노출, CWE-209) 만 남긴다. 단일 손상 레코드가 페이지 전체를
     * 500 으로 만들지 않도록 한다.
     */
    public static LabelHistoryResponse from(LsDataLblHstry h) {
        List<LabelChangeView> views;
        try {
            views = LabelHistoryDiffSerializer.deserialize(h.getChgDtlCn()).stream()
                    .map(LabelChangeView::from)
                    .toList();
        } catch (RuntimeException e) {
            log.warn("[LabelHistory] diff 파싱 실패 — 빈 changes 로 폴백 lblHstrySn={}", h.getLblHstrySn());
            views = List.of();
        }
        return new LabelHistoryResponse(
                h.getLblHstrySn(),
                h.getSrcSn(),
                h.getRegDt(),
                h.getRegId(),
                h.getAddCnt(),
                h.getMdfcnCnt(),
                h.getDelCnt(),
                views);
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
