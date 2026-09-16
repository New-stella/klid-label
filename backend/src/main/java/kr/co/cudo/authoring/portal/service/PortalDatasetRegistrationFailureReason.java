package kr.co.cudo.authoring.portal.service;

/**
 * 데이터셋 영상 원장 등록이 실패한 사유 — <b>값으로 보존</b>한다(메시지 파싱 금지).
 *
 * <p>⚠ 어느 값도 경로·파일명·문서 원문을 담지 않는다(CWE-209). 운영자가 다음 조치를 고를 수 있을 만큼만
 * 가른다 — 앞의 값들은 <b>배포본 구성이 가정과 다르다</b>는 뜻이고(ADR-068 은 구성을 가정했다), 뒤의 셋은
 * 구성은 맞았는데 적재가 끝나지 않은 것이다.
 *
 * @design ADR-068
 */
public enum PortalDatasetRegistrationFailureReason {

    /** 해제본 자리가 없다 — 소재 해제본이 공개되지 않았거나 지워졌다. */
    CONTENT_MISSING,

    /** 해제본 안에 심링크가 있다 — 따라가지 않고 멈춘다(CWE-59). */
    SYMLINK_REJECTED,

    /** 등록할 수 있는 영상이 한 건도 없다(영상 폴더 → 버전 폴더 → 비식별 이미지 폴더 구성이 없다). */
    NO_VIDEO,

    /** 같은 영상 키가 서로 다른 자리에 있다 — 어느 쪽인지 추측하지 않는다. */
    AMBIGUOUS_VIDEO_KEY,

    /** 영상 키로 클립 식별자를 만들 수 없다(비었거나 허용 길이를 넘는다). */
    INVALID_VIDEO_KEY,

    /** 프레임 이미지와 같은 이름의 라벨 문서 짝이 맞지 않는다(한쪽만 있거나 같은 번호가 둘이다). */
    PAIR_MISMATCH,

    /** 라벨 문서를 NIA 어노테이션 문서로 읽을 수 없다. */
    DOCUMENT_UNREADABLE,

    /** 프레임 이미지가 JPEG 가 아니다. */
    IMAGE_NOT_JPEG,

    /** 문서 값이 원장에 앉을 수 없다(좌표 형식·길이 상한 위반 등) — 잘라 넣거나 고쳐 넣지 않는다. */
    INVALID_VALUE,

    /** 등록 토글이 꺼져 있다 — 운영자가 등록을 멈췄다. */
    DISABLED,

    /** 파일 복사·읽기 중 입출력이 실패했다. */
    IO_ERROR,

    /** 원장 적재가 실패했다. */
    PERSIST_FAILED
}
