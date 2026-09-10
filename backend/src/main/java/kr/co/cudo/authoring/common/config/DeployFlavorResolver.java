package kr.co.cudo.authoring.common.config;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import kr.co.cudo.authoring.common.security.Channel;
import lombok.extern.slf4j.Slf4j;

/**
 * <b>배포 향을 해석하고, 그것으로 인계 토큰의 채널을 확정하는 자리</b> — 판정은 여기 한 곳이다.
 * [@design ADR-012] [@design INT-013] [@design AC-1103]
 *
 * <h2>결정 (2026-09-09 사용자 확정, 구속)</h2>
 * <p><b>포털 채널 배포본에 도착하는 인계 토큰은 항상 포털 채널로 해석한다</b> — 토큰에 채널 값이
 * 실려 있어도 그 값을 따르지 않는다(<b>기본값 전환이 아니라 강제</b>). <b>관제 채널 배포본은
 * 종전대로다</b> — 채널 값이 있으면 그 값을, 없으면 관제 채널로 본다.
 *
 * <h2>왜 토큰이 아니라 배포 향인가</h2>
 * <p>저작도구는 채널마다 별도로 배포되므로 <b>포털 채널 배포본에 도착하는 토큰은 정의상 전부 포털
 * 채널</b>이다. 배포본이 이미 아는 사실을 상대 시스템의 토큰에 실어 달라고 요구하는 것은
 * <b>우리 배포 형상을 상대의 계약으로 만드는 것</b>이다({@code INT-013} 「계약 경계」).
 *
 * <p>또 포털은 사용자 주체 토큰과 시스템 주체 토큰을 <b>같은 공유 서명키로 서명</b>하고 주체를
 * 주체 식별자 클레임으로 가르므로, <b>서명키만으로는 채널 축이 갈리지 않는다.</b>
 *
 * <h2>★되돌리기 전에 알아야 할 것 — 종전 기본값의 근거는 «조건부»였다</h2>
 * <p>종전에는 「채널 값이 없는 토큰은 관제 채널로 본다」가 fail-closed 로 정당화돼 있었다(관제
 * 사용자 토큰 호환 · 외부 노출 없음). <b>그 정당화는 「이 배포본은 관제향」이라는 전제 위에서만
 * 성립한다.</b> 포털 채널 배포본에서는 전제가 뒤집혀 <b>오히려 fail-open</b> 이 된다 — 포털
 * 사용자가 관제 채널 사용자로 해석되어 관제 전용 경로(사용자 역할 조회 · 미배정 사용자 자동 등록 ·
 * 최종 접속 기록 · 주체 식별자 정규화)가 그대로 돌고 정작 포털 전용 창구는 차단된다.
 * <b>2026-09-10 개발망에서 그 서술과 정확히 일치하는 일이 실제로 일어났다</b> — 포털 회원이
 * 저작도구 내부 작업자로 자동 등록됐다.
 *
 * <p>⚠ <b>종전 근거를 지우지 않는다 — 틀렸던 것이 아니라 조건부였다.</b> 관제 채널 배포본에서는
 * 그 근거가 지금도 그대로 참이다({@code ADR-063}).
 *
 * <p>⚠ 특히 <b>포털 회원 식별자와 저작도구 사용자 번호는 서로 다른 체계</b>인데, 관제 채널로
 * 오인되면 포털 회원 식별자가 저작도구 사용자 번호 공간에 그대로 등록된다. <b>값이 겹치면 다른
 * 사용자의 행에 매칭된다.</b>
 */
@Slf4j
@Component
public class DeployFlavorResolver {

    /** 확정된 배포 향. */
    private final DeployFlavor flavor;

    /** 어디서 왔는지 — 기동 로그 전용(운영자가 「왜 이 향인가」를 물을 때의 답). */
    private final String origin;

