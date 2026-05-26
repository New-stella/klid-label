package kr.co.cudo.authoring.marking.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.marking.entity.LsMarking;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 마킹 응답 DTO.
 */
public record MarkingResponse(
        Long markingSn,
        Long rawSn,
        String eventName,
        String markingMode,
        Integer intervalSec,
        String videoPath,
        List<MarkItem> marks,
        String status,
        LocalDateTime createdAt
) {

    private static final TypeReference<List<MarkItem>> MARK_LIST_TYPE = new TypeReference<>() {};

    /**
     * Entity -> Response 변환.
     *
     * @param entity LsMarking 엔티티
     * @param mapper ObjectMapper (JSON 파싱용)
     */
    public static MarkingResponse from(LsMarking entity, ObjectMapper mapper) {
        List<MarkItem> parsed;
        try {
            parsed = mapper.readValue(entity.getMarks(), MARK_LIST_TYPE);
        } catch (Exception e) {
            parsed = List.of();
        }
        return new MarkingResponse(
                entity.getMarkingSn(),
                entity.getRawSn(),
                entity.getEventName(),
                entity.getMarkingMode(),
                entity.getIntervalSec(),
                entity.getVideoPath(),
                parsed,
                entity.getStatus(),
                entity.getCreatedAt()
        );
    }
}
