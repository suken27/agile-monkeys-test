package com.releasepilot.domain.ports;

/** Opaque reference to a triggered deployment, returned by {@link DeploymentPort}. */
public record DeploymentRef(String value) {
}
