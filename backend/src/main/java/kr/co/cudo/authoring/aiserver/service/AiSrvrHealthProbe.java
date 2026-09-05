package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;

/**
 * 노드 하나가 <b>응답하는가</b>만 판정한다. [@design ADR-057]
 *
 * <p>부하 축과 갈라 둔 이유: 이 축은 노드를 <b>내리고 올리는</b> 판정에 쓰이므로 잘못되면 멀쩡한
 * 장비를 잃는다. 반면 부하 축이 틀리면 요청이 한쪽으로 몰릴 뿐이다. 무게가 다른 두 판정이 같은
 * 응답을 공유하면, 한쪽을 관대하게 만드는 변경이 다른 쪽을 조용히 느슨하게 만든다.
 *
 * <p><b>구현은 예외를 던지지 않는다</b> — 응답 없음은 {@code false} 다.
 */
public interface AiSrvrHealthProbe {

    boolean ping(LsAiSrvr server);
}
