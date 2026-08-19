package kr.co.cudo.authoring;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * 외부 WAS(서블릿 컨테이너) 배포용 진입점. [@design DEPLOY-001]
 *
 * <p>실행 가능 JAR 는 {@link AuthoringApplication#main} 이 내장 톰캣을 띄우지만, WAR 로 반입하면
 * 컨테이너가 그 {@code main} 을 부르지 않는다. 서블릿 컨테이너는 {@code ServletContainerInitializer}
 * 를 통해 이 클래스를 찾아 애플리케이션을 기동한다. <b>이 클래스가 없으면 WAR 는 정상 배포된 것처럼
 * 보이지만 스프링 컨텍스트가 아예 뜨지 않아 모든 요청이 404 가 된다</b> — 오류 로그도 남지 않아
 * 원인을 찾기 어렵다.
 *
 * <p><b>컨텍스트 경로는 여기서 정해지지 않는다.</b> {@code server.servlet.context-path} 는 내장
 * 서버 전용이라 WAR 배포에서는 무시되고, 컨텍스트는 WAR 파일명(또는 컨테이너의 컨텍스트 설정)이
 * 정한다. 현재 API 주소가 전부 {@code /api} 하위이므로 산출물 이름을 {@code api.war} 로 고정해
 * 주소를 유지한다(build.gradle 의 {@code bootWar.archiveFileName}).
 *
 * <p><b>내장 톰캣용 설정은 이 경로에서 적용되지 않는다.</b> {@code server.tomcat.*}(대용량 업로드를
 * 위한 swallow-size·form-post-size 해제, 요청 스레드 예산)는 WAS 자체 설정으로 옮겨야 한다.
 * 옮기지 않아도 기동과 일반 요청은 정상이라 배포 시점에 드러나지 않고, 대용량 업로드에서만 실패한다.
 */
public class ServletInitializer extends SpringBootServletInitializer {

    /**
     * 외부 WAS 로 기동됐다는 표식. [@design DEPLOY-001]
     *
     * <p>이 경로에서만 참이다 — 실행 가능 JAR 로 띄우면 이 클래스가 호출되지 않는다.
     * 보안 가드가 "WAS 자체 설정은 내가 볼 수 없다"는 사실을 알려야 할 때 쓴다
     * ({@code ForwardedHeadersConfigGuard}).
     *
     * <p>정적 필드인 이유: 이 클래스는 스프링 빈이 아니라 서블릿 컨테이너가 직접 만드는
     * 진입점이라 컨텍스트에 값을 넘길 통로가 없다. 프로세스당 한 번만 쓰이고 되돌리지 않는다.
     */
    private static volatile boolean deployedAsWar = false;

    /** 외부 WAS 배포 여부. */
    public static boolean isDeployedAsWar() {
        return deployedAsWar;
    }

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
        deployedAsWar = true;
        return application.sources(AuthoringApplication.class);
    }
}
