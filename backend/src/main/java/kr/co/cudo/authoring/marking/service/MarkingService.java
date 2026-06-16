package kr.co.cudo.authoring.marking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
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

    /** 비식별 완료 마킹 값 (LS_DATA_RAW.DE_IDENT_YN). */
    private static final String DEIDENTIFIED = "Y";

    private final LsMarkingRepository markingRepository;
    private final VideoRepository videoRepository;
    private final LsTaskAssignmentRepository assignmentRepository;
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
        // 0. 본인 배정 검증 (CWE-639 수평 권한 상승 차단) — WORKER 는 본인 LABELER 배정 영상만.
        requireAssignedOrReviewer(rawSn, actor);

        // 1. 영상 존재 확인
        LsDataRaw raw = videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));

        // 1-1. 비식별 완료 가드 — "마킹은 비식별 완료(deIdntfYn='Y') 영상 대상" (CLAUDE.md).
        // 마킹은 비식별 영상에서 수행하므로 미완료 영상은 거부한다. (MarkingBatchBridge 배치 진입
        // 가드와 일관성 보강 — 생성 단계에서도 동일 규칙 강제.)
        if (!DEIDENTIFIED.equals(raw.getDeIdntfYn())) {
            throw new CustomException(ErrorCode.PRECONDITION_FAILED,
                    "비식별이 완료된 영상에서만 마킹할 수 있습니다.");
        }

        // 2. 마킹 모드에 따른 처리
        String marksJson;
        if ("AUTO".equals(req.mode())) {
            if (req.intervalFrames() == null || req.intervalFrames() <= 0) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "자동 모드에서 intervalFrames 는 1 이상이어야 합니다.");
            }
            marksJson = generateAutoMarks(raw.getDurationSec(), req.intervalFrames());
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
                ? LsMarking.createAuto(rawSn, req.eventName(), req.intervalFrames(), raw.getRawFilePathNm(), marksJson, actorNo)
                : LsMarking.createManual(rawSn, req.eventName(), raw.getRawFilePathNm(), marksJson, actorNo);
        markingRepository.save(marking);

        log.info("[Marking] created rawSn={}, mode={}, markingSn={}", rawSn, req.mode(), marking.getMarkingSn());

        eventPublisher.publishEvent(new MarkingCompletedEvent(rawSn, marking.getMarkingSn()));

        // 4. 응답
        return MarkingResponse.from(marking, objectMapper);
    }

    /**
     * 영상별 마킹 목록 조회. 본인 배정 검증 (CWE-639 수평 권한 상승 차단).
     */
    public List<MarkingResponse> list(Long rawSn, TokenClaims actor) {
        requireAssignedOrReviewer(rawSn, actor);
        videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        return markingRepository.findByRawSnOrderByRegDtDesc(rawSn)
                .stream().map(m -> MarkingResponse.from(m, objectMapper)).toList();
    }

    /**
     * 마킹 단건 조회. 본인 배정 검증(CWE-639 수평 권한 상승) + rawSn 일치 검증(IDOR).
     */
    public MarkingResponse get(Long rawSn, Long markingSn, TokenClaims actor) {
        requireAssignedOrReviewer(rawSn, actor);
        LsMarking marking = markingRepository.findById(markingSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "마킹을 찾을 수 없습니다."));
        if (!marking.getRawSn().equals(rawSn)) {
            throw new CustomException(ErrorCode.FORBIDDEN, "해당 영상의 마킹이 아닙니다.");
        }
        return MarkingResponse.from(marking, objectMapper);
    }

    /**
     * 영상 단위 접근 가드 (CWE-639 수평 권한 상승 차단).
     *
     * <p>REVIEWER 는 전체 허용. WORKER 는 본인이 LABELER 로 배정된 rawSn 만 허용한다.
     * 배정 여부는 LS_TASK_ASSIGNMENT(TASK_TYPE_CD='LABELER') 존재로 판정한다.
     *
     * @param rawSn 영상 PK
     * @param actor 인증된 사용자 (null 이면 401)
     */
    private void requireAssignedOrReviewer(Long rawSn, TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        if (actor.role() == Role.REVIEWER) {
            return;
        }
        Long userNo = parseUserNo(actor.sub());
        boolean assigned = userNo != null && assignmentRepository
                .existsByUserNoAndTaskTypeCdAndRawDataId(userNo, LsTaskAssignment.TASK_LABELER, rawSn);
        if (!assigned) {
            throw new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정된 영상의 마킹만 접근할 수 있습니다.");
        }
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

    /** 네이티브 FPS 상수 (30fps). */
    private static final int NATIVE_FPS = 30;

    /**
     * 자동 모드: durationSec 기반 intervalFrames 간격으로 marks 자동 생성 (프레임 단위).
     *
     * @param durationSec    영상 길이 (초)
     * @param intervalFrames 프레임 간격 (1 이상)
     */
    String generateAutoMarks(Integer durationSec, int intervalFrames) {
        List<MarkItem> marks = new ArrayList<>();
        int totalFrames = (durationSec != null ? durationSec : 0) * NATIVE_FPS;
        for (int frameIndex = 0; frameIndex <= totalFrames; frameIndex += intervalFrames) {
            double sec = frameIndex / (double) NATIVE_FPS;
            String timestamp = formatTimestamp((int) sec);
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
