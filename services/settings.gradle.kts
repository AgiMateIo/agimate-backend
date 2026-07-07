rootProject.name = "services"

// Services
include(
    "user-api",
    "control-api",
    "agent-worker"
)

// libs
include(
    ":libs:common",
    ":libs:agentworker-proto"
)