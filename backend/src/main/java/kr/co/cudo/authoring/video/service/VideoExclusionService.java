package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.ControlCharNormalizer;
import kr.co.cudo.authoring.video.dto.VideoExclusionResponse;
import kr.co.cudo.authoring.video.dto.VideoRestoreResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * <b>영상 제외·복원의 단일 쓰기 지점</b> — 상태 전이 + 감사. [@design ADR-069] [@design API-260]
 * [@design API-261] [@design UC-043]
 *
 * <h2>무엇을 하는가</h2>
 * 잘못 들어온 영상과 시험 데이터를 <b>지우지 않고</b> 저작도구 화면 목록에서만 뺀다. 행이 남아 있으므로
 * 복원이 표시를 되돌리는 것으로 끝난다. 프레임 단위 논리 폐기({@code FrameDiscardApplier})가 같은
 * 모양의 선례이며, 쓰기·판정·감사 구조를 새로 발명하지 않고 그것을 따른다.
 *
 * <h2>★ 경계 — 이것이 이 기능의 핵심이다</h2>
 * 제외는 <b>저작도구 화면 시야만</b>이다. 다음은 <b>전부 무변경</b>이며 제외된 영상도 종전과 똑같이
 * 흐른다: 배치 파이프라인(단계 진행·회수 스윕 포함) · 관제 통지 · 데이터마트 조회 뷰 · 학습데이터
 * 산출물 · 관제 조회 창구 · 통계 대시보드 · 포털 채널. <b>라벨링 상세 화면 진입도 막지 않는다</b> —
 * 주소를 직접 넣으면 제외된 영상의 상세가 열리며 <b>알고 받아들인 비대칭</b>이다(결함으로 다시 보고하지
 * 말 것).
 *
 * <p>그래서 이 서비스는 <b>이벤트를 발행하지 않고</b> 산출물·통지·해시 어느 것도 건드리지 않는다.
 * 제외 표시가 콘텐츠 해시의 입력이 되면 제외를 켰다 끄는 것만으로 산출물이 재생성되고 수정 통지가 나간다.
 *
 * <h2>멱등 — 무변경이면 이력도 남기지 않는다</h2>
 * 같은 값을 다시 보내는 것은 오류가 아니라 <b>무변경 성공</b>이다. 무변경인데도 이력이 쌓이면 감사에서
 * 같은 행위가 여러 번 있었던 것처럼 보이고, 무엇이 언제 감춰졌는지를 이력으로 되짚을 수 없게 된다.
 *
 * <h2>★ 판정과 반영 사이에 창을 두지 않는다</h2>
 * 배정 존재 조건을 사전 조회로 판정한 뒤 갱신하면 <b>그 사이에 배정이 새로 생긴다</b>(CWE-367). 조건을
 * 갱신 문장 자체에 걸고, 갱신이 한 행도 바꾸지 못했을 때 <b>그 시점 상태를 다시 읽어</b> 세 결말을
 * 가른다 — 이미 제외돼 있으면 멱등 성공 · 행이 없으면 없음 · 그 밖이면 배정 충돌 거부.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoExclusionService {

    /**
     * 사유 저장 상한 — {@code LS_TASK_EVNT_LOG.RSN} 칸 폭과 <b>같은 값</b>이다.
     *
     * <p>입구에서 자르지 않으면 INSERT 시점 DB 오류(500)가 된다. 거부가 아니라 절단인 이유는 창구 계약이
     * 정한 400 사유가 「비어 있거나 공백만」 하나이기 때문이다.
     */
    static final int REASON_MAX_LEN = 500;

    private final VideoRepository videoRepository;
    private final LsTaskEventLogRepository taskEventLogRepository;

    /**
     * 영상을 화면 목록에서 뺀다. [@design API-260] [@design AC-1124] [@design AC-1127]
     *
     * @param rawSn     대상 영상
     * @param rawReason 요청 원본 사유 — 정규화 후 비면 400
     * @param actor     인증 주체(검수자 이상 — 인가는 창구의 역할 게이트가 강제한다)
     * @throws CustomException 사유 없음 400 · 영상 없음 404 · 배정 존재 409
     */
    @Transactional
    public VideoExclusionResponse exclude(Long rawSn, String rawReason, TokenClaims actor) {
        // ① 사유 정규화가 먼저다 — 제어문자·개행을 제거해 로그 인젝션(CWE-117)과 DB 오류를 함께 막는다.
        //    제어문자만 보낸 입력은 빈 입력과 같게 다룬다(정규화 소유자의 규약).
        String reason = normalizeReason(rawReason);
        Long actorNo = actorUserNo(actor);

        // ② 조건부 원자 UPDATE — 「아직 제외가 아님」과 「배정 없음」을 갱신 문장에 함께 건다.
        //    반환 행수가 곧 "실제로 바뀌었는가"다(판정과 반영 사이에 창이 없다).
        if (videoRepository.markExcluded(rawSn, LsDataRaw.EXCL_YES, LsTaskAssignment.TASK_LABELER) == 0) {
            return unchangedOrReject(rawSn);
        }

        // ③ 값이 실제로 바뀐 경우에만 감사 이력을 남긴다 — 누가 · 그 시점 역할 · 언제 · 왜.
        LocalDateTime excludedAt = LocalDateTime.now();
        taskEventLogRepository.save(
                LsTaskEventLog.videoExcluded(rawSn, actorNo, actor.role(), reason));
        // 식별자만 출력한다 — 사유 본문은 사용자 자유 문구라 로그에 싣지 않는다(CWE-117/CWE-359).
        log.info("[VideoExclusion] excluded rawSn={} actor={}", rawSn, actorNo);
        return VideoExclusionResponse.changed(rawSn, reason, excludedAt);
    }

    /**
     * 제외했던 영상을 다시 보이게 한다. [@design API-261] [@design AC-1124]
     *
     * <p><b>배정 조건이 없는 것은 누락이 아니라 의도</b>다 — 배정이 있는 영상은 제외 자체가 거부되므로
     * 제외된 영상에는 배정이 존재할 수 없고 그 조건이 성립할 자리가 없다.
     *
     * <p>사유를 받지 않는다 — 감추는 쪽만 사유를 남긴다.
     *
     * @throws CustomException 영상 없음 404
     */
    @Transactional
    public VideoRestoreResponse restore(Long rawSn, TokenClaims actor) {
        Long actorNo = actorUserNo(actor);
        if (videoRepository.markRestored(rawSn, LsDataRaw.EXCL_NO) == 0) {
            // 0행의 원인은 둘뿐이다 — 이미 보이는 영상(멱등)이거나 행이 없거나.
            requireExists(rawSn);
            return VideoRestoreResponse.of(rawSn, false);
        }
        taskEventLogRepository.save(LsTaskEventLog.videoRestored(rawSn, actorNo, actor.role()));
        log.info("[VideoExclusion] restored rawSn={} actor={}", rawSn, actorNo);
        return VideoRestoreResponse.of(rawSn, true);
    }

    /**
     * 제외 갱신이 0행일 때 세 결말을 가른다 — <b>같은 지점에서 갈리므로 한쪽만 시험하면 반대쪽이 조용히
     * 뒤집혀도 통과한다</b>.
     *
     * <ul>
     *   <li>이미 제외됨 → <b>멱등 무변경 성공</b>(이력 없음)</li>
     *   <li>행 없음 → 404</li>
     *   <li>그 밖 → 배정 존재 409. 배정은 풀 수 있으므로 <b>일시 조건</b>이지 영구 거부가 아니며,
     *       안내가 배정 해제 창구를 가리켜 막다른 길이 되지 않게 한다</li>
     * </ul>
     */
    private VideoExclusionResponse unchangedOrReject(Long rawSn) {
        String current = requireExists(rawSn);
        if (LsDataRaw.EXCL_YES.equals(current)) {
            return VideoExclusionResponse.unchanged(rawSn);
        }
        throw new CustomException(ErrorCode.CONFLICT,
                "배정된 영상은 제외할 수 없습니다. 먼저 배정을 해제하세요.");
    }

    /** 행 실재 확인 — 빈 값이 곧 행 부재다(컬럼이 {@code NOT NULL} 이라 값이 비지 않는다). */
    private String requireExists(Long rawSn) {
        Optional<String> current = videoRepository.findExclYnByRawSn(rawSn);
        return current.orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
    }

    /**
     * 사유 정규화 — 개행·제어문자 제거 후 앞뒤 공백 제거, 그다음 칸 폭으로 절단.
     *
     * <p>규칙의 단일 원천은 {@link ControlCharNormalizer} 다(여기서 문자 판정을 다시 적지 않는다).
     * 남는 게 없으면 <b>400</b> — 「왜 뺐는가」가 이 창구의 확정 요구다.
     */
    private String normalizeReason(String rawReason) {
        String normalized = ControlCharNormalizer.normalizeOrNull(rawReason);
        if (normalized == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "제외 사유를 입력해 주세요.");
        }
        return normalized.length() > REASON_MAX_LEN
                ? normalized.substring(0, REASON_MAX_LEN) : normalized;
    }

    /**
     * 행위자 사용자 번호 — <b>인증 주체에서만</b> 나온다. 비숫자 subject 는 값을 지어내지 않고 401 이다
     * (fail-closed — {@code VideoQueryService.parseUserNo} 와 같은 규약).
     */
    private Long actorUserNo(TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        try {
            return Long.parseLong(actor.sub());
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "토큰 subject 형식이 올바르지 않습니다.");
        }
    }
}
