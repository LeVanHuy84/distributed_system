rootProject.name = "flashorder"

include(
    ":platform:common-events",
    ":platform:common-observability",
    ":services:order-service",
    ":services:inventory-service",
    ":services:payment-service"
)
