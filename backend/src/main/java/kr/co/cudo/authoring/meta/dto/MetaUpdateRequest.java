package kr.co.cudo.authoring.meta.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 메타 수정 요청 (V1.7).
 * - 저작도구는 외부 생성 메타의 값만 수정 가능 (key 추가/삭제는 제공하지 않음).
 * - DoS 방어: 한 번에 100 건까지.
 */
public record MetaUpdateRequest(
        @NotEmpty @Valid @Size(max = 100, message = "한 번에 100 건까지만 수정 가능") List<Item> items
) {
    public record Item(
            @NotBlank @Size(max = 64) String metaKey,
            @Size(max = 2000) String metaVal
    ) {
    }
}
