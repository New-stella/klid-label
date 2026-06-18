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

        // 1-2. 이벤트명 자동 소싱 (API-047 계약 변경) — 더 이상 요청으로 받지 않고
        // 영상의 이벤트 유형(EVNT_TYPE_CD)을 그대로 사용한다. 미지정 영상은 마킹 불가.
        String eventName = raw.getEvntTypeCd();
        if (eventName == null || eventName.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "이벤트 유형이 지정되지 않은 영상은 마킹할 수 없습니다.");
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
                ? LsMarking.createAuto(rawSn, eventName, req.intervalFrames(), raw.getRawFilePathNm(), marksJson, actorNo)
                : LsMarking.createManual(rawSn, eventName, raw.getRawFilePathNm(), marksJson, actorNo);
        markingRepository.save(marking);

        log.info("[Marking] created rawSn={}, mode={}, markingSn={}", rawSn, req.mode(), marking.getMarkingSn());

        eventPublisher.publishEvent(new MarkingCompletedEvent(rawSn, marking.getMarkingSn()));

        // 4. 응답
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
     * 자동 마킹의 네이티브 FPS 가정값 (30fps).
     *
     * <p><b>M-3 — 30fps 고정 가정의 제약(중요):</b> 자동 마킹은 영상의 실제 FPS 를 조회하지 않고 30fps 로
     * 가정하여 totalFrames(=durationSec×30)와 프레임 인덱스→타임스탬프(frameIndex/30)를 계산한다.
     * {@code LS_DATA_RAW} 에는 FPS 컬럼이 없어 영상별 실 FPS 를 알 수 없기 때문이다. 따라서 30fps 가 아닌
     * 영상(예: 25/60fps)은 자동 마킹의 마크 프레임 번호·타임스탬프가 실제 재생 위치와 어긋날 수 있다.
     *
     * <p>FPS 필드 신설은 LS_DATA_RAW 스키마/Flyway 마이그레이션 + 적재 파이프라인(메타 추출) 전반에
     * 파급이 커서 본 후속 개선 범위에 포함하지 않는다. 현 단계에서는 가정을 명시(Javadoc + Swagger 설명)하고,
     * 정확한 프레임 정렬이 필요하면 수동 모드(MANUAL)를 사용하도록 안내한다.
     */
    static final int NATIVE_FPS = 30;

    /**
     * 자동 모드: durationSec 기반 intervalFrames 간격으로 marks 자동 생성 (프레임 단위).
     *
     * <p><b>30fps 고정 가정(M-3):</b> 이 메서드는 영상의 실제 FPS 를 사용하지 않고 {@link #NATIVE_FPS}(30fps)
     * 로 가정하여 totalFrames(=durationSec×30)와 각 프레임의 타임스탬프(frameIndex/30초)를 계산한다.
     * {@code LS_DATA_RAW} 에 FPS 정보가 없기 때문이며, 30fps 가 아닌 영상에서는 마크 프레임/타임스탬프가
     * 실제와 어긋날 수 있다. 정확한 프레임 정렬이 필요하면 수동 모드를 사용한다. (상세 사유는 {@link #NATIVE_FPS}.)
     *
     * <p>경계/널 처리:
     * <ul>
     *   <li>{@code durationSec} 이 null 이거나 0 이하면 영상 길이를 알 수 없어 자동 마킹을 생성할 수 없으므로
     *       {@link ErrorCode#INVALID_INPUT} 로 거부한다(과거: totalFrames=0 으로 frame 0 단 1건만 생성되는 퇴화 방지).</li>
     *   <li>루프 상한은 {@code frameIndex < totalFrames} — totalFrames(예: 30fps·10초=300)는 존재하지 않는
     *       끝 경계 프레임이므로 포함하지 않는다(off-by-one 수정).</li>
     * </ul>
     *
     * @param durationSec    영상 길이 (초) — null/0 이하면 거부
     * @param intervalFrames 프레임 간격 (1 이상)
     */
    String generateAutoMarks(Integer durationSec, int intervalFrames) {
        if (durationSec == null || durationSec <= 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "자동 마킹은 영상 길이(durationSec)가 1초 이상이어야 합니다.");
        }
        List<MarkItem> marks = new ArrayList<>();
        // M-3 — 영상 실 FPS 를 알 수 없어 30fps 가정. 비-30fps 영상은 마크 프레임/타임스탬프가 어긋날 수 있다.
        int totalFrames = durationSec * NATIVE_FPS;
        for (int frameIndex = 0; frameIndex < totalFrames; frameIndex += intervalFrames) {
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
