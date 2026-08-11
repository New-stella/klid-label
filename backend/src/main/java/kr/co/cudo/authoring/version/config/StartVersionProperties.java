package kr.co.cudo.authoring.version.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 영상 단위 「시작 버전 선택」 자원 상한 설정 ({@code authoring.version.start-version.*}).
 *
 * <h3>왜 상한이 필요한가 (CWE-770)</h3>
 * 이 작업은 요청 1건이 <b>영상의 전 프레임</b>을 한 트랜잭션에서 처리하며 프레임 행 락을 커밋까지
 * 보유한다. 프레임당 스냅샷 본문은 최대 10MB({@code VersionService.MAX_DEIDENT_PAYLOAD_BYTES})이고,
 * 내부 파이프라인 영상의 프레임 수에는 상한이 없다(포털 경로만 자체 상한을 갖는다). 상한이 없으면
 * 요청 1건이 커넥션을 장시간 점유해 그 영상의 라벨 저장·승인을 전면 블록하고, 승인 영상이면 호출마다
 * 산출물 전량 재생성(이미지 2벌)을 유발한다.
 *
 * <h3>fail-closed 검증 — 오설정이면 기동을 거부한다</h3>
 * 이 설정이 무너지는 방향은 둘 다 나쁘다: 0/음수면 <b>모든 요청이 거부</b>되어 기능이 죽고, 값이
 * 비어 바인딩이 어긋나면 상한이 <b>사라진다</b>. 이 저장소에는 {@code .env.example} 의 <b>빈 값</b>이
 * {@code ${KEY:default}} 를 무력화해 운영에서만 조용히 다르게 동작한 실사고가 있다 — 그래서 경고가
 * 아니라 {@code @PostConstruct} 기동 차단이다({@code AugmentDiscardProperties} 와 동형).
 *
 * <p>판정은 순수 메서드라 컨테이너 없이 단위 검증할 수 있다.
 *
 * @param maxFrames 요청 1건이 처리할 수 있는 최대 프레임 수. 초과 요청은 {@code 400} 으로 거부한다
 *                  (조용히 잘라내지 않는다 — 일부만 되돌아간 영상은 혼합 상태가 된다).
 * @design D5
 * @req R6
 */
@ConfigurationProperties(prefix = "authoring.version.start-version")
public record StartVersionProperties(
        @DefaultValue("2000") int maxFrames
) {

    /** 프레임 상한의 하한. 1 미만이면 어떤 영상도 되돌릴 수 없어 기능 자체가 도달 불가가 된다. */
    public static final int MIN_MAX_FRAMES = 1;

    @PostConstruct
    public void validate() {
        if (maxFrames < MIN_MAX_FRAMES) {
            throw new IllegalStateException(
                    "authoring.version.start-version.max-frames=" + maxFrames + " 는 허용되지 않습니다"
                            + " (최소 " + MIN_MAX_FRAMES + "). 0 이하는 모든 시작 버전 적용을 거부해"
                            + " 기능을 도달 불가로 만듭니다. 값을 비우면(빈 문자열) 기본값이 적용되지"
                            + " 않으니 .env / application-{profile}.yml 에 값을 명시하세요.");
        }
    }
}
