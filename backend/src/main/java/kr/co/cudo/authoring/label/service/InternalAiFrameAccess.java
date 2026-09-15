package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.security.TokenClaims;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 내부 채널 {@link AiFrameAccess} — 요청마다 행위자에 묶어 만든다(빈이 아니다).
 *
 * <ul>
 *   <li>인가: {@link LabelAccessGuard#verifyAndGet} — 본인 배정 IDOR(WORKER), 검수자 계열 전체 허용.</li>
 *   <li>이미지: {@link FrameImageEncoder#encodeFrame} / {@link FrameImageEncoder#resolveFrameImageForInference}
 *       — 비식별 누락 신고 게이트가 <b>파일을 읽기 전</b>에 끊는다(전송 전 차단).</li>
 *   <li>좌표 상한: {@link FrameBoundsResolver}(캐시 · 측정 실패 시 상한 생략).</li>
 *   <li>차단: 서비스가 넘긴 판정(AI 탐지 = 작업락 409 → 신고 상태 412). 넘기지 않으면 통과 —
 *       자동 추적은 종전대로 이미지 게이트 하나로 끊는다.</li>
 * </ul>
 *
 * <p>이 클래스가 하는 일은 기존 서비스가 직접 부르던 협력자 호출을 <b>그대로</b> 옮긴 것뿐이다 — 내부 창구의
 * 인가·게이트·응답 코드는 바뀌지 않는다.
 */
final class InternalAiFrameAccess implements AiFrameAccess {

    private final LabelAccessGuard accessGuard;
    private final FrameImageEncoder frameImageEncoder;
    private final FrameBoundsResolver frameBoundsResolver;
    private final TokenClaims actor;
    /** null 이면 막을 것이 없다. rawSn 이 null 일 수 있어 원시형 소비자를 쓰지 않는다(언박싱 NPE). */
    private final Consumer<Long> blockedCheck;

    InternalAiFrameAccess(LabelAccessGuard accessGuard, FrameImageEncoder frameImageEncoder,
                          FrameBoundsResolver frameBoundsResolver, TokenClaims actor,
                          Consumer<Long> blockedCheck) {
        this.accessGuard = accessGuard;
        this.frameImageEncoder = frameImageEncoder;
        this.frameBoundsResolver = frameBoundsResolver;
        this.actor = actor;
        this.blockedCheck = blockedCheck;
    }

    @Override
    public LsDataSrc authorize(Long srcSn) {
        return accessGuard.verifyAndGet(srcSn, actor);
    }

    @Override
    public Path resolveImage(LsDataSrc frame) {
        return frameImageEncoder.resolveFrameImageForInference(frame);
    }

    @Override
    public String encodeImage(LsDataSrc frame) {
        return frameImageEncoder.encodeFrame(frame);
    }

    @Override
    public Optional<int[]> resolveBounds(LsDataSrc frame) {
        return frameBoundsResolver.resolve(frame);
    }

    @Override
    public void requireNotBlocked(Long rawSn) {
        if (blockedCheck != null) {
            blockedCheck.accept(rawSn);
        }
    }
}
