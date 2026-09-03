package kr.co.cudo.authoring.transfer.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import kr.co.cudo.authoring.transfer.dto.MarkingImportCreateRequest;

import java.time.LocalDateTime;

/**
 * 작업 행에 담아 두는 <b>사람이 지정한 값</b> — 항목마다 같은 값으로 붙는다.
 *
 * <h3>왜 행에 담는가</h3>
 * <p>적재는 요청이 끝난 <b>뒤에</b> 일어난다. 요청 본문은 그때 이미 사라졌고, 노드가 다시 떠서 남은
 * 항목을 이어 처리할 때는 <b>이 행이 그 값의 유일한 원천</b>이다. 메모리에 들고 있으면 재기동 복구가
 * 값을 잃어 이어 처리할 수 없다.
 *
 * <h3>이관 이력 식별자를 함께 담는 이유</h3>
 * <p>이관 이력은 두 갈래 공통 표시 대상이라 마킹 갈래도 <b>한 줄</b>을 남긴다. 그 줄을 작업이 끝날 때
 * 마감해야 하는데, 원장에 그 식별자를 담을 자리가 따로 없다. 새 컬럼을 만드는 대신 사람이 지정한
 * 값과 같은 자리에 담는다 — 둘 다 「이 작업을 시작할 때 정해져 끝까지 바뀌지 않는 값」이다.
 *
 * <h3>모르는 항목이 있어도 읽는다</h3>
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 다. 나중에 항목이 늘면 <b>이전에 저장된
 * 행</b>에는 그 항목이 없고, 반대로 되돌리면 새 항목이 남는다. 어느 쪽이든 읽지 못해 예외가 나면
 * 그 작업의 남은 항목이 영영 처리되지 않는다.
 *
 * @param eventTypeCd 이벤트 유형 코드 — 마킹 문서의 비고를 파싱한 값이 아니다
 * @param localGovCd  지자체 코드
 * @param cctvId      카메라 식별자
 * @param prvcTypeCd  개인정보 유형
 * @param capturedAt  촬영 시각(선택 — 지정하지 않으면 비어 있다. 대용값 금지)
 * @param historySn   이 작업이 남긴 이관 이력 줄의 식별자
 * @design DOMAIN-017
 * @design API-217
 * @design DFEAT-060
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MarkingImportJobMeta(
        String eventTypeCd,
        String localGovCd,
        String cctvId,
        String prvcTypeCd,
        LocalDateTime capturedAt,
        Long historySn) {

    public static MarkingImportJobMeta from(MarkingImportCreateRequest.Meta meta, Long historySn) {
        return new MarkingImportJobMeta(meta.eventTypeCd(), meta.localGovCd(), meta.cctvId(),
                meta.prvcTypeCd(), meta.capturedAt(), historySn);
    }
}
