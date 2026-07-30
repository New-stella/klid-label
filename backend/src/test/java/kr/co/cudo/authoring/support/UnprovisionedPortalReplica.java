package kr.co.cudo.authoring.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 테스트의 portal 데이터소스를 <b>복제본 테이블이 없는 빈 DB</b> 로 향하게 한다.
 *
 * <p>기본 테스트 인프라({@link PostgresContainerContextCustomizerFactory})는 control/portal 을 같은
 * 컨테이너의 같은 DB 로 물린다. 그래서 "포털 DB 에 복제본 테이블이 아직 프로비저닝되지 않은 상태"가
 * 테스트에서 <b>구조적으로 재현되지 않았고</b>, 그 결과 미프로비저닝 경로의 결함
 * ({@code isReplicaAvailable()} 이 트랜잭션 안에서 42P01 을 삼켜 커밋 시
 * {@code UnexpectedRollbackException} 을 유발)이 전체 테스트 GREEN 을 통과했다(dev 실측 후 확인).
 *
 * <p>이 애노테이션이 붙은 테스트 클래스는 별도의 빈 DB({@code PostgresTestContainer#unprovisionedPortalJdbcUrl()})
 * 를 portal 로 사용하므로 실제 미프로비저닝 환경과 동일하게 동작한다. Flyway 는 control 데이터소스에만
 * 붙으므로 이 DB 는 계속 비어 있다.
 *
 * <p>컨텍스트 캐시는 customizer 의 equals/hashCode 로 분리되므로 다른 테스트에 영향을 주지 않는다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface UnprovisionedPortalReplica {
}
