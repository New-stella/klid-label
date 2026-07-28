package kr.co.cudo.authoring.marking.service;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;

/**
 * 마킹 생성의 <b>인가·프리컨디션 공용 가드</b> (HIGH/MEDIUM 수정 — 사전체크와 persist 이중 방어 동일 규칙).
 *
 * <p><b>배경:</b> 마킹 생성은 두 지점에서 동일한 인가·프리컨디션을 적용해야 한다.
 * <ol>
 *   <li><b>프로브 이전 사전 확인</b>({@link MarkingPrecheckReader}) — 고비용 ffprobe 서브프로세스가
 *       <b>인가·프리컨디션을 통과한 뒤에만</b> 실행되도록, 값싼 readonly read 로 먼저 거부한다(CWE-862/400,
 *       OWASP API4 — 미배정 WORKER 가 403 이전에 임의 rawSn 프로브를 트리거하는 리소스 소모 표면 차단).</li>
 *   <li><b>persist 트랜잭션 내부 재확인</b>({@link MarkingService} 쓰기 트랜잭션) — 트랜잭션 원자
 *       source-of-truth 로서의 방어적 이중화(defense-in-depth). 사전확인 후 persist 사이에 상태가 바뀌어도
 *       최종 쓰기 시점 규칙을 강제한다.</li>
 * </ol>
 * 두 지점이 동일 규칙·<b>동일 평가 순서·동일 상태코드/메시지</b>를 쓰도록 그 로직을 이 헬퍼로 단일화한다.
 * (계약 불변식 — 평가 순서와 예외가 어느 지점에서도 달라지지 않는다.)
 *
 * <p><b>평가 순서(불변):</b> ① 인가(UNAUTHORIZED/FORBIDDEN) → ② 영상 존재(NOT_FOUND) →
 * ③ 비식별 완료(PRECONDITION_FAILED) → ④ MARKING_READY(PRECONDITION_FAILED) → ⑤ 이벤트 유형 존재(INVALID_INPUT)
 * → ⑥ 활성 마킹 중복(CONFLICT).
 */
final class MarkingGuards {

    /** 비식별 완료 마킹 값 (LS_DATA_RAW.DE_IDENT_YN). */
    static final String DEIDENTIFIED = "Y";

    private MarkingGuards() {
    }

    /**
     * ① 영상 단위 접근 가드 (CWE-639 수평 권한 상승 차단).
     *
     * <p>REVIEWER 는 전체 허용. WORKER 는 본인이 LABELER 로 배정된 rawSn 만 허용한다(배정 존재로 판정).
     * 인가는 <b>영상 존재 확인보다 먼저</b> 평가한다 — 미배정 WORKER 는 미존재 rawSn 에도 NOT_FOUND 가
     * 아니라 FORBIDDEN 을 받아 리소스 존재 여부를 노출하지 않는다(기존 계약 보존).
     *
     * @param rawSn                영상 PK
     * @param actor                인증된 사용자 (null 이면 UNAUTHORIZED)
     * @param assignmentRepository 배정 조회 리포지토리
     */
    static void requireAssignedOrReviewer(Long rawSn, TokenClaims actor,
                                          LsTaskAssignmentRepository assignmentRepository) {
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
     * ②③④⑤ 영상 프리컨디션 가드. {@code raw} 가 null 이면 영상 미존재로 NOT_FOUND.
     *
     * <ul>
     *   <li>비식별 완료(deIdntfYn='Y') — 마킹은 비식별 영상 대상(CLAUDE.md). 미완료 → PRECONDITION_FAILED.</li>
     *   <li>배치 단계 MARKING_READY — 이미 처리(PROCESSING/COMPLETED) 영상의 재마킹으로 인한
     *       DATA_STTS 역전을 차단. 아니면 → PRECONDITION_FAILED.</li>
     *   <li>이벤트 유형(EVNT_TYPE_CD) 존재 — 미지정 영상은 이벤트명 자동 소싱 불가 → INVALID_INPUT.</li>
     * </ul>
     *
     * @param raw 대상 영상 (null 이면 미존재)
     */
    static void requirePreconditions(LsDataRaw raw) {
        if (raw == null) {
            throw new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다.");
        }
        if (!DEIDENTIFIED.equals(raw.getDeIdntfYn())) {
            throw new CustomException(ErrorCode.PRECONDITION_FAILED,
                    "비식별이 완료된 영상에서만 마킹할 수 있습니다.");
        }
        if (!LsDataRaw.DATA_STTS_MARKING_READY.equals(raw.getDataSttsCd())) {
            throw new CustomException(ErrorCode.PRECONDITION_FAILED,
                    "이미 처리된 영상은 재마킹할 수 없습니다.");
        }
        String eventName = raw.getEvntTypeCd();
        if (eventName == null || eventName.isBlank()) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "이벤트 유형이 지정되지 않은 영상은 마킹할 수 없습니다.");
        }
    }

    /**
     * ⑥ 활성 마킹 중복 가드 — 영상당 <b>미종결 마킹 1건</b> (B-ISSUE-22, CWE-362 방어의 1선).
     *
     * <p>영상당 마킹이 2건 이상 쌓이면 배치는 최신 1건만 VLM 에 위탁하고 나머지는 영원히
     * {@code PENDING} 으로 남는 고아가 된다. 활성 마킹이 이미 있으면 {@link ErrorCode#CONFLICT}(409)로
     * 거부한다. "활성" 의 정의·근거는 {@link LsMarking#ACTIVE_STATUSES} 참조.
     *
     * <p><b>이 조회만으로는 동시 요청을 막지 못한다</b>(세 트랜잭션이 서로의 미커밋 행을 보지 못함).
     * 최종 방어는 {@code LS_MARKING} 부분 유니크 인덱스(V142)이며, 본 가드는 순차 요청을 프로브/쓰기
     * 이전에 값싸게 거부하는 1선이다.
     */
    static void requireNoActiveMarking(Long rawSn, LsMarkingRepository markingRepository) {
        if (markingRepository.existsByRawSnAndSttsCdIn(rawSn, LsMarking.ACTIVE_STATUSES)) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "이미 진행 중인 마킹이 있습니다. 기존 마킹이 종결된 뒤 다시 시도하세요.");
        }
    }

    /**
     * {@code actor.sub()} 에서 사용자 번호 파싱. 파싱 불가/blank 는 {@code null}.
     */
    static Long parseUserNo(String sub) {
        if (sub == null || sub.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(sub);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
