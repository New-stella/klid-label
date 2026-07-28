package kr.co.cudo.authoring.marking.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.marking.entity.LsMarking;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 마킹 응답 DTO.
 *
 * <p><b>batchTriggered / batchSkipReason (DEV_FIX H11)</b>: 마킹 저장은 성공(201)했지만 후속 배치가
 * 시작되지 않은 경우가 있다(비식별 미완료·이미 처리된 영상·검수 소유 작업 상태·중복 큐잉). 과거에는
 * 서버 로그에만 남아 사용자는 "제출됐고 배치가 시작됐다"고 오인했다. 이제 트리거 결과와 사유를 응답에
 * 실어 무음 스킵을 없앤다. 두 필드는 <b>선택</b>이며 브리지가 실행되지 않은 경로에서는 {@code null}
 * (판정 불가)이다 — 기존 소비자는 무시해도 동작이 바뀌지 않는다(하위호환).
 */
public record MarkingResponse(
        Long markingSn,
        Long rawSn,
        String eventName,
        String markingMode,
        Integer intervalFrames,
        String videoPath,
        List<MarkItem> marks,
        String status,
        LocalDateTime createdAt,
        Boolean batchTriggered,
        String batchSkipReason
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
            parsed = mapper.readValue(entity.getMarkCn(), MARK_LIST_TYPE);
        } catch (Exception e) {
            parsed = List.of();
        }
        return new MarkingResponse(
                entity.getMarkingSn(),
                entity.getRawSn(),
                entity.getEvntNm(),
                entity.getMarkModeCd(),
                entity.getFrmeIntvNocs(),
                entity.getVideoFilePathNm(),
                parsed,
                entity.getSttsCd(),
                entity.getRegDt(),
                null,
                null
        );
    }

    /**
     * 배치 트리거 결과를 덧입힌 사본을 만든다 (DEV_FIX H11).
     *
     * @param triggered 배치가 실제로 시작됐는지. {@code null} = 판정 불가(브리지 미실행)
     * @param skipReason 미시작 사유(고정 문구). 시작됐으면 {@code null}
     */
    public MarkingResponse withBatchOutcome(Boolean triggered, String skipReason) {
        return new MarkingResponse(markingSn, rawSn, eventName, markingMode, intervalFrames,
                videoPath, marks, status, createdAt, triggered, skipReason);
    }
}
