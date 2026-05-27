package kr.co.cudo.authoring.marking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.dto.MarkingRequest;
import kr.co.cudo.authoring.marking.dto.MarkingResponse;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.event.MarkingCompletedEvent;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 마킹 비즈니스 로직.
 *
 * <p>Phase 2 — VLM 콜백 요청 발송은 Phase 3 에서 구현 (TODO).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class MarkingService {

    private final LsMarkingRepository markingRepository;
    private final VideoRepository videoRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 마킹 생성.
     *
     * @param rawSn 영상 PK
     * @param req   마킹 생성 요청
     * @param actor 인증된 사용자
     * @return 생성된 마킹 응답
     */
    @Transactional("controlTransactionManager")
    public MarkingResponse create(Long rawSn, MarkingRequest req, TokenClaims actor) {
        // 1. 영상 존재 확인
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));

        // 2. 마킹 모드에 따른 처리
        String marksJson;
        if ("AUTO".equals(req.mode())) {
            if (req.intervalSec() == null || req.intervalSec() <= 0) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "자동 모드에서 intervalSec 는 1 이상이어야 합니다.");
            }
            marksJson = generateAutoMarks(raw.getDurationSec(), req.intervalSec());
        } else if ("MANUAL".equals(req.mode())) {
            if (req.marks() == null || req.marks().isEmpty()) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "수동 모드에서 marks 는 필수입니다.");
            }
            marksJson = serializeMarks(req.marks());
        } else {
            throw new CustomException(ErrorCode.INVALID_INPUT, "mode 는 AUTO 또는 MANUAL 이어야 합니다.");
        }

        // 3. Entity 생성 + 저장
        Long actorNo = parseUserNo(actor.sub());
        LsMarking marking = "AUTO".equals(req.mode())
                ? LsMarking.createAuto(rawSn, req.eventName(), req.intervalSec(), raw.getFilePath(), marksJson, actorNo)
                : LsMarking.createManual(rawSn, req.eventName(), raw.getFilePath(), marksJson, actorNo);
        markingRepository.save(marking);

        log.info("[Marking] created rawSn={}, mode={}, markingSn={}", rawSn, req.mode(), marking.getMarkingSn());

        eventPublisher.publishEvent(new MarkingCompletedEvent(rawSn, marking.getMarkingSn()));

        // 4. 응답
        return MarkingResponse.from(marking, objectMapper);
    }

    /**
     * 영상별 마킹 목록 조회.
     */
    public List<MarkingResponse> list(Long rawSn) {
        videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        return markingRepository.findByRawSnOrderByCreatedAtDesc(rawSn)
                .stream().map(m -> MarkingResponse.from(m, objectMapper)).toList();
    }

    /**
     * 마킹 단건 조회. rawSn 일치 검증 (CWE-639 IDOR 방어).
     */
    public MarkingResponse get(Long rawSn, Long markingSn) {
        LsMarking marking = markingRepository.findById(markingSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "마킹을 찾을 수 없습니다."));
        if (!marking.getRawSn().equals(rawSn)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "해당 영상의 마킹이 아닙니다.");
        }
        return MarkingResponse.from(marking, objectMapper);
    }

    /**
     * 마킹 삭제. rawSn 일치 검증 (CWE-639 IDOR 방어).
     */
    @Transactional("controlTransactionManager")
    public void delete(Long rawSn, Long markingSn) {
        LsMarking marking = markingRepository.findById(markingSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "마킹을 찾을 수 없습니다."));
        if (!marking.getRawSn().equals(rawSn)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "해당 영상의 마킹이 아닙니다.");
        }
        markingRepository.delete(marking);
        log.info("[Marking] deleted rawSn={}, markingSn={}", rawSn, markingSn);
    }

    /**
     * 자동 모드: durationSec 기반 intervalSec 간격으로 marks 자동 생성.
     */
    String generateAutoMarks(Integer durationSec, int intervalSec) {
        List<MarkItem> marks = new ArrayList<>();
        int duration = (durationSec != null) ? durationSec : 0;
        // 30fps 기준으로 frameIndex 계산
        for (int sec = 0; sec <= duration; sec += intervalSec) {
            int frameIndex = sec * 30; // 30fps 기준
            String timestamp = formatTimestamp(sec);
            marks.add(new MarkItem(frameIndex, timestamp));
        }
        return serializeMarks(marks);
    }

    /**
     * marks 목록을 JSON 직렬화.
     */
    String serializeMarks(List<MarkItem> marks) {
        try {
            return objectMapper.writeValueAsString(marks);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "marks 직렬화 실패");
        }
    }

    /**
     * actor.sub() 에서 사용자 번호 파싱.
     */
    private Long parseUserNo(String sub) {
        if (sub == null || sub.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(sub);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 초 단위를 "mm:ss" 형식으로 변환.
     */
    private String formatTimestamp(int totalSec) {
        int min = totalSec / 60;
        int sec = totalSec % 60;
        return String.format("%02d:%02d", min, sec);
    }
}
