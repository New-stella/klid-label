package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.assignment.entity.LsTaskAssignment;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Phase 6 — 프레임 접근 권한 가드 (CWE-639 IDOR 일차 차단).
 *
 * LabelService / Sam2TrackService 등 프레임(SRC_SN) 단위로 라벨을 변경하는 모든 진입점이
 * 동일한 규칙으로 권한을 검사하도록 추출되었다.
 *
 *  - REVIEWER : 통과 (모든 프레임 검수 책임)
 *  - WORKER   : 본인이 LABELER 로 배정된 RAW 영상에 속한 프레임만 통과
 *  - 그 외    : 차단
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LabelAccessGuard {

    private final LsDataSrcRepository srcRepository;
    private final LsTaskAssignmentRepository authrtRepository;
    /**
     * 신고 구간 판정(actor 무관 데이터 상태)은 {@link DeidentReportGate} 단일 원천에 위임한다 —
     * 산출(export) 경로도 같은 판정을 쓰므로 여기서 {@code "F"} 비교를 재구현하지 않는다.
     */
    private final DeidentReportGate deidentReportGate;

    public void verifyAccess(Long srcSn, TokenClaims actor) {
        verifyAndGet(srcSn, actor);
    }

    /**
     * verifyAccess 와 동일한 인가 검사를 수행하되 조회된 {@link LsDataSrc} 를 반환한다.
     * 호출 측에서 rawSn/frameNo 가 추가로 필요할 때 사용 (N+1 회피).
     */
    public LsDataSrc verifyAndGet(Long srcSn, TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        // D-ISSUE-26 방어 — srcSn 이 null 이면 findById(null) 이 InvalidDataAccessApiUsageException
        // (미처리 500)을 던진다. 호출 측(예: DATA_SRC_SN 이 NULL 인 레거시 버전 스냅샷 경로)이 null 을
        // 흘려도 규약 4xx 로 끝나도록 진입부에서 차단한다(OWASP A10:2025 — 예외 처리 규약).
        if (srcSn == null) {
            throw new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다.");
        }
        LsDataSrc src = srcRepository.findById(srcSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다."));
        if (actor.role() == Role.REVIEWER) {
            return src;
        }
        if (actor.role() == Role.WORKER) {
            Long selfNo = parseUserNo(actor.sub());
            boolean assigned = authrtRepository.existsByUserNoAndTaskTypeCdAndRawDataId(
                    selfNo, LsTaskAssignment.TASK_LABELER, src.getRawSn());
            if (!assigned) {
                throw new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다.");
            }
            return src;
        }
        throw new CustomException(ErrorCode.FORBIDDEN, "라벨 접근 권한이 없습니다.");
    }

    /**
     * 영상(rawSn) 단위 접근 인가 검사 — 프레임(srcSn)이 아니라 영상 ID 만으로 권한을 확인할 때 사용.
     * <p>비식별 신고 resolve(R1 v1.14) 처럼 srcSn 컨텍스트 없이 rawSn 만 있는 경로용.
     * <ul>
     *   <li>REVIEWER : 통과</li>
     *   <li>WORKER   : 본인 LABELER 배정 영상만 통과 (CWE-639 IDOR 방어)</li>
     *   <li>그 외    : 차단</li>
     * </ul>
     */
    public void verifyRawAccess(Long rawSn, TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        if (actor.role() == Role.REVIEWER) {
            return;
        }
        if (actor.role() == Role.WORKER) {
            Long selfNo = parseUserNo(actor.sub());
            boolean assigned = authrtRepository.existsByUserNoAndTaskTypeCdAndRawDataId(
                    selfNo, LsTaskAssignment.TASK_LABELER, rawSn);
            if (!assigned) {
                throw new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다.");
            }
            return;
        }
        throw new CustomException(ErrorCode.FORBIDDEN, "라벨 접근 권한이 없습니다.");
    }

    /**
     * S7 (HIGH — CWE-359) — 비식별 누락 신고 구간(재비식별 대기) 영상의 <b>차단 게이트</b>
     * (조회 + 개인정보 선언 저장 — 아래 "차단 범위" 참조).
     *
     * <p>배경: 비식별 신고는 라벨을 <b>삭제하지 않고 보존</b>한다(2026-07-27 정책 반전). 그래서 신고
     * ~재비식별 완료 사이에 라벨을 그대로 내려주면, 영상 스트리밍은 {@code DE_IDNTF_YN='F'} 로 막혀
     * 있는데도 라벨 좌표(=PII 위치 특정 정보)만 계속 노출된다. 인가(WORKER 배정/REVIEWER)를 통과한
     * 뒤 이 게이트로 한 번 더 막는다.
     *
     * <p>정책 근거 — 기존 유사 게이트와 정렬:
     * <ul>
     *   <li><b>역할 무관 차단(REVIEWER 도 동일)</b>: 영상 스트리밍({@code VideoStreamService} — 비식별
     *       미완료 시 역할 무관 NOT_FOUND)·마킹 진입({@code MarkingGuards.requirePreconditions} —
     *       역할 무관 PRECONDITION_FAILED)이 모두 역할과 무관한 프리컨디션이다. 신고 구간의 PII 노출
     *       위험은 검수자에게도 동일하므로 REVIEWER 예외를 두지 않는다.</li>
     *   <li><b>인가 이후 평가</b>: 인가 검사({@link #verifyAndGet})를 먼저 통과시켜 이 게이트가 인가를
     *       대체·우회하지 않게 한다(미배정 WORKER 는 여전히 FORBIDDEN).</li>
     *   <li><b>차단 범위 = 조회 + 개인정보 선언 저장</b> (2026-08-03 DEV_FIX 2차 정정): 도입 시점에는
     *       조회 전용이었고 "저장/수정은 작업락({@code WorkLockService.isRawLocked})이 409 로 막으므로
     *       여기서 막을 것이 없다"가 근거였다. 지금은 <b>쓰기 호출자가 있다</b> — 개인정보 메타 PUT
     *       (영상 축 {@code VideoPrivacyMetaService} · 프레임 축 {@code FramePrivacyMetaService})은
     *       작업락이 걸리지 않는 별도 경로인데, 신고가 리셋한 개인정보 판정을 신고 구간에 되돌릴 수 있어
     *       412 로 함께 막는다. 그 외 저장/수정(라벨 등)은 여전히 작업락 409 가 담당한다.</li>
     *   <li><b>자동 해제</b>: resolve(수동/자동)가 {@code 'F'→'Y'} 를 복원하면 게이트가 즉시 열려
     *       <b>보존된 기존 라벨을 그대로</b> 다시 사용한다(별도 복원 절차 없음).</li>
     * </ul>
     *
     * <p>{@code 'Y'}(정상)·{@code 'N'}(미수행)·null 은 통과 — 일반 영상 흐름에 영향이 없다.
     * 응답 메시지에 경로·좌표·내부 정보를 담지 않는다.
     *
     * <p><b>판정 범위 = 해당 영상 행 하나</b>({@link DeidentReportGate} 단일 원천): 파생영상(해상도·증강)은
     * <b>원본의 신고와 무관하게</b> 다루는 것이 확정 정책(2026-07-29)이라 {@code ORGNL_RAW_SN} 을 타고
     * 올라가지 않는다. 이 게이트를 공유하는 모든 호출부(라벨 조회·이력·버전 diff/rollback·프레임 이미지·
     * 포털·관제 조회)가 동일 판정을 쓴다 — 호출부마다 {@code "F".equals(...)} 를 재구현하지 않는다.
     *
     * @param rawSn 영상 PK (null 이면 판정 불가 → 통과, 상위 가드가 이미 존재 검증)
     */
    public void requireNotUnderDeidentReport(Long rawSn) {
        if (deidentReportGate.isUnderDeidentReport(rawSn)) {
            log.warn("[LabelAccess] blocked — deident report open rawSn={}", rawSn);
            // 행위 중립 문구 — 이 게이트는 조회(라벨·이력·프레임 이미지)와 개인정보 선언 저장(PUT)을
            //   함께 막는다. 구 문구("…라벨을 조회할 수 없습니다")는 PUT 호출자가 생긴 뒤로 거짓이었다.
            throw new CustomException(ErrorCode.PRECONDITION_FAILED,
                    "비식별 재처리 대기 중인 영상입니다. 재비식별 완료 후 다시 시도해 주세요.");
        }
    }

    public Long parseUserNo(String sub) {
        try {
            return Long.parseLong(sub);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "토큰 subject 형식이 올바르지 않습니다.");
        }
    }
}
