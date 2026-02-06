rootProject.name = "services"

// Services
include(
    "user-api",
    "device-api",
    "connectors-api"
)

// libs
include(
    ":libs:common"
)