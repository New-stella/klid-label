package kr.co.cudo.authoring.label.dto;

/**
 * SAM2 Track 결과 + mock 게이트 신호 (C-ISSUE-81, CWE-345).
 *
 * <p>SAM2 분할({@link Sam2SegmentResponse})·YOLO 오토라벨({@code AutolabelOnlineService.AutolabelOutcome})과
 * <b>동일한 규약</b>이다 — ai-server 가 mock 응답(모델 미로드/마스크 미검출)을 반환하면 서비스가 그 프레임을
 * 결과에서 제외하고, 컨트롤러가 {@code ApiResponse.message} 에 안내를 세팅해 FE 자동 적용을 차단한다.
 *
 * <p><b>FE 계약 불변</b>: mock 플래그를 FE-facing DTO({@link Sam2TrackResponseDto}) 컴포넌트로 노출하지
 * 않고 본 outcome 으로만 컨트롤러에 전달한다(세그/오토라벨과 동일한 설계).
 *
 * @param response 자동 적용 가능한 추적 결과(=mock 프레임 제외분)
 * @param mock     mock 응답이 1건이라도 관측됐으면 true
 * @param message  mock 일 때 FE 표시용 안내(비-mock 이면 null)
 */
public record Sam2TrackOutcome(Sam2TrackResponseDto response, boolean mock, String message) {

    /** 전량 신뢰 불가(반환 0건) 안내 — SAM2 세그/오토라벨과 동일 문구. */
    public static final String MOCK_UNAVAILABLE_MESSAGE = Sam2SegmentResponse.MOCK_UNAVAILABLE_MESSAGE;

    /** 일부 프레임만 mock 으로 제외된 경우 안내 — 오토라벨 폴리곤 부분 실패와 동일 문구. */
    public static final String PARTIAL_MOCK_MESSAGE = AutolabelResponse.POLYGON_PARTIAL_MOCK_MESSAGE;

    /** 전 프레임 실모델(mock 없음) 결과 — 안내 메시지 없음. */
    public static Sam2TrackOutcome ok(Sam2TrackResponseDto response) {
        return new Sam2TrackOutcome(response, false, null);
    }

    /**
     * mock 프레임이 1건 이상 제외된 결과. 남은 결과가 없으면 전량 안내, 있으면 부분 안내를 세팅한다.
     */
    public static Sam2TrackOutcome withMock(Sam2TrackResponseDto response) {
        boolean none = response == null || response.tracked() == null || response.tracked().isEmpty();
        return new Sam2TrackOutcome(response, true, none ? MOCK_UNAVAILABLE_MESSAGE : PARTIAL_MOCK_MESSAGE);
    }

    /** mock 관측 여부에 따라 적절한 outcome 을 만든다. */
    public static Sam2TrackOutcome of(Sam2TrackResponseDto response, boolean anyMock) {
        return anyMock ? withMock(response) : ok(response);
    }
}
