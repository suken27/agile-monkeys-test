package com.releasepilot.infrastructure.adapters.inmemory;

import com.releasepilot.domain.ports.DeploymentPort;
import com.releasepilot.domain.ports.DeploymentRef;
import com.releasepilot.domain.promotion.ApplicationId;
import com.releasepilot.domain.promotion.Environment;
import com.releasepilot.domain.promotion.Version;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory stub adapter for {@link DeploymentPort} (SPECS §6). Returns a fake, incrementing
 * {@link DeploymentRef} and records every call so tests can assert on what was triggered — there
 * is no outbound HTTP in this exercise; a real CI/CD client would implement the same port.
 */
@Component
public class InMemoryDeploymentAdapter implements DeploymentPort {

	private final AtomicLong sequence = new AtomicLong();
	private final List<TriggeredDeployment> triggeredDeployments = new CopyOnWriteArrayList<>();

	@Override
	public DeploymentRef trigger(ApplicationId applicationId, Version version, Environment environment) {
		DeploymentRef ref = new DeploymentRef("deploy-" + sequence.incrementAndGet());
		triggeredDeployments.add(new TriggeredDeployment(applicationId, version, environment, ref));
		return ref;
	}

	/** The deployments triggered so far, oldest first — for test assertions. */
	public List<TriggeredDeployment> triggeredDeployments() {
		return List.copyOf(triggeredDeployments);
	}

	public record TriggeredDeployment(
			ApplicationId applicationId, Version version, Environment environment, DeploymentRef deploymentRef) {
	}
}
