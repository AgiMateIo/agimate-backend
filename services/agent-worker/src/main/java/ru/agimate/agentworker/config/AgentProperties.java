package ru.agimate.agentworker.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Worker configuration, bound from {@code application.yaml} under the {@code agent}
 * prefix. Defaults live in the yaml (plain values); every value is overridable via
 * environment variables through Spring's relaxed binding (e.g. {@code agent.grpc.target}
 * ← {@code AGENT_GRPC_TARGET}). Secrets (auth token, DB url) are supplied via env only.
 */
@Component
@ConfigurationProperties(prefix = "agent")
@Getter
@Setter
public class AgentProperties {

    private Grpc grpc = new Grpc();
    private Agent agent = new Agent();
    private Concurrency concurrency = new Concurrency();
    private App app = new App();
    private Tool tool = new Tool();
    private Dbos dbos = new Dbos();

    /** gRPC channel to control-api's worker protocol (:9091, TLS). */
    @Getter
    @Setter
    public static class Grpc {
        private String target = "localhost:9091";
        private boolean useTls = false;
        /** PEM CA cert path for TLS verification; null → system trust store. */
        private String caCert;
        /** Bearer token (worker-pool authkey) sent in the "authorization" metadata; empty disables auth. */
        private String authToken = "";
        private Duration requestTimeout = Duration.ofSeconds(10);
        private Duration connectTimeout = Duration.ofSeconds(5);
    }

    /** Identity of this worker/agent deployment. */
    @Getter
    @Setter
    public static class Agent {
        private String id = "agent-default";
        private String workflowId = "wf-default";
    }

    /**
     * Per-queue {@code worker_concurrency} — the per-worker (per-executor) cap. The
     * cluster-wide ceiling is the value × the number of workers sharing the same
     * DBOS application version.
     */
    @Getter
    @Setter
    public static class Concurrency {
        /** Concurrent model requests per worker. */
        private int llm = 3;
        /** Concurrent backend tool calls per worker. */
        private int tool = 8;
    }

    /** Identity advertised to LLM providers (User-Agent + OpenRouter app attribution). */
    @Getter
    @Setter
    public static class App {
        private String url = "https://agimate.io";
        private String title = "AgiMate";
        private String category = "cloud-agent";
        private String userAgent = "AgiMate managed agent (agimate.io)";
    }

    /** Параметры выполнения бэкенд-тулов. */
    @Getter
    @Setter
    public static class Tool {
        /**
         * Poll budget for one backend tool call ({@code GetToolResult}); a tool that has not
         * finished within it is reported to the model as failed (the backend job may still
         * complete — the timeout does not cancel it).
         */
        private Duration pollTimeout = Duration.ofSeconds(60);
        /**
         * Cap on one tool output, in chars; longer output is cut with an explicit truncation
         * marker. Bounds both the model context (the output rides in every following turn) and
         * the DBOS checkpoints (tool outcome + each {@code llm_call} child input).
         */
        private int maxOutputChars = 64_000;
    }

    /**
     * DBOS system database + versioning. Must point at the same Postgres/schema the producer
     * (control-api) enqueues into. Credentials are supplied via env (AGENT_DBOS_USERNAME/PASSWORD).
     */
    @Getter
    @Setter
    public static class Dbos {
        private String appName = "agent-worker";
        /** JDBC URL of the shared DBOS Postgres; supplied via AGENT_DBOS_DATABASE_URL. */
        private String databaseUrl;
        private String username;
        private String password;
        private String schema = "dbos";
        /** Explicit DBOS application version; null → DBOS auto-hashes the code. */
        private String applicationVersion;
        /**
         * How long finished workflows (checkpoints included) are kept in the system database;
         * zero or negative disables the purge. Business data is persisted in control-api
         * synchronously with the run, so this bounds only the operational/recovery window.
         */
        private Duration retention = Duration.ofDays(7);
    }
}
