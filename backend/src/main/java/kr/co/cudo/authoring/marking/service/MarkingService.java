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
import kr.co.cudo.authoring.video.service.VideoDurationResolver;
import kr.co.cudo.authoring.video.service.VideoFpsResolver;
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
     * 단일 fps 소스 — 자동 마킹의 30fps 고정 가정(M-3)을 실 fps 로 대체.
     * 여기서 해석한 fps 를 마킹 레코드에 pin 하여 추출단계가 재조회 없이 동일 값을 쓰게 한다(TOCTOU 제거).
     */
    private final VideoFpsResolver fpsResolver;
    /**
     * 자동 마킹용 영상 길이(초) 해석기 — durationSec 미기입 영상의 자동 마킹 실패(FIX A) 방지.
     * VDO_LEN_SEC → video.duration_ms 메타 → 직접 프로브 순으로 폴백하며, 이번 트랜잭션에서 transient
     * 하게만 사용한다(LS_DATA_RAW 되쓰기 없음).
     */
    private final VideoDurationResolver durationResolver;

    /**
     * 마킹 생성.
     *
     * <p><b>MEDIUM-1 (ffprobe 커넥션 점유 — 문서화된 한정 처리):</b> AUTO 모드의 영상 길이 해석
     * ({@link VideoDurationResolver#resolveDurationSec})은 durationSec·메타가 <b>모두</b> 없는 드문 코호트에서만
     * 최후 폴백으로 ffprobe 서브프로세스를 실행한다. 이 경로는 본 쓰기 트랜잭션 안에서 수행되어 프로브가
     * 끝날 때까지 DB 커넥션을 점유한다. 완전한 트랜잭션-외 격리(프로브를 tx 진입 전에 수행)는 이 서비스가
     * {@code @Transactional} 통합 테스트(시드 raw 를 테스트 tx 안에서 생성)에서 <b>시드 가시성 계약</b>을
     * 깨뜨리므로 채택하지 않았다(별도 tx 는 미커밋 시드를 못 본다). 대신 리스크를 다음으로 한정한다:
     * ① 프로브는 값싼 1·2단계(엔티티 필드·메타 DB read)가 모두 실패한 <b>예외 코호트</b>에서만 실행되고,
     * ② {@link kr.co.cudo.authoring.video.service.port.BrampVideoProbe} 의 <b>30초 하드 타임아웃</b>으로
     * 커넥션 점유 시간이 상한된다(무한 블로킹 없음). 적재 파이프라인이 durationSec/메타를 채우면 이 경로는
     * 아예 타지 않는다.
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

        // 1-1b. 배치 단계 가드 — 마킹은 MARKING_READY 단계 영상만 허용한다(fail-fast).
        // 이미 배치가 진행중(PROCESSING)/완료(COMPLETED)인 영상에 직접 마킹 요청이 들어오면, 마킹 완료
        // 이벤트가 배치를 재트리거해 LS_DATA_RAW.DATA_STTS_CD 가 COMPLETED→PROCESSING 으로 역전된다.
        // 생성 단계에서 거부해 역전을 원천 차단한다(MarkingBatchBridge 의 재트리거 가드와 이중 방어).
        // 정상 재처리(비식별 신고→재비식별 성공)는 영상을 다시 MARKING_READY 로 되돌리므로 본 가드를 통과한다.
        if (!LsDataRaw.DATA_STTS_MARKING_READY.equals(raw.getDataSttsCd())) {
            throw new CustomException(ErrorCode.PRECONDITION_FAILED,
                    "이미 처리된 영상은 재마킹할 수 없습니다.");
        }

        // 1-2. 이벤트명 자동 소싱 (API-047 계약 변경) — 더 이상 요청으로 받지 않고
        // 영상의 이벤트 유형(EVNT_TYPE_CD)을 그대로 사용한다. 미지정 영상은 마킹 불가.
        String eventName = raw.getEvntTypeCd();
        if (eventName == null || eventName.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "이벤트 유형이 지정되지 않은 영상은 마킹할 수 없습니다.");
        }

        // 2. 마킹 시점에 실 fps 를 확정(pin) — TOCTOU 제거의 핵심.
        //    M-3 이전에는 자동마킹과 프레임추출이 각자 다른 시점에 resolveFps 를 재조회했다. Phase 2 의
        //    video.fps 메타 적재(ingest AFTER_COMMIT @Async)가 두 시점 사이에 완료되면, 마킹은 30 폴백으로
        //    frameIndex 를, 추출은 실 fps 로 seekMillis 를 계산해 프레임이 어긋났다(TOCTOU).
        //    이제 마킹 생성 시 해석한 fps 를 마킹 레코드에 저장하고, FfmpegFrameExtractor 가 재조회 대신
        //    이 pin 값을 읽어 계산하므로 마킹↔추출이 구조적으로 동일 값을 사용한다(정합성 불변식).
        //    미상 시 30.0 폴백이라 기존 동작과 동일(무회귀). AUTO 는 marks 산출에도 이 fps 를 쓴다.
        double fps = fpsResolver.resolveFps(rawSn);

        // 3. 마킹 모드에 따른 처리
        String marksJson;
        if ("AUTO".equals(req.mode())) {
            if (req.intervalFrames() == null || req.intervalFrames() <= 0) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "자동 모드에서 intervalFrames 는 1 이상이어야 합니다.");
            }
            // durationSec(VDO_LEN_SEC) 미기입 영상은 메타(video.duration_ms)·직접 프로브로 폴백 해석한다.
            // (FIX A — 적재 시 길이가 비어 자동 마킹만 INVALID_INPUT 으로 실패하던 결함 제거. 되쓰기 없음.)
            // MEDIUM-1 — 프로브 폴백은 값싼 1·2단계 실패 시에만·30s 타임아웃으로 한정된다(위 create Javadoc).
            Integer durationSec = durationResolver.resolveDurationSec(rawSn, raw);
            marksJson = generateAutoMarks(durationSec, req.intervalFrames(), fps);
        } else if ("MANUAL".equals(req.mode())) {
            if (req.marks() == null || req.marks().isEmpty()) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "수동 모드에서 marks 는 필수입니다.");
            }
            marksJson = serializeMarks(req.marks());
        } else {
            throw new CustomException(ErrorCode.INVALID_INPUT, "mode 는 AUTO 또는 MANUAL 이어야 합니다.");
        }

        // 4. Entity 생성 + 저장 — 해석한 fps 를 마킹에 pin 하여 추출단계가 재조회 없이 동일 값을 사용하게 한다.
        Long actorNo = parseUserNo(actor.sub());
        LsMarking marking = "AUTO".equals(req.mode())
                ? LsMarking.createAuto(rawSn, eventName, req.intervalFrames(), raw.getRawFilePathNm(), marksJson, actorNo, fps)
                : LsMarking.createManual(rawSn, eventName, raw.getRawFilePathNm(), marksJson, actorNo, fps);
        markingRepository.save(marking);

        log.info("[Marking] created rawSn={}, mode={}, markingSn={}", rawSn, req.mode(), marking.getMarkingSn());

        eventPublisher.publishEvent(new MarkingCompletedEvent(rawSn, marking.getMarkingSn()));

        // 5. 응답
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
     * 자동 모드: durationSec·실 fps 기반 intervalFrames 간격으로 marks 자동 생성 (프레임 단위).
     *
     * <p><b>M-3 수정 — 실 fps 사용:</b> 과거에는 영상의 실제 FPS 를 조회하지 않고 30fps 로 고정 가정하여
     * totalFrames·타임스탬프를 계산했다. NIA export Phase 2 가 {@code LS_DATA_META.video.fps} 에 실
     * 프레임레이트를 적재하므로, 이제 {@link VideoFpsResolver#resolveFps(Long)} 가 해석한 실 fps 를 인자로
     * 받아 계산한다. fps 미상 시 resolver 가 30.0 으로 폴백하므로 기존 동작과 동일하다(무회귀).
     *
     * <p><b>반올림 정책:</b> 분수 fps(예: 29.97) 를 지원하기 위해 double 로 계산하되,
     * {@code totalFrames = Math.round(durationSec × fps)} 로 프레임 총수를 반올림한다. 타임스탬프는
     * {@code frameIndex / fps}(초)를 정수 초로 절단(mm:ss 표기)한다. 추출 단계의 seekMillis 도 동일
     * fps·동일 Math.round 정책을 쓰므로 마킹↔추출이 정합한다.
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
     * @param fps            영상 실 프레임레이트 (미상 시 호출자가 폴백값 30.0 을 전달) — 양수
     */
    String generateAutoMarks(Integer durationSec, int intervalFrames, double fps) {
        if (durationSec == null || durationSec <= 0) {
            // FIX A backstop — durationSec·메타·직접 프로브까지 모두 실패한 진짜 예외 케이스만 여기 도달한다.
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "영상 길이를 확인할 수 없어 자동 마킹을 생성할 수 없습니다.");
        }
        List<MarkItem> marks = new ArrayList<>();
        // M-3 수정 — 실 fps(video.fps, 미상 시 30.0 폴백)로 totalFrames 를 반올림 계산(분수 fps 지원).
        int totalFrames = (int) Math.round(durationSec * fps);
        for (int frameIndex = 0; frameIndex < totalFrames; frameIndex += intervalFrames) {
            double sec = frameIndex / fps;
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
