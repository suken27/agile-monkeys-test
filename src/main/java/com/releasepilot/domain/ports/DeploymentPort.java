package com.releasepilot.domain.ports;

import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.Environment;
import com.releasepilot.domain.promotion.Version;

/**
 * Output port: a capability the domain requires of the outside world to trigger a deployment.
 * Implemented by an infrastructure adapter (e.g. an in-memory stub, or later a real CI/CD
 * client); the domain and application layers depend only on this abstraction.
 */
public interface DeploymentPort {

	DeploymentRef trigger(ApplicationId applicationId, Version version, Environment environment);
}
