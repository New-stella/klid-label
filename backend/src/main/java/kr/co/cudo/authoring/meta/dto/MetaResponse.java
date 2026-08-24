package kr.co.cudo.authoring.meta.dto;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.meta.entity.LsDataMetaReview;

import java.util.List;
import java.util.Map;

/**
 * 외부 시스템이 생성한 시계열 메타 조회 응답 (V1.7).
 * - 저작도구는 검토·수정만 제공. 메타 자동 생성 책임은 외부 시스템.
 *
 * <p>R6(Phase 6-D): 라벨링 화면에서 REVIEWER 가 시계열 메타를 승인/반려할 수 있도록,
 * 각 메타의 검토행 PK({@code dataMetaReviewSn})와 검토상태({@code reviewStatus})를 함께 반환한다.
 * 검토행이 없는 메타는 두 값 모두 null 이다.
 *
 * <h3>2026-08-03 — {@code video.*} 기술메타 분리 (계약 변경)</h3>
 * <p>{@code LS_DATA_META} 는 VLM 시계열 메타와 <b>영상 기술메타({@code video.*} 6키)</b> 를 한 테이블에
 * 담는다. 후자는 ffprobe/관제 인입이 채우고
 * {@link kr.co.cudo.authoring.video.service.VideoMetaService} 가 소유하는 값이라 "시계열 메타"가 아니다.
 * 조회가 이를 구분하지 않아 검수·라벨링 화면이 {@code video.fps} 같은 값을 시계열 메타로 렌더했다.
 *
 * <ul>
 *   <li>{@code items} — 시계열 메타만. {@code video.*} 는 <b>포함되지 않는다</b>(의도된 계약 변경).</li>
 *   <li>{@code technicalMeta} — 기술메타. 버리지 않고 별도 필드로 <b>여전히 조회된다</b>.
 *       기술메타에는 검토행({@code LS_DATA_META_REVIEW})이 없으므로 검토 필드는 항상 null 이다
 *       — 검토 대상이 아니며 화면에서도 읽기 전용 '영상 정보'로만 표시한다.</li>
 * </ul>
 *
 * <h3>2026-08-06 — 화면 전용 읽기 메타 분리 (필드 추가, @req R12)</h3>
 * <p>콜백 적재는 {@code vlm.description}(검수큐 진입·편집 가능) 하나다. {@code vlm.accuracy}(일치도,
 * 검수큐 미진입·<b>화면 전용</b>) 2키 구조다. 후자는 편집 대상이 아닌데 {@code items} 에 섞여 내려가
 * 편집·저장까지 됐다.
 *
 * <ul>
 *   <li>{@code readOnlyMeta} — <b>신설</b>. 화면 전용 읽기 메타. 값과 함께 여전히 조회되며(정보 유실 없음)
 *       화면은 읽기 전용으로만 표시한다. 수정 요청은 서버가 400 으로 거부한다.</li>
 *   <li>{@code items} — {@code vlm.accuracy} 가 <b>빠진다</b>(의도된 계약 변경). 레거시 구간 키
 *       ({@code 0-8} 등)와 {@code manual-timeseries} 는 그대로 남아 계속 편집 가능하다.</li>
 *   <li>{@code items}·{@code technicalMeta} 및 각 항목 필드의 <b>이름·타입·시맨틱은 불변</b>이다(추가만).</li>
 * </ul>
 *
 * <p>분류(어느 목록으로 갈지)는 <b>서비스 레이어가</b> 판정해 넘긴다 — {@code video.*} 는
 * {@link kr.co.cudo.authoring.video.service.VideoMetaService#isTechnicalKey}, 읽기 전용은
 * {@code MetaService} 의 술어다. 접두·키 문자열을 DTO 에 복제하면 소유자가 키를 늘릴 때 조용히 드리프트한다.
 */
public record MetaResponse(List<Item> items, List<Item> technicalMeta, List<Item> readOnlyMeta) {

    public record Item(Long metaSn, String metaKey, String metaVal,
                       Long dataMetaReviewSn, String reviewStatus) {

        /** 검토행 정보 없이 값(K/V)만 매핑. 검토 관련 필드는 null. */
        public static Item from(LsDataMeta entity) {
            return from(entity, null);
        }

        /** 값(K/V) + 검토행(nullable) 매핑. 검토행이 null 이면 검토 필드는 null. */
        public static Item from(LsDataMeta entity, LsDataMetaReview review) {
            return new Item(
                    entity.getMetaSn(),
                    entity.getMetaKey(),
                    entity.getMetaVl(),
                    review == null ? null : review.getDataMetaReviewSn(),
                    review == null ? null : review.getRvwSttsCd());
        }
    }

    /** 메타 0건인 빈 응답. 모든 필드가 null 이 아닌 빈 배열이다(FE 크래시 방지). */
    public static MetaResponse empty() {
        return new MetaResponse(List.of(), List.of(), List.of());
    }

    /**
     * 이미 분류된 세 목록 + (metaSn → 검토행) 매핑으로 응답 생성. 매핑에 없는 메타는 검토 필드 null.
     * 호출부({@code MetaService})가 metaSn 집합으로 검토행을 배치 조회(N+1 금지)해 매핑을 구성한다.
     *
     * <p><b>분류 판정은 하지 않는다</b> — 이 DTO 는 호출부가 나눠 준 목록을 담기만 한다. 접두·키 문자열을
     * 여기서 재해석하면 소유자가 키를 늘릴 때 조용히 드리프트한다.
     *
     * @param timeseries 편집 가능한 시계열 메타. 호출부가 분류해 넘긴다.
     * @param technical  {@code video.*} 기술메타. 호출부가 분류해 넘긴다.
     * @param readOnly   화면 전용 읽기 메타({@code vlm.accuracy} 등). 호출부가 분류해 넘긴다.
     */
    public static MetaResponse of(List<LsDataMeta> timeseries, List<LsDataMeta> technical,
                                  List<LsDataMeta> readOnly,
                                  Map<Long, LsDataMetaReview> reviewByMetaSn) {
        return new MetaResponse(toItems(timeseries, reviewByMetaSn), toItems(technical, reviewByMetaSn),
                toItems(readOnly, reviewByMetaSn));
    }

    private static List<Item> toItems(List<LsDataMeta> entities, Map<Long, LsDataMetaReview> reviewByMetaSn) {
        return entities.stream()
                .map(e -> Item.from(e, reviewByMetaSn.get(e.getMetaSn())))
                .toList();
    }
}
