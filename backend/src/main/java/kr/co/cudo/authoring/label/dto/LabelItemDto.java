package kr.co.cudo.authoring.label.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 라벨 항목 (요청/응답 공용).
 * - id == null  : 신규 추가
 * - id != null  : 기존 라벨 수정 (AUTO_LBL_YN 은 유지됨)
 * - lblTypeCd : BBOX / POLYGON / SEGMENT / TRACK / SKELETON
 * - labelId : LS_LABEL FK (Phase 2). null 허용 — 수정 시 기존 값 유지, 신규 시 NULL 저장.
 * - points : [[x, y], ...] (2-튜플) 또는 SKELETON 은 [[x, y, v], x17] (삼중값).
 *            좌표 검증은 Service 에서 — 음수/이미지 경계 초과 차단, SKELETON 은 17점·v∈{0,1,2}.
 *
 * <h3>R9 provenance 필드 (source / confScore / algorithm) — 신규 삽입 힌트 (요청 전용)</h3>
 * 온라인 오토라벨(AI 탐지/추적)은 좌표만 반환하므로 저장 시 FE 작업본이 이 힌트를 함께 실어
 * <b>신규(id==null)</b> 라벨을 자동 라벨(AUTO_LBL_YN='Y')로 보존하게 한다(구 버그: 자동 라벨이
 * 수동으로 둔갑 + 신뢰도 유실). id != null(기존 UPDATE) 경로는 이 힌트를 무시한다.
 *
 * <p><b>Mass Assignment 트러스트 경계 (CWE-915)</b>: 본 엔드포인트는 내부 채널 한정
 * (REVIEWER/WORKER, PORTAL 403·CHANNEL_INTERNAL)이며 provenance 는 <b>권한 상승이 아니라
 * 데이터마트 분류 힌트</b>다. 그럼에도 요청 DTO 는 아래 3개(source/confScore/algorithm)만 노출하고
 * role/isAdmin/autoLblYn 같은 민감·정책 필드는 절대 바인딩하지 않는다. 값은 아래 검증(범위·화이트리스트)을
 * 통과해야 하며, 신뢰할 수 없는 값은 400 으로 거부한다.
 *
 * @param source    "MANUAL" | "AUTO_YOLO" | "AUTO_SAM2" (화이트리스트). null → MANUAL 로 간주(하위호환).
 * @param confScore 신뢰도 [0.0, 1.0]. null 허용(수동/미보유). AI_INFO.CONF_SCORE 로 보존.
 * @param algorithm "YOLO" | "SAM2" | "RT-DETR" (화이트리스트). null 허용 — 미지정 시 source 로 파생.
 */
public record LabelItemDto(
        Long id,
        @NotBlank @Pattern(regexp = "BBOX|POLYGON|SEGMENT|TRACK|SKELETON",
                message = "lblTypeCd 는 BBOX/POLYGON/SEGMENT/TRACK/SKELETON 중 하나여야 합니다.") String lblTypeCd,
        Long labelId,
        @NotBlank @Size(max = 80) String label,
        /**
         * 좌표 배열. <b>입구 상한</b>이 DTO 레벨에 있어야 한다 (CWE-770).
         *
         * <h3>왜 서비스 검증만으로는 부족한가</h3>
         * {@code LabelService.MAX_POINTS_PER_LABEL}(1000)은 <b>신규 라벨만</b> 강제하고 기존 라벨은
         * simplify 로 통과시킨다. 그래서 좌표 개수에 사실상 상한이 없었고, 영상 단위 확정 저장
         * ({@code VideoLabelSaveRequest})이 <b>프레임 × 라벨 × 좌표</b> 곱셈 축을 만든 뒤로는 그 공백이
         * 수 GB 힙으로 증폭된다. 앞단 방벽은 리버스 프록시 본문 크기 제한뿐이고, {@code @Valid} 는
         * <b>역직렬화 후</b>에 도므로 이미 힙에 올라온 뒤에야 거부된다 — 그래도 상한이 있으면 그 이상은
         * 절대 통과하지 못하므로 축적·확산을 끊는다.
         *
         * <h3>상한값 근거 — 왜 1000 이 아닌가</h3>
         * 기존 라벨은 1000 초과여도 simplify 로 저장되는 계약이라(레거시 SAM2 폴리곤), 1000 으로 조이면
         * <b>이미 저장된 라벨을 그대로 재전송하는 정상 저장이 400</b> 이 된다. 그래서 그 계약을 깨지 않는
         * 여유(10배)를 두고 <b>비정상 입력만</b> 끊는다.
         *
         * <p>안쪽 튜플에도 상한을 둔다 — {@code [[x,y,v]]} 가 최대 형태(SKELETON 삼중값)이므로 그보다 긴
         * 배열은 어떤 도형에도 쓰이지 않는다. 이 축이 없으면 좌표쌍 하나가 무한히 길어질 수 있다.
         */
        @NotEmpty
        @Size(max = MAX_POINTS_PER_REQUEST,
                message = "라벨당 좌표 개수 초과 (최대 " + MAX_POINTS_PER_REQUEST + ")")
        List<@Size(max = MAX_COORD_TUPLE_LENGTH,
                message = "좌표는 [x,y] 또는 [x,y,v] 형태여야 합니다.") List<Double>> points,
        String autoLblYn,   // 응답 전용 (요청 시 무시, REVIEWER 도 변경 불가 — Mass Assignment 방어)
        @Pattern(regexp = "MANUAL|AUTO_YOLO|AUTO_SAM2",
                message = "source 는 MANUAL/AUTO_YOLO/AUTO_SAM2 중 하나여야 합니다.") String source,
        @DecimalMin(value = "0.0", message = "confScore 는 0.0 이상이어야 합니다.")
        @DecimalMax(value = "1.0", message = "confScore 는 1.0 이하여야 합니다.") Double confScore,
        @Size(max = 20)
        @Pattern(regexp = "YOLO|SAM2|RT-DETR",
                message = "algorithm 은 YOLO/SAM2/RT-DETR 중 하나여야 합니다.") String algorithm
) {

    /**
     * 요청 1건의 라벨당 좌표 개수 <b>입구 상한</b> (CWE-770).
     *
     * <p>서비스의 {@code LabelService.MAX_POINTS_PER_LABEL}(신규 라벨 강제, simplify 기준)과 <b>다른 축</b>
     * 이다: 이쪽은 "역직렬화를 허용할 최대 크기", 그쪽은 "신규 라벨로 저장을 허용할 최대 크기"다.
     * 두 값을 같게 맞추면 기존 라벨의 simplify 저장 계약이 깨진다(필드 주석 참조).
     */
    public static final int MAX_POINTS_PER_REQUEST = 10_000;

    /** 좌표 튜플 최대 길이 — {@code [x,y]} 또는 SKELETON {@code [x,y,v]}. */
    public static final int MAX_COORD_TUPLE_LENGTH = 3;

    /** 하위호환 — provenance 미지정(수동/legacy) 6-arg 생성자. source/confScore/algorithm=null. */
    public LabelItemDto(Long id, String lblTypeCd, Long labelId, String label,
                        List<List<Double>> points, String autoLblYn) {
        this(id, lblTypeCd, labelId, label, points, autoLblYn, null, null, null);
    }
}
