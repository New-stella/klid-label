package kr.co.cudo.authoring.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 분류 대응 목록.
 *
 * <h3>왜 페이지 정보를 함께 싣는가</h3>
 * <p>대응 표는 산출물을 가져올수록 단조 증가한다. 전체를 한 번에 돌려주면 표가 커진 뒤에 응답이
 * 무거워지고 되돌릴 방법도 없다(CWE-770). 항목 배열의 이름과 모양은 계약 그대로 두고, 페이지 정보만
 * 나란히 더한다 — 기존 소비자는 {@code items} 만 읽으면 되고 새 소비자는 남은 건수를 알 수 있다.
 *
 * @design API-209
 */
@Schema(description = "분류 대응 목록")
public record ImportMappingListResponse(
        List<ImportMappingResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static ImportMappingListResponse of(Page<ImportMappingResponse> page) {
        return new ImportMappingListResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
