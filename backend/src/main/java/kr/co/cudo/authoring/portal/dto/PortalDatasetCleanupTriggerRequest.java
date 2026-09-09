package kr.co.cudo.authoring.portal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import kr.co.cudo.authoring.portal.entity.LsDatstArngmtTrgr;

/**
 * 포털 정리 삭제 트리거 요청 본문.
 *
 * <p>속성 이름은 저작도구가 확정했다(포털의 수락 회신은 아직 오지 않았다 — API-244 「열린 항목」).
 *
 * <h3>길이 상한이 「형식 확정」이 아닌 이유</h3>
 * <p>데이터셋 코드와 버전의 표현 형식(길이·문자집합·버전 표기법)은 <b>여전히 회신되지 않은 열린
 * 항목</b>이다. 여기 건 {@link Size} 는 형식을 정하려는 것이 아니라 <b>접수 원장 컬럼 폭</b>
 * ({@link LsDatstArngmtTrgr#DATASET_CODE_MAX_LENGTH} · {@link LsDatstArngmtTrgr#VERSION_MAX_LENGTH})
 * 을 그대로 옮긴 것이다. 입구에서 막지 않으면 폭을 넘는 값이 INSERT 시점 <b>DB 오류(500)</b> 가 되어
 * 저장 구조가 응답으로 샌다 — 거부 응답에 저장 구조를 싣지 않는다는 규칙(API-244)에 어긋난다.
 *
 * <p>⚠ 그러므로 포털이 표현 형식을 회신하면 <b>이 상한과 컬럼 폭을 함께</b> 조정한다. 한쪽만 고치면
 * 다시 500 이 새거나 정상 값이 400 으로 막힌다.
 *
 * <p>문자집합 제약은 두지 않는다 — 회신되지 않은 형식을 우리가 먼저 좁히면 정상 값을 우리 쪽에서
 * 막게 된다(이 저장소가 검증이벤트유형 allowlist 에서 이미 한 번 겪은 실패 방향이다).
 *
 * @design API-244
 * @design AC-1102
 */
@Schema(description = "포털이 학습데이터셋의 새 버전을 받았을 때 보내는 정리 삭제 트리거")
public record PortalDatasetCleanupTriggerRequest(

        @Schema(description = "정리 대상을 가리키는 학습데이터셋 코드", example = "DS-2026-001")
        @NotBlank(message = "데이터셋 코드는 필수입니다.")
        @Size(max = LsDatstArngmtTrgr.DATASET_CODE_MAX_LENGTH,
                message = "데이터셋 코드가 허용 길이를 넘습니다.")
        String datasetCode,

        @Schema(description = "포털이 새로 받은 버전. 이 버전이 들어오면 그보다 이전의 올드 해제본이 정리 대상이 된다.",
                example = "v3")
        @NotBlank(message = "버전은 필수입니다.")
        @Size(max = LsDatstArngmtTrgr.VERSION_MAX_LENGTH,
                message = "버전이 허용 길이를 넘습니다.")
        String version
) {
}
