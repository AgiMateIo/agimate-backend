package ru.agimate.agentworker.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.agentworker.LlmCredentials;
import ru.agimate.agentworker.config.AgentProperties;
import ru.agimate.common.net.TargetNotAllowedException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("ModelFactory — base_url провайдера как недоверенный адрес")
class ModelFactoryTargetsTest {

    private final ModelFactory factory = new ModelFactory(new AgentProperties());

    private static LlmCredentials creds(String baseUrl) {
        return LlmCredentials.newBuilder()
                .setProviderType("openai_compatible")
                .setBaseUrl(baseUrl)
                .setApiKey("sk-test")
                .setModel("gpt-4o-mini")
                .setProviderId("prov-1")
                .build();
    }

    @Test
    @DisplayName("адрес внутрь сети отвергается: туда уехал бы ключ, а ответ попал бы в историю агента")
    void rejectsPrivateBaseUrl() {
        assertThrows(TargetNotAllowedException.class,
                () -> factory.build(creds("http://169.254.169.254/v1")));
        assertThrows(TargetNotAllowedException.class,
                () -> factory.build(creds("https://10.0.0.5/v1")));
    }

    @Test
    @DisplayName("plain http отвергается: проверка адреса висит на TLS-сокете и без него не сработает")
    void rejectsPlainHttp() {
        assertThrows(TargetNotAllowedException.class,
                () -> factory.build(creds("http://api.openai.com/v1")));
    }

    @Test
    @DisplayName("с allow-private-targets=true проверка снимается целиком")
    void permissiveAllowsLocalModels() {
        AgentProperties props = new AgentProperties();
        props.getNet().setAllowPrivateTargets(true);
        assertDoesNotThrow(() -> new ModelFactory(props).build(creds("http://127.0.0.1:11434/v1")));
    }
}
