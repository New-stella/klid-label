package kr.co.cudo.authoring.common.datasource;

import javax.sql.DataSource;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariDataSource;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * <b>기동 시점에 「어느 DB 를 보고 있는가」를 로그 한 줄로 남긴다.</b>
 *
 * <h2>왜 필요한가 (2026-09-10)</h2>
 * <p>배포 형상이 <b>외부 WAS 에 WAR 를 반입</b>하는 방식이라, 접속 정보가 우리 저장소가 아니라
 * <b>WAS 쪽 설정</b>(시스템 프로퍼티·환경변수)에 있다. 그래서 「지금 뜬 이 앱이 어느 DB 를 보고
 * 있나」를 확인하려면 서버에 들어가 WAS 설정을 뒤져야 했고, 그것이 안 되는 사람은 <b>확인할
 * 방법이 없었다.</b> 실제로 그 질문이 나왔고 답하지 못했다.
 *
 * <p>{@code /actuator/env} 로도 볼 수 있지만 그 창구는 인증이 필요하고 운영에서는 닫아 두는 것이
 * 맞다 — 접속 정보 확인 때문에 그 창구를 열 이유가 없다. 기동 로그 한 줄이면 충분하다.
 * ({@code [PublicApiPath]}·{@code [DeployFlavor]} 와 같은 형태다.)
 *
 * <h2>★ 무엇을 남기고 무엇을 남기지 않나</h2>
 * <p>남기는 것은 <b>어느 서버의 어느 DB 인가</b>뿐이다 — 호스트·포트·DB 이름·스키마·계정.
 * <b>비밀번호는 어떤 형태로도 남기지 않는다</b>(마스킹한 형태조차 두지 않는다 — 길이가 드러나면
 * 그것도 단서다).
 *
 * <p>⚠ JDBC URL 에는 <b>질의 문자열로 비밀번호가 실릴 수 있다</b>({@code ?password=...}).
 * 그래서 URL 을 <b>그대로 찍지 않고</b> 물음표 앞까지만 남긴다 — 이 한 줄이 없으면 비밀번호가
 * 로그로 새는 경로가 된다(CWE-532).
 *
 * <p>⚠ 계정명은 남긴다 — 「권한이 모자란 계정으로 붙었나」가 실제로 자주 나오는 물음이고,
 * 계정명만으로는 접속할 수 없다. 다만 <b>계정명이 곧 비밀번호인 형상</b>이라면 이 판단이
 * 성립하지 않는다(그런 형상을 쓰지 않는다는 전제다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSourceAnnouncer {

    private final @Qualifier("controlDataSource") DataSource controlDataSource;

    @Value("${DB_SCHEMA:klid_at}")
    private String schema;

    @PostConstruct
    void announce() {
        if (!(controlDataSource instanceof HikariDataSource hikari)) {
            // 형이 다르면 URL 을 꺼낼 표준 수단이 없다. 조용히 넘기지 말고 그 사실을 남긴다 —
            // 「로그가 없다」와 「확인할 수 없었다」는 다른 사실이다.
            log.info("[DataSource] 접속 정보를 확인할 수 없다(형: {}) — 스키마 = {}",
                    controlDataSource.getClass().getSimpleName(), schema);
            return;
        }
        log.info("[DataSource] control = {} (스키마 = {}, 계정 = {})",
                sanitize(hikari.getJdbcUrl()), schema, hikari.getUsername());
    }

    /**
     * JDBC URL 에서 <b>질의 문자열을 잘라낸다</b> — 거기에 비밀번호가 실릴 수 있다.
     *
     * @param url 원본 JDBC URL. {@code null} 이면 그 사실을 그대로 표시한다(빈 문자열로 뭉개지 않는다)
     */
    private static String sanitize(String url) {
        if (url == null || url.isBlank()) {
            return "(미설정)";
        }
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }
}
