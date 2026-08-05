package kr.co.cudo.authoring.version.dto;

import kr.co.cudo.authoring.version.entity.LsLabelVersion;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 프레임 단위 버전(커밋) 목록 응답.
 */
public record VersionResponse(List<Item> items) {

    /**
     * 버전 1건.
     *
     * <ul>
     *   <li>{@code registeredUserNo} : 수행자 <b>사번</b>({@code REG_ID}). <b>하위호환 — 계속 사번을 담는다</b>
     *       (이름으로 바꿔치지 않는다. FE 폴백 원값).</li>
     *   <li>{@code registeredUserName} : 수행자 <b>표시명</b>({@code LS_ACNT_USER.USER_NM}). 비숫자 사번·
     *       마스터 미존재·사번 null 이면 {@code null} 이며, 그때 화면은 사번으로 폴백한다.</li>
     * </ul>
     */
    public record Item(
            Long lblHstrySn,
            Long srcSn,
            String versionHash,
            String registeredUserNo,
            String registeredUserName,
            LocalDateTime registeredAt
    ) {
        /**
         * ⚠ {@code registeredUserName} 이 <b>항상 null</b> 인 오버로드(표시명 미해석). 화면에 수행자를
         * 노출하는 경로에서는 {@link #from(LsLabelVersion, String)} 에 해석 결과를 넘길 것 —
         * 그러지 않으면 사번이 그대로 찍히던 결함이 되살아난다.
         */
        public static Item from(LsLabelVersion e) {
            return from(e, null);
        }

        /** 표시명은 호출부가 해석해 넘긴다 — 해석 실패는 {@code null}(조회는 계속 200). */
        public static Item from(LsLabelVersion e, String registeredUserName) {
            return new Item(
                    e.getLabelVersionSn(),
                    e.getDataSrcSn(),
                    e.getVersionHash(),
                    e.getRegId(),
                    registeredUserName,
                    e.getRegDt()
            );
        }
    }

    public static VersionResponse of(List<LsLabelVersion> entities) {
        return new VersionResponse(entities.stream().map(Item::from).toList());
    }
}
