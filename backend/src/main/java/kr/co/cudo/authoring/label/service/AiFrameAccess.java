package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

/**
 * 인터랙티브 AI 추론(AI 탐지 · AI 분할 · AI 자동 추적)의 <b>채널별 입력 경계</b> — 인가 · 입력 이미지 해석 ·
 * 차단 판정을 추론 본체에서 떼어 낸 전략이다.
 *
 * <h3>왜 나누는가</h3>
 * 추론 본체(검출 클래스 서버측 재구성 · ai-server 호출 · bulkhead · 취소 · 좌표 정규화 · 폴리곤 상한/예산 ·
 * 추적 시간 예산 절단 · mock 차단 · 응답 조립 · 프레임 단위 동시 중복 차단)는 채널과 무관하다. 반면
 * <b>누가 이 프레임에 접근할 수 있는가</b>와 <b>어떤 파일을 추론 입력으로 보내는가</b>, <b>무엇이 추론을 막는가</b>는
 * 채널마다 다르다. 본체를 채널마다 복제하면 다음 변경 때 한쪽만 고쳐지므로, 채널이 갈아끼우는 부분만
 * 이 인터페이스로 받는다.
 *
 * <ul>
 *   <li><b>내부 채널</b> — 각 서비스가 요청마다 만드는 구현이다: 본인 배정 인가
 *       ({@link LabelAccessGuard}) + 비식별 누락 신고 게이트를 거친 이미지({@link FrameImageEncoder}) +
 *       (AI 탐지) 작업락·신고 상태 차단.</li>
 *   <li><b>외부(포털) 채널</b> — 포털 패키지가 자기 작업 대상 판정과 <b>포털 이미지 서빙과 같은 파일</b>로
 *       구현한다.</li>
 * </ul>
 *
 * <h3>구현 계약</h3>
 * <ul>
 *   <li>한 인스턴스는 <b>한 요청·한 행위자</b>에 묶인다(행위자를 인자로 받지 않는다).</li>
 *   <li>{@link #authorize} 는 대상 프레임마다 호출된다 — 자동 추적은 시작 프레임과 <b>후속 프레임 전부</b>에
 *       대해 부른다. 인가 실패·부재는 구현이 자기 채널 규약의 {@link CustomException} 으로 끝낸다.
 *       ai-server 호출 전에 끝나야 하며 본체는 그 순서를 지킨다.</li>
 *   <li>{@link #resolveImage}·{@link #encodeImage} 는 <b>인가를 통과한 프레임</b>만 받는다. 반환하는 파일은
 *       기준 디렉터리 안으로 실경로 검증이 끝난 것이어야 한다(경로 순회 CWE-22 · 링크 CWE-59).
 *       본체는 경로를 스스로 조립하지 않는다.</li>
 *   <li>예외 메시지에 내부 경로·원문을 싣지 않는다(CWE-209).</li>
 * </ul>
 */
public interface AiFrameAccess {

    /**
     * 인가 + 프레임 조회. 통과한 프레임 엔티티를 돌려준다.
     *
     * @throws CustomException 인가 실패(403 등) · 프레임 부재(404) — 코드는 채널 규약을 따른다
     */
    LsDataSrc authorize(Long srcSn);

    /**
     * 추론 입력 이미지 파일 해석 — 크기 상한·실측 해상도처럼 <b>파일 단위</b> 판정이 필요한 본체(AI 분할)가 쓴다.
     * 채널의 이미지 게이트는 이 안에서 끝난다(파일을 읽기 전 차단).
     *
     * @return 존재하고 실경로 검증이 끝난 이미지 파일
     */
    Path resolveImage(LsDataSrc frame);

    /**
     * 추론 입력 이미지를 base64 로 인코딩한다. 결과는 전량 ai-server 요청 본문으로 나간다.
     *
     * <p>기본 구현은 {@link #resolveImage} 가 돌려준 파일을 그대로 읽는다 — 게이트는 그 해석 안에 있으므로
     * 경로를 받는 게이트 없는 인코더를 따로 두지 않는다. 채널이 파일 열기 규약(링크 비추종 등)을 따로 가지면
     * 이 메서드를 재정의한다.
     */
    default String encodeImage(LsDataSrc frame) {
        Path imagePath = resolveImage(frame);
        try {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(imagePath));
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "이미지 읽기에 실패했습니다.");
        }
    }

    /**
     * 검출 좌표 clamp 상한 기준 — 프레임 이미지 실측 {@code [width, height]}.
     *
     * <p>AI 탐지·자동 추적 본체가 ai-server 응답 박스를 이미지 경계로 정규화할 때 쓴다. 치수는
     * <b>추론 입력으로 보낸 것과 같은 파일</b>에서 나와야 한다 — 채널마다 저장 기준 디렉터리가 달라,
     * 다른 채널의 해석기로 재면 엉뚱한 파일(또는 부재)을 잰다. 그래서 기본 구현을 두지 않는다.
     *
     * @return 측정 불가면 {@link Optional#empty()} — 본체는 상한 clamp 만 생략하고 하한은 유지한다(fail-open)
     */
    Optional<int[]> resolveBounds(LsDataSrc frame);

    /**
     * 진입 · 중간(프레임/박스 반복마다) · 마감(응답 조립 직전) 공통 차단 판정.
     *
     * <p>AI 호출이 길게 블로킹되는 사이 상태가 바뀔 수 있어(TOCTOU) 본체가 여러 번 부른다. 막을 것이 없는
     * 채널은 재정의하지 않는다(기본 통과).
     *
     * @param rawSn 대상 프레임이 속한 영상
     * @throws CustomException 차단(코드는 채널 규약을 따른다)
     */
    default void requireNotBlocked(Long rawSn) {
        // 기본 — 막을 것이 없다.
    }
}
