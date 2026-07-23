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
 */
public record MetaResponse(List<Item> items) {

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

    public static MetaResponse of(List<LsDataMeta> entities) {
        return new MetaResponse(entities.stream().map(Item::from).toList());
    }

    /**
     * 메타 목록 + (metaSn → 검토행) 매핑으로 응답 생성. 매핑에 없는 메타는 검토 필드 null.
     * 호출부(MetaService)가 metaSn 집합으로 검토행을 배치 조회(N+1 금지)해 매핑을 구성한다.
     */
    public static MetaResponse of(List<LsDataMeta> entities, Map<Long, LsDataMetaReview> reviewByMetaSn) {
        return new MetaResponse(entities.stream()
                .map(e -> Item.from(e, reviewByMetaSn.get(e.getMetaSn())))
                .toList());
    }
}
