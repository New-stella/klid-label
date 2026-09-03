package kr.co.cudo.authoring.portal.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.entity.LsMarking;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 포털 업로드 영상 마킹 조회 응답. [design: API-241]
 *
 * <p>저장된 마킹이 없으면 <b>빈 목록</b>으로 답한다 — 없다는 것도 화면이 알아야 하는 사실이라
 * 오류로 돌려주지 않는다. 마킹이라는 개념이 없는 자산 종류도 같은 빈 목록이다(조회는 아무것도
 * 바꾸지 않으므로 「없다」가 참인 답이며, 여기서 종류를 따지면 화면이 같은 자리에서 두 갈래로 갈린다).
 *
 * @param markings 저장 시각 내림차순
 */
public record PortalMarkingListResponse(Long uldSn, List<Item> markings) {

    private static final TypeReference<List<MarkItem>> MARK_LIST_TYPE = new TypeReference<>() {};

    /**
     * 마킹 한 건.
     *
     * @param interval 자동 방식일 때 쓴 간격(프레임 수). 수동이면 비어 있다
     */
    public record Item(
            Long markingSn,
            String mode,
            Integer interval,
            List<MarkItem> marks,
            int markCount,
            LocalDateTime regDt
    ) {
    }

    public static PortalMarkingListResponse of(Long uldSn, List<LsMarking> rows, ObjectMapper mapper) {
        return new PortalMarkingListResponse(uldSn, rows.stream()
                .map(row -> toItem(row, mapper))
                .toList());
    }

    private static Item toItem(LsMarking row, ObjectMapper mapper) {
        List<MarkItem> marks;
        try {
            marks = mapper.readValue(row.getMarkCn(), MARK_LIST_TYPE);
        } catch (Exception e) {
            // 본문이 깨진 과거 행은 조회를 통째로 실패시키지 않는다 — 화면이 「무엇이 저장돼 있나」를
            // 확인하는 자리이고, 한 건의 파손이 나머지를 가리면 안 된다.
            marks = List.of();
        }
        return new Item(row.getMarkingSn(), row.getMarkModeCd(), row.getFrmeIntvNocs(),
                marks, marks.size(), row.getRegDt());
    }
}
