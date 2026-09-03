package kr.co.cudo.authoring.video.dto;

import kr.co.cudo.authoring.video.entity.LsDataRaw;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * <b>외부 마킹 산출물 일괄 가져오기</b>가 영상 한 건을 적재할 때 넘기는 커맨드(ADR-053 · SEQ-030).
 *
 * <h3>왜 영상 <b>한 건</b>인가</h3>
 * <p>적재는 항목마다 따로 트랜잭션을 갖는다 — 백 건을 한 트랜잭션에 묶으면 한 건 때문에 전부
 * 되돌아간다(SEQ-030). 그래서 이 도메인이 내주는 자리는 <b>영상 1건 단위</b>이고, 몇 건을 어떤 순서로
 * 어느 동시성으로 부를지는 호출부(이관)가 정한다.
 *
 * <h3>필드는 <b>사람이 지정한 값</b>과 <b>이미 복사된 위치</b>뿐이다</h3>
 * <p>마킹 문서에서 얻을 수 없는 값만 사람이 화면에서 한 번 지정해 항목마다 같은 값으로 붙는다
 * (DFEAT-060). 영상 식별자는 영상 파일 이름에서 얻으므로 사람이 지정하지 않는다.
 * <p><b>여기에 없는 것</b>도 의도다.
 * <ul>
 *   <li><b>영상 길이·해상도·프레임률 같은 기술메타</b> — 적재 직후 발행되는
 *       {@code VideoIngestedEvent} 가 기술메타 추출을 태우고 그 실측값이 적재된다. 커맨드로 받으면
 *       호출부가 채운 값과 실측값이 갈린다.</li>
 *   <li><b>비식별 여부</b> — 이 갈래가 받는 영상은 <b>언제나 비식별되지 않은 원본</b>이다(UC-037).
 *       고를 수 있게 두면 원본이 비식별 완료로 적재되는 길이 열린다(CWE-359).</li>
 *   <li><b>인입 원장 관련 값</b> — 이 경로는 관제 인입 원장을 거치지 않는다. 그 원장은 "관제가 무엇을
 *       보냈는가"의 기록이라, 저작도구가 자기 판단으로 행을 넣으면 관제가 보낸 것과 우리가 넣은 것을
 *       나중에 구분할 수 없다(ADR-048 이 같은 이유로 정한 규칙을 이 갈래도 따른다).</li>
 * </ul>
 *
 * @param vmsClipId     영상 파일 이름에서 <b>확장자를 뗀 값</b>. {@code UK_LS_DATA_RAW_VMS_CLIP} 가
 *                      중복 반입을 실제로 막는 근거이므로 호출부가 임의 접미를 붙여 유일화하지 않는다
 * @param vmsCctvId     사람이 지정한 카메라 식별자(선택)
 * @param evntTypeCd    사람이 지정한 이벤트 유형 코드. ⚠ 마킹 문서의 {@code notes} 를 유형으로
 *                      파싱한 값이 아니다
 * @param lclgvCd       사람이 지정한 지자체 코드
 * @param prvcTypeCd    사람이 지정한 개인정보 유형 — {@link #ALLOWED_PRVC_TYPES} 3종
 * @param rawFilePathNm <b>이미 저작도구 저장소로 복사를 마친</b> 영상 파일의 절대 위치. 복사는 호출부가
 *                      한다 — 이 도메인은 외부 폴더를 직접 열지 않는다
 * @param shtDt         사람이 지정한 촬영일시(선택 — 미지정이면 {@code null}, 대용값 금지)
 * @design ADR-053
 * @design DFEAT-060
 * @design SEQ-030
 */
public record MarkingImportIngestCommand(
        String vmsClipId,
        String vmsCctvId,
        String evntTypeCd,
        String lclgvCd,
        String prvcTypeCd,
        String rawFilePathNm,
        LocalDateTime shtDt) {

    /**
     * 사람이 고를 수 있는 개인정보 유형.
     *
     * <p>{@code UNKNOWN}(미상)은 <b>넣지 않는다</b> — 그 값은 분류가 확정되지 않은 영상의 잠정값인데,
     * 이 경로는 사람이 화면에서 직접 고르므로 "모른다"가 선택지로 존재할 이유가 없다. 열어 두면 화면이
     * 값을 안 보냈을 때 조용히 미상으로 적재되어 결손이 드러나지 않는다.
     */
    public static final Set<String> ALLOWED_PRVC_TYPES = Set.of(
            LsDataRaw.PRVC_TYPE_ANONY, LsDataRaw.PRVC_TYPE_PRVC, LsDataRaw.PRVC_TYPE_PSDO);
}
