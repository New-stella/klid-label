package kr.co.cudo.authoring.version.dto;

import kr.co.cudo.authoring.version.entity.LsLabelVersion;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 프레임 단위 버전(커밋) 목록 응답.
 */
public record VersionResponse(List<Item> items) {

    public record Item(
            Long lblHstrySn,
            Long srcSn,
            String giteaCmtHash,
            String registeredUserNo,
            LocalDateTime registeredAt
    ) {
        public static Item from(LsLabelVersion e) {
            return new Item(
                    e.getLabelVersionSn(),
                    e.getDataSrcSn(),
                    e.getGiteaCmtHash(),
                    e.getRegId(),
                    e.getRegDt()
            );
        }
    }

    public static VersionResponse of(List<LsLabelVersion> entities) {
        return new VersionResponse(entities.stream().map(Item::from).toList());
    }
}
