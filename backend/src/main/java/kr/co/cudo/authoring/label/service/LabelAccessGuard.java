package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.assignment.entity.LsPjtUserAuthrt;
import kr.co.cudo.authoring.assignment.repository.LsPjtUserAuthrtRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import lombok.RequiredArgsConstructor;
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
@Component
@RequiredArgsConstructor
public class LabelAccessGuard {

    private final LsDataSrcRepository srcRepository;
    private final LsPjtUserAuthrtRepository authrtRepository;

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
        LsDataSrc src = srcRepository.findById(srcSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프레임을 찾을 수 없습니다."));
        if (actor.role() == Role.REVIEWER) {
            return src;
        }
        if (actor.role() == Role.WORKER) {
            Long selfNo = parseUserNo(actor.sub());
            boolean assigned = authrtRepository.existsByUserNoAndTaskTypeCdAndRawDataId(
                    selfNo, LsPjtUserAuthrt.TASK_LABELER, src.getRawSn());
            if (!assigned) {
                throw new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다.");
            }
            return src;
        }
        throw new CustomException(ErrorCode.FORBIDDEN, "라벨 접근 권한이 없습니다.");
    }

    public Long parseUserNo(String sub) {
        try {
            return Long.parseLong(sub);
        } catch (NumberFormatException e) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "토큰 subject 형식이 올바르지 않습니다.");
        }
    }
}
