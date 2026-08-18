package kr.co.cudo.authoring.label.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * SAM2 Track 시작 요청.
 * - srcSn       : 시작 프레임의 SRC_SN
 * - trackId     : 트랙 식별자 (이전 라벨에 부여된 식별자, 신규 트랙이면 클라이언트가 UUID 발급)
 * - prevPolygon : 시작 프레임의 폴리곤 좌표 [[x,y],...] (최소 3 점)
 * - label       : 객체 라벨명
 * - nextSrcSns  : 트래킹 대상 후속 프레임의 SRC_SN 리스트 (CWE-770 — 최대 50)
 * - shape       : (R12) 결과 형태(BBOX | POLYGON). null → POLYGON 기본. BBOX 면 추적 폴리곤의
 *                 외접 bbox 를 산출해 반환(label 부여). 잘못된 문자열은 Jackson 이 400 으로 차단(CWE-20).
 */
public record Sam2TrackRequest(
        @NotNull Long srcSn,
        // CWE-117/20 — trackId 는 로그·ai 요청에 전달되므로 길이 상한(TRACK_ID VARCHAR(64) 정합)으로 제한.
        // 허용 문자도 제한한다: 개행이 섞인 trackId 는 ai-server 로그를 위조할 수 있고(BE 는 원문을
        // 그대로 ai-server 로 전달한다), ai-server 스키마(TRACK_ID_PATTERN)와 같은 규칙을 여기서도
        // 강제해야 계약이 갈라지지 않는다(안 그러면 클라이언트 입력 오류가 ai-server 400 → BE 502 로 승격).
        // 실제 트랙 ID 는 트래커가 부여한 정수 문자열/채번값이라 이 집합을 벗어나지 않는다.
        @NotBlank @Size(max = 64)
        @Pattern(regexp = "^[A-Za-z0-9._:-]+$", message = "트랙 ID 는 영숫자와 . _ : - 만 사용할 수 있습니다.")
        String trackId,
        @NotEmpty @Size(min = MIN_POLYGON_POINTS, max = MAX_POLYGON_POINTS) List<List<Double>> prevPolygon,
        @NotBlank @Size(max = 80) String label,
        @NotEmpty @Size(max = 50) List<Long> nextSrcSns,
        AutolabelShape shape
) {

    /**
     * {@code prevPolygon} 최소 정점 수 — 면적이 있는 폐곡선의 하한.
     *
     * <p>값이 {@code Sam2CoordinateValidator.MIN_POLYGON_POINTS}(응답 축) 와 같은 것은 우연이 아니라
     * 같은 기하 요건이다. 다만 두 상수를 <b>합치지는 않는다</b> — 요청 축은 Bean Validation 이,
     * 응답 축은 서비스가 쓰며 위반 시 응답 코드도 400/502 로 다르다.
     */
    public static final int MIN_POLYGON_POINTS = 3;

    /**
     * {@code prevPolygon} 최대 정점 수 (CWE-770 — 무제한 입력 차단).
     *
     * <p><b>상수로 노출하는 이유</b>: 서버가 <b>스스로 발행하는</b> 이어 보내기 시드
     * ({@code Sam2TrackResponseDto.Resume.prevPolygon})가 이 상한을 지켜야 한다. 그 값은 추론 서버가
     * 준 원본 폴리곤인데 추론 서버는 윤곽점 수를 제한하지 않으므로, 서비스가 발행 직전에 이 상한으로
     * 줄인다({@code Sam2TrackService.resumeSeed}). 숫자를 서비스에도 적으면 <b>두 번째 진실원</b>이
     * 되어 한쪽만 바뀌는 순간 «서버가 스스로 받지 못하는 값을 발행» 하는 상태로 되돌아간다.
     */
    public static final int MAX_POLYGON_POINTS = 1000;

    /** 하위호환 — shape 미지정(POLYGON 기본) 5-arg 편의 생성자. 포털 경로/기존 호출자 유지. */
    public Sam2TrackRequest(Long srcSn, String trackId, List<List<Double>> prevPolygon,
                            String label, List<Long> nextSrcSns) {
        this(srcSn, trackId, prevPolygon, label, nextSrcSns, null);
    }

    /** null shape 를 추적 기본값 {@code POLYGON} 으로 정규화. */
    public AutolabelShape shapeOrDefault() {
        return shape == null ? AutolabelShape.POLYGON : shape;
    }
}
