package kr.co.cudo.authoring.meta.dto;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;

import java.util.List;

/**
 * 외부 시스템이 생성한 시계열 메타 조회 응답 (V1.7).
 * - 저작도구는 검토·수정만 제공. 메타 자동 생성 책임은 외부 시스템.
 */
public record MetaResponse(List<Item> items) {

    public record Item(Long metaSn, String metaKey, String metaVal) {
        public static Item from(LsDataMeta entity) {
            return new Item(entity.getMetaSn(), entity.getMetaKey(), entity.getMetaVal());
        }
    }

    public static MetaResponse of(List<LsDataMeta> entities) {
        return new MetaResponse(entities.stream().map(Item::from).toList());
    }
}
