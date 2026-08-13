package kr.co.cudo.authoring.architecture;

import kr.co.cudo.authoring.support.MainResourceYaml;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quartz 클러스터링 설정 가드 — <b>실제 yml·배포 템플릿 파일</b>을 파싱해 고정한다(B-ISSUE-81 / B-ISSUE-02).
 *
 * <p>배경: 배포 토폴로지는 "주 서버 2노드 Active-Active + Quartz 클러스터링" 인데 설정은 전 프로파일이
 * {@code isClustered=false} 였다. {@code @DisallowConcurrentExecution} 은 <b>스케줄러 인스턴스 내부에서만</b>
 * 유효하므로, 두 노드가 관제 학습용 스캔(60s)·KPST 폴링(30s)·export sweep(600s) 트리거를 <b>각각</b> 발화했다.
 *
 * <p>프로퍼티를 테스트에서 직접 주입하는 방식({@code withPropertyValues})으로는 <b>실 yml 회귀를 잡지
 * 못한다</b>(과거 전례). 그래서 {@link MainResourceYaml} 로 운영 yml 원본을 그대로 읽어 판정한다.
 */
class QuartzClusteringConfigGuardTest {

    private static final String COMMON_YML = "application.yml";
    private static final String PREFIX = "spring.quartz.properties.";
    private static final String CLUSTERED = PREFIX + "org.quartz.jobStore.isClustered";
    private static final String CHECKIN_INTERVAL = PREFIX + "org.quartz.jobStore.clusterCheckinInterval";
    private static final String DELEGATE = PREFIX + "org.quartz.jobStore.driverDelegateClass";
    private static final String USE_PROPERTIES = PREFIX + "org.quartz.jobStore.useProperties";
    private static final String INSTANCE_ID = PREFIX + "org.quartz.scheduler.instanceId";
    private static final String INSTANCE_NAME = PREFIX + "org.quartz.scheduler.instanceName";

    /** QRTZ_LOCKS 락 행을 시딩하는 마이그레이션 — SCHED_NAME 이 instanceName 과 일치해야 한다. */
    private static final Path LOCK_SEED_SQL =
            Paths.get("src/test/resources/db-archive/migration/V76__seed_qrtz_locks.sql");

    /** 온프렘 배포 환경변수 템플릿(모듈 밖 — Gradle test 작업 디렉토리는 backend 모듈 루트). */
    private static final Path ENV_TEMPLATE =
            Paths.get("../deploy/onprem/config/backend/env.template");

    @Test
    @DisplayName("stg_prd_yml_에_클러스터링이_켜져있다")
    void deployedProfilesEnableClustering() {
        for (String profileYml : List.of("application-stg.yml", "application-prd.yml")) {
            // given: 공통 yml 위에 배포 프로파일을 얹은 실효 설정
            Environment env = MainResourceYaml.environment(COMMON_YML, profileYml);

            // then: 2노드 Active-Active 에서 트리거가 1회만 발화하려면 클러스터링이 켜져 있어야 한다
            assertThat(env.getProperty(CLUSTERED))
                    .as("%s 의 Quartz 클러스터링 실효값(공통 기본값 false 를 상속하면 2노드 중복 발화)", profileYml)
                    .isEqualTo("true");

            // and: 공통 상속이 아니라 프로파일 yml 이 직접 선언해야 한다(공통값이 바뀌어도 운영이 따라 꺼지지 않도록)
            assertThat(MainResourceYaml.keys(profileYml))
                    .as("%s 는 클러스터링을 명시 override 해야 한다", profileYml)
                    .contains(CLUSTERED);
        }
    }

    @Test
    @DisplayName("local_dev_는_단일노드_기본값을_유지한다")
    void singleNodeProfilesKeepClusteringOff() {
        for (String profileYml : List.of("application-local.yml", "application-dev.yml")) {
            Environment env = MainResourceYaml.environment(COMMON_YML, profileYml);

            assertThat(env.getProperty(CLUSTERED))
                    .as("%s 는 단일 노드이므로 클러스터링 없이 동작해야 한다(회귀 방어)", profileYml)
                    .isEqualTo("false");
        }
    }

