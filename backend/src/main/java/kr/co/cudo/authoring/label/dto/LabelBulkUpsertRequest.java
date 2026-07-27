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
 */
public record LabelBulkUpsertRequest(
        @NotNull @Valid @Size(max = 500, message = "한 번에 처리 가능한 라벨 수 초과 (최대 500)") List<LabelItemDto> items,
        @PositiveOrZero(message = "labelVersion 은 0 이상이어야 합니다.") Long labelVersion
) {

    /** 하위호환 — labelVersion 미첨부(버전 검사 skip) 단일 인자 생성자. */
    public LabelBulkUpsertRequest(List<LabelItemDto> items) {
        this(items, null);
    }
}
