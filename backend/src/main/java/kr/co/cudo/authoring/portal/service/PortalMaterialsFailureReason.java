package kr.co.cudo.authoring.portal.service;

/**
 * 조달이 실패한 사유 — <b>값으로 보존</b>한다.
 *
 * <p>사유를 메시지 문자열에만 담으면 소비 계층이 그것을 파싱하게 되고, 문구가 바뀌는 순간 조용히
 * 오분류된다. 그래서 화면·응답이 보는 것은 이 enum 이다.
 *
 * <p>⚠ 어느 값도 <b>경로·키·외부 응답 본문 원문</b>을 담지 않는다(CWE-209). 사유 분류만으로
 * 운영자가 다음 조치를 고를 수 있게 하는 것이 이 목록의 기준이다.
 *
 * @design INT-014
 */
public enum PortalMaterialsFailureReason {

    /** 조달이 구성되지 않았다(연동 주소 또는 키 미설정) — 설정을 채워야 풀린다. */
    NOT_CONFIGURED,

    /** 포털이 거부했다(키 불일치·없는 데이터셋 등 4xx) — 다시 물어도 같다. */
    FETCH_REJECTED,

    /** 포털 조회가 실패했다(5xx·타임아웃·네트워크) — 잠시 뒤 다시 시도할 수 있다. */
    FETCH_FAILED,

    /** 응답에 배포 압축본이 없다 — 포털 쪽 소재 구성 문제다. */
    NO_DEPLOYMENT_ZIP,

    /**
     * 소재 경로가 저장소 루트 밖이거나 열 수 없다 — <b>열지 않고 멈춘 것</b>이다(fail-closed).
     *
     * <p>세부 판정은 {@code PortalMaterialsPathGuard.Verdict} 가 갖고 있으며 서버 로그에만 남는다.
     */
    MATERIAL_PATH_REJECTED,

    /** 압축 해제를 거부했다(경로 탈출·항목 수·총량 상한·손상). */
    UNPACK_REJECTED,

    /** 복사·해제 중 입출력이 실패했다. */
    IO_ERROR
}