    @Test
    @DisplayName("클러스터링_부가설정이_JobStore와_정합한다")
    void clusteringSupportSettingsAreCoherent() {
        // given: 클러스터링은 PostgreSQL JobStore + 노드별 고유 instanceId + 공유 락 행을 전제로 한다
        Environment env = MainResourceYaml.environment(COMMON_YML, "application-prd.yml");

        // then: PostgreSQL delegate(BYTEA) — CLAUDE.md 확정 형상
        assertThat(env.getProperty(DELEGATE))
                .isEqualTo("org.quartz.impl.jdbcjobstore.PostgreSQLDelegate");
        assertThat(env.getProperty(USE_PROPERTIES))
                .as("useProperties=true 여야 JobDataMap 이 직렬화 BLOB 대신 문자열로 저장된다")
                .isEqualTo("true");

        // and: 노드마다 고유 인스턴스 ID 가 있어야 클러스터 체크인이 노드를 구분한다
        assertThat(env.getProperty(INSTANCE_ID))
                .as("클러스터에서 instanceId 를 고정값으로 두면 두 노드가 같은 노드로 인식된다")
                .isEqualTo("AUTO");

        // and: 체크인 주기는 양수 + 장애 감지 지연을 30초 이내로 유지
        String checkin = env.getProperty(CHECKIN_INTERVAL);
        assertThat(checkin).as("clusterCheckinInterval 미설정").isNotNull();
        assertThat(Long.parseLong(checkin))
                .as("clusterCheckinInterval(ms) — 양수 + 30초 이내(노드 실패 감지 지연)")
                .isPositive()
                .isLessThanOrEqualTo(30_000L);

        // and: 락 행 시드(V76)의 SCHED_NAME 과 instanceName 이 일치해야 클러스터 락이 성립한다
        String instanceName = env.getProperty(INSTANCE_NAME);
        assertThat(instanceName).isNotBlank();
        assertThat(read(LOCK_SEED_SQL))
                .as("V76 QRTZ_LOCKS 시드의 SCHED_NAME 이 instanceName(%s) 과 달라 클러스터 락이 성립하지 않는다",
                        instanceName)
                .contains("'" + instanceName + "'");
    }

    @Test
    @DisplayName("배포_템플릿이_클러스터링을_활성값으로_제공한다")
    void deployTemplateShipsClusteringEnabled() {
        // given
        assertThat(Files.isReadable(ENV_TEMPLATE))
                .as("배포 환경변수 템플릿을 찾을 수 없다: %s", ENV_TEMPLATE)
                .isTrue();
        String template = read(ENV_TEMPLATE);

        // then: 온프렘(stg/prd)은 2노드 Active-Active 이므로 템플릿 기본값이 켜져 있어야 한다
        assertThat(template)
                .as("env.template 이 QUARTZ_CLUSTERED=false 를 배포하면 stg/prd 기동이 거부된다")
                .contains("QUARTZ_CLUSTERED=true")
                .doesNotContain("QUARTZ_CLUSTERED=false");

        // and: 클러스터 모드 전제인 노드 간 시계 동기(NTP) 안내가 남아 있어야 한다
        assertThat(template)
                .as("클러스터링은 노드 간 시계 동기가 전제다 — 운영 안내를 템플릿에 유지한다")
                .contains("NTP");
    }

    @Test
    @DisplayName("배포_템플릿이_배포환경_표식_ENV_를_주입한다")
    void deployTemplateShipsDeployedEnvMarker() {
        // given: 가드의 두 번째 축(ENV)은 <실제로 주입될 때만> 작동한다
        String template = read(ENV_TEMPLATE);

        // then: 템플릿이 ENV 항목을 배포 표식값으로 제공해야 한다. 없으면 systemd EnvironmentFile 에도
        //       ENV 가 없어 프로파일 축만 남고, SPRING_PROFILES_ACTIVE=dev 로 뜬 배포 노드가 가드를
        //       통과해 클러스터링 off 인 채 2노드 중복 발화한다(서버 잔존 .env 가 프로파일을 덮은 실사고 이력).
        assertThat(template)
                .as("env.template 에 ENV 배포 표식이 없으면 QuartzClusteringGuard/DevProfileGuard 의 ENV 축이 무력하다")
                .containsPattern("(?m)^ENV=(stg|prd)$");
    }

    private String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("파일 읽기 실패: " + path, e);
        }
    }
}
