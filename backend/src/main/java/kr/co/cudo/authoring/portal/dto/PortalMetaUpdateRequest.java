package kr.co.cudo.authoring.portal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 포털 프레임 메타 저장 요청.
 *
 * <p>상한은 <b>내부 원장 창구와 같은 값</b>이다(한 번에 100 건 · 키 64자 · 값 2000자) — 같은 원장에
 * 같은 규격으로 적재되므로 입구가 달라 잘리는 지점이 갈리면 안 된다. 값 상한은 컬럼 폭과 같아
 * 초과분이 INSERT 시점 DB 오류(500)로 새지 않고 입구에서 400 이 된다.
 *
 * @design API-235
 */
public record PortalMetaUpdateRequest(
        @NotEmpty @Valid @Size(max = MAX_ITEMS, message = "한 번에 100 건까지만 저장할 수 있습니다.")
        List<Item> items
) {

    /** 한 요청의 메타 개수 상한(CWE-770) — 내부 원장 창구와 같은 값. */
    public static final int MAX_ITEMS = 100;

    /**
     * @param metaKey 메타 키
     * @param metaVl  메타 값. 비워 보내면 값 없음으로 적재된다(내부 원장과 같은 시맨틱)
     * @param scope   영상 축인지 프레임 축인지. ★이 값으로 되돌려 보내야 영상 축 값이 프레임에
     *                매달리지 않는다
     */
    public record Item(
            @NotBlank @Size(max = 64) String metaKey,
            @Size(max = 2000) String metaVl,
            @NotNull PortalMetaScope scope
    ) {
    }
}
