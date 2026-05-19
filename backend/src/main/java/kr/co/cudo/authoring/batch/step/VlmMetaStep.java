package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * VLM 영상 단위 메타 추출 단계 — Phase 1(2026-05-19)부터 NO-OP 으로 보류.
 *
 * <p><b>변경 배경</b> (CLAUDE.md V1.8 / ccarch {@code if-vlm-timeseries-spi}):
 * 영상 단위 시계열 메타 추출은 외부 VLM 서비스 책임으로 이관되었다. 본 Step 의 책임은
 * 신규 {@link VlmTimeseriesStep}(외부 위탁) + Phase 2 결과 수신 webhook 으로 분리되었다.
 *
 * <p><b>보존 사유</b>: Phase 2 webhook 적재 흐름이 완성되기 전까지는 본 Step 의 코드/빈을
 * 즉시 폐기하지 않는다. NO-OP 처리하여 외부 통신을 발생시키지 않으면서, 의존성 그래프와
 * 마이그레이션 경로를 보존한다. 완전 폐기는 Phase 5 cleanup 에서 수행한다.
 *
 * <p><b>현재 동작</b>: {@link #run(Long)} 호출 시 외부 호출 없이 INFO 로깅 후 0 반환.
 * BatchOrchestrator 는 더 이상 본 Step 을 호출하지 않는다 (Phase 1 에서 호출 제거).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VlmMetaStep {

    /**
     * @deprecated Phase 1(2026-05-19)부터 NO-OP. 호출 경로 자체가 제거되었으므로 본 메서드는
     *             외부 통신 없이 즉시 0 을 반환한다. 완전 폐기 대상 — Phase 5 에서 클래스 삭제 예정.
     */
    @Deprecated
    public int run(Long rawSn) {
        if (rawSn == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 이 null 입니다.");
        }
        log.info("[Batch][VlmMeta] no-op (deprecated, replaced by VlmTimeseriesStep) rawSn={}", rawSn);
        return 0;
    }
}
