rootProject.name = "services"

// Services
include(
    "user-api",
    "mobile-api",
    "connectors-api"
)

// libs
include(
    ":libs:common"
)