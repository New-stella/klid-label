package kr.co.cudo.authoring.portal.dto;

import java.util.List;

/**
 * 포털 프레임 메타 Load·저장 응답 — 원본과 본인 오버레이를 <b>병합</b>한 결과.
 *
 * <p>담는 축은 다섯이다 — 촬영환경 · 프레임 설명 · 개인정보 판정 · 시계열 메타(이벤트 어노테이션은
 * 구조체라 별도 창구). 그 가운데 앞 셋은 원장의 <b>컬럼</b>에서 조달해 키/값으로 바꿔 내리므로
 * 값이 있다고 해서 사람이 고른 값이라는 뜻이 아니다 — 그 구분은 {@link Item#source()} 가 알려 준다.
 *
 * <p>세 목록의 배분은 {@code PortalMetaKeyPolicy} 가 정한다. 화면이 키 접두를 파싱해 스스로 가르지
 * 않는다 — 분류의 소유자는 서버다.
 *
 * @param rawSn         영상 PK
 * @param srcSn         프레임 PK
 * @param items         확인·수정·추가할 수 있는 메타
 * @param readOnlyMeta  표시만 하고 고칠 수 없는 메타
 * @param technicalMeta 영상 기술 메타. 사람이 고치는 값이 아니다
 * @design API-234
 * @design API-235
 */
public record PortalMetaResponse(Long rawSn, Long srcSn,
                                 List<Item> items,
                                 List<Item> readOnlyMeta,
                                 List<Item> technicalMeta) {

    /**
     * @param metaKey    메타 키. 내부 원장과 같은 키 규격
     * @param metaVl     메타 값
     * @param scope      영상 축인지 프레임 축인지
     * @param overridden 본인 오버레이가 <b>원본을 가린</b> 값인지. 본인이 올린 자산은 가려야 할
     *                   남의 원본이 없어 <b>항상 거짓</b>이고, 원본에 없던 키를 새로 추가한 경우도
     *                   가린 것이 없으므로 거짓이다
     * @param source     <b>원본 쪽</b> 값의 출처. {@code overridden} 과 다른 축이다 —
     *                   {@code overridden} 은 내 값이 덮었는가이고 이 값은 덮인 쪽이 무엇이었는가다.
     *                   둘을 합치면 「자동으로 채워져 있던 값을 사람이 바꿨다」를 표현하지 못한다
     */
    public record Item(String metaKey, String metaVl, PortalMetaScope scope, boolean overridden,
                       PortalMetaSource source) {
    }
}