    /**
     * ⚠ {@code @Autowired} 를 지우지 말 것 — 이 클래스는 생성자가 <b>둘</b>이라(아래 단위 시험용
     * private 생성자) 표식이 없으면 스프링이 어느 것을 쓸지 고르지 못하고
     * {@code No default constructor found} 로 <b>컨텍스트 전체가 기동에 실패</b>한다.
     * ({@link PublicApiPath} 가 같은 형태에서 실제로 겪은 사고다.)
     */
    @Autowired
    public DeployFlavorResolver(@Value(DeployFlavor.VALUE_EXPRESSION) String declared) {
        this.flavor = DeployFlavor.parseOrDefault(declared);
        this.origin = describeOrigin(declared, this.flavor);
    }

    private DeployFlavorResolver(DeployFlavor flavor, String origin) {
        this.flavor = flavor;
        this.origin = origin;
    }

    /**
     * 스프링 없이 쓸 때 — <b>단위 시험 전용</b>.
     *
     * <p>협력자를 생성자로 직접 받는 단위 시험이 이 빈을 주입받지 못하므로, 필드 초기값으로 둘 수
     * 있게 열어 둔다. 스프링이 뜨면 주입이 이 값을 덮는다.
     */
    public static DeployFlavorResolver of(DeployFlavor flavor) {
        return new DeployFlavorResolver(flavor, "단위 시험 지정값");
    }

    /** 종전 동작(관제 향)을 그대로 쓰는 단위 시험용 기본값. */
    public static DeployFlavorResolver ofDefault() {
        return of(DeployFlavor.DEFAULT);
    }

    /**
     * ★<b>기동 시점에 향을 로그로 드러낸다.</b>
     *
     * <p>{@code ADR-012} 가 위험으로 적어 둔 자리다 — <i>"배포 향 선언 누락·오기는 기동 시점이
     * 아니라 인계 진입 시점에야 드러난다"</i>. 이 한 줄이 그 관측 수단이며, 배포 점검 항목이
     * 확인할 대상이다.
     *
     * <p>⚠ <b>값역 밖이었다는 사실이 반드시 드러나야 한다.</b> 오기를 조용히 관제 향으로 떨어뜨리면
     * 「코드는 맞는데 안 고쳐진 것처럼 보이는」 상태가 되어 원인을 찾을 수 없다.
     */
    @PostConstruct
    void announce() {
        log.info("[DeployFlavor] 배포 향 = {} (출처: {})", flavor, origin);
    }

    /** 확정된 배포 향. */
    public DeployFlavor flavor() {
        return flavor;
    }

    /** 포털 채널 배포본인가. */
    public boolean isPortal() {
        return flavor == DeployFlavor.PORTAL;
    }

    /**
     * <b>인계 토큰의 채널을 확정한다</b> — 이 판정의 단일 지점.
     *
     * <p>포털 향이면 인자를 <b>보지 않는다</b>(강제). 관제 향이면 종전 규칙 그대로다 — 값이 있으면
     * 그 값, 없으면 {@link Channel#INTERNAL}.
     *
     * <p>⚠ 관제 향에서 <b>모르는 채널 값</b>은 종전대로 {@link IllegalArgumentException} 이다
     * ({@link Channel#valueOf}). 그 거부를 완화하지 않는다 — 인증 입구라 회귀 0 이 우선이다.
     *
     * @param channelClaim 토큰의 {@code channel} 클레임. 없으면 {@code null}
     */
    public Channel resolveChannel(String channelClaim) {
        if (isPortal()) {
            // ★ 값을 읽지 않는다. 「없으면 포털」이 아니라 「무엇이 실려 있든 포털」이다.
            return Channel.PORTAL;
        }
        return channelClaim == null ? Channel.INTERNAL : Channel.valueOf(channelClaim);
    }

    private static String describeOrigin(String declared, DeployFlavor resolved) {
        if (DeployFlavor.isOutOfRange(declared)) {
            // ⚠ 받은 값을 그대로 싣는다 — 오기를 고치려면 무엇이 들어왔는지 보여야 한다.
            //   이 값은 배포 설정이지 자격증명이 아니라 로그에 남겨도 되는 값이다.
            return "기본값(값역 밖: '" + declared.trim() + "')";
        }
        if (declared == null || declared.trim().isEmpty()) {
            return "기본값(미선언)";
        }
        return "선언(" + DeployFlavor.PROPERTY_KEY + "=" + resolved.name().toLowerCase() + ")";
    }
}
