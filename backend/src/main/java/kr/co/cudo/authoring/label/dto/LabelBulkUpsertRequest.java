package kr.co.cudo.authoring.label.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 프레임 내 라벨 bulk upsert 요청.
 * - items 가 빈 리스트면 = 모든 라벨 삭제 (기존 라벨이 있을 경우).
 * - 한 요청 최대 500 건 (CWE-770 DoS 방어).
 *
 * <h3>labelVersion — 라벨셋 낙관적 동시성 토큰 (C-ISSUE-21, 선택)</h3>
 * 라벨 조회 응답({@link LabelResponse#labelVersion()})이 내려준 값을 그대로 실어 보내면, 서버는 저장
 * 직전 프레임의 현재 라벨셋 버전과 대조해 <b>다르면 409</b> 로 거부한다(내 화면이 낡았다는 뜻 —
 * full-replace 라 그대로 저장하면 그사이 다른 사람이 추가한 라벨이 삭제된다).
 *
 * <p><b>선택 필드다.</b> 값을 넣지 않으면(null) 버전 검사를 건너뛰고 기존과 완전히 동일하게 저장된다
 * (FE 미반영 구간 하위호환). 저장으로 라벨이 실제 변경되면 서버가 버전을 +1 하고 응답에 새 값을 담는다.
 *
 * <h3>dscdYn — 프레임 폐기여부 (R4·R5, 선택)</h3>
 * 화면에서 한 일은 <b>저장을 눌러야</b> 확정되므로(D8), 프레임 폐기·복원도 별도 엔드포인트가 아니라 이
 * 저장 계약에 실린다. {@code Y}=산출물에서 제외 / {@code N}=다시 산출 대상. <b>보내지 않으면 현재 값을
 * 그대로 둔다</b> — 폐기를 모르는 기존 호출자가 저장할 때마다 폐기 상태를 조용히 되돌리면 안 되기
 * 때문이다(하위호환).
 *
 * <p>폐기는 <b>논리 폐기</b>다: 프레임 행·이미지 파일·라벨은 보존되고 학습데이터 산출물·데이터마트
 * 노출에서만 빠진다. 저작도구 화면의 프레임 수는 줄지 않는다(D2).
 *
 * <p><b>계약 출처</b>: 이 필드의 이름·허용값·"생략 시 현재 값 유지" 규약은 확정 설계 API-196
 * (영상 단위 일괄 저장)의 {@code frames[].dscdYn} 과 동일하다. 영상 단위 저장은 후속 단계에서
 * 추가되며, 그때 <b>같은 규약</b>을 그대로 쓰도록 여기서 축을 먼저 맞춘다.
 *
 * @design D8
 */
public record LabelBulkUpsertRequest(
        @NotNull @Valid @Size(max = 500, message = "한 번에 처리 가능한 라벨 수 초과 (최대 500)") List<LabelItemDto> items,
        @PositiveOrZero(message = "labelVersion 은 0 이상이어야 합니다.") Long labelVersion,
        /**
         * 프레임 폐기여부 — {@code Y}/{@code N} 만 허용(null=현재 값 유지).
         *
         * <p>서버가 받아들이는 문자열을 두 개로 못 박는 이유(CWE-20): 이 값은 코드값 컬럼
         * ({@code LS_DATA_SRC.DSCD_YN CHAR(1)})에 그대로 들어가고 감사 로그 축에도 실린다. 자유 문자열을
         * 허용하면 개행·제어문자가 로그로 흘러가고(CWE-117) 코드값이 오염된다. 정규식은 <b>앵커와 함께</b>
         * 써서 {@code "Y\n"} 같은 다중행 우회를 막는다({@code \A}/{@code \z} — 자바 기본 {@code ^$} 는
         * MULTILINE 이 아니어도 {@code \n} 을 끝으로 허용하는 함정이 있다).
         */
        @jakarta.validation.constraints.Pattern(regexp = "\\A[YN]\\z",
                message = "dscdYn 은 Y 또는 N 이어야 합니다.") String dscdYn
) {

    /** 하위호환 — labelVersion 미첨부(버전 검사 skip) 단일 인자 생성자. */
    public LabelBulkUpsertRequest(List<LabelItemDto> items) {
        this(items, null, null);
    }

    /** 하위호환 — 폐기여부 미첨부(현재 값 유지) 2-인자 생성자. */
    public LabelBulkUpsertRequest(List<LabelItemDto> items, Long labelVersion) {
        this(items, labelVersion, null);
    }
}
