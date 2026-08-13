package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import lombok.extern.slf4j.Slf4j;

/**
 * D5 — 버전 스냅샷({@code LS_LABEL_VERSION.LBL_PAYLOAD})의 <b>폐기여부 해석 단일 판정기</b>.
 *
 * <h3>과도기 호환 (Critical)</h3>
 * 폐기 축을 스냅샷에 편입하면 {@code VERSION_HASH} 가 달라진다(의도된 변경). 그러나 <b>이미 저장된
 * 승인 스냅샷은 그대로</b>이므로 한동안 옛 형식(키 부재)과 새 형식(키 존재)이 <b>공존</b>한다.
 * 읽는 쪽은 <b>키 부재를 "폐기 아님"({@code N})</b> 으로 해석해야 옛 버전을 시작 버전으로 골랐을 때
 * 멀쩡한 프레임이 근거 없이 폐기되지 않는다.
 *
 * <h3>allowlist 판정 (fail-safe)</h3>
 * 정확히 문자열 {@code "Y"} 일 때만 폐기로 읽는다. 손상·비규격 값({@code "y"}·{@code true}·숫자)은
 * 전부 {@code N} 이다 — 잘못 폐기하면 산출물에서 프레임이 <b>사라지는</b> 방향이라 반대 방향
 * (잘못 살림)보다 피해가 크다. 본문 손상 자체는 롤백 코어({@code VersionService.rollbackToSnapshot})
 * 가 400 으로 먼저 걸러내므로, 여기 도달하는 payload 는 이미 파싱 가능한 것이다.
 *
 * @design D5
 * @req R6
 */
@Slf4j
public final class SnapshotDiscardPolicy {

    /** 스냅샷 payload 의 폐기여부 필드명 — 응답 DTO({@code LabelResponse.dscdYn})와 같은 이름이다. */
    public static final String FIELD = "dscdYn";

    private SnapshotDiscardPolicy() {
    }

    /**
     * 스냅샷 payload 에서 그 프레임의 폐기여부를 읽는다.
     *
     * @param payload 스냅샷 JSON ({@code null}·공백이면 라벨 0건 스냅샷 — 폐기 아님)
     * @param srcSn   대상 프레임 PK — <b>파싱 실패 관측용 식별자</b>로만 쓴다(판정에 관여하지 않는다)
     * @return {@code "Y"}(폐기) 또는 {@code "N"}(사용) — {@code null} 을 돌려주지 않는다.
     *         {@code null} 은 {@code FrameDiscardApplier} 에서 "현재 값 유지"를 뜻해, 되돌리기가
     *         조용히 아무것도 하지 않는 결과가 되기 때문이다.
     */
    public static String resolve(String payload, ObjectMapper objectMapper, Long srcSn) {
        if (payload == null || payload.isBlank()) {
            return LsDataSrc.DSCD_NO;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(payload).path(FIELD);
        } catch (Exception e) {
            // 판정은 안전한 쪽(N)으로 수렴시키되 <b>무음으로 삼키지 않는다</b>: 손상 스냅샷은 되돌리기
            //   결과를 조용히 바꾸므로(폐기였던 프레임이 살아난다) 운영에서 관측 가능해야 한다.
            //   본문·경로·예외 메시지는 남기지 않고 식별자와 예외 종류만 남긴다(CWE-359/117).
            log.warn("[Version] snapshot discard flag unreadable — treated as not discarded srcSn={} cause={}",
                    srcSn, e.getClass().getSimpleName());
            return LsDataSrc.DSCD_NO;
        }
        return node.isTextual() && LsDataSrc.DSCD_YES.equals(node.asText())
                ? LsDataSrc.DSCD_YES : LsDataSrc.DSCD_NO;
    }
}
