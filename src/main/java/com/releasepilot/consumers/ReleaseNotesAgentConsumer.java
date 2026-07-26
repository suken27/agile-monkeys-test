package com.releasepilot.consumers;

import com.releasepilot.application.queries.PromotionReadModelPort;
import com.releasepilot.domain.ports.AgentContext;
import com.releasepilot.domain.ports.AgentDecision;
import com.releasepilot.domain.ports.IssueTrackerPort;
import com.releasepilot.domain.ports.ReleaseNotesLlmPort;
import com.releasepilot.domain.promotion.DomainEvent;
import com.releasepilot.domain.promotion.Promotion;
import com.releasepilot.domain.promotion.Version;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Release-notes agent consumer (SPECS §9, optional): the only consumer triggered by a single event
 * type, {@code PromotionApproved} — every other event delivered off the fanout exchange is a no-op.
 * Drives a genuine tool-calling loop rather than a single completion: repeatedly asks
 * {@link ReleaseNotesLlmPort} for the next {@link AgentDecision}, executes whichever tool it names,
 * appends the result to the {@link AgentContext} transcript, and hands the updated context back —
 * until the LLM returns {@link AgentDecision.Done} instead of another tool call.
 *
 * <p>Deliberately not a {@code @RabbitListener} bean itself, matching {@link AuditLogConsumer} and
 * {@link ReadModelProjector} — depends only on its ports and a {@link JdbcTemplate}, which keeps the
 * loop directly testable (SPECS §14.3) without a broker; the queue transport that decodes a message
 * into a {@code DomainEvent} and calls {@link #onEvent} is a separate, thin adapter.
 */
public class ReleaseNotesAgentConsumer {

	private static final int HISTORY_PAGE_SIZE = 5;

	private final ReleaseNotesLlmPort llm;
	private final IssueTrackerPort issueTracker;
	private final PromotionReadModelPort readModel;
	private final JdbcTemplate jdbcTemplate;

	public ReleaseNotesAgentConsumer(
			ReleaseNotesLlmPort llm, IssueTrackerPort issueTracker, PromotionReadModelPort readModel,
			JdbcTemplate jdbcTemplate) {
		this.llm = llm;
		this.issueTracker = issueTracker;
		this.readModel = readModel;
		this.jdbcTemplate = jdbcTemplate;
	}

	public void onEvent(DomainEvent event) {
		if (!Promotion.EVENT_PROMOTION_APPROVED.equals(event.eventType())) {
			return;
		}

		Version version = readModel.findPromotionDetail(event.promotionId())
				.orElseThrow(() -> new IllegalStateException(
						"No promotion_detail row for approved promotion " + event.promotionId().value()))
				.version();

		AgentContext context = AgentContext.start(event.promotionId(), event.applicationId(), version);

		AgentDecision decision = llm.decide(context);
		while (decision instanceof AgentDecision.CallTool callTool) {
			Object result = executeTool(callTool, context);
			context = context.withToolResult(callTool.toolName(), result);
			decision = llm.decide(context);
		}
	}

	private Object executeTool(AgentDecision.CallTool callTool, AgentContext context) {
		return switch (callTool.toolName()) {
			case ReleaseNotesLlmPort.TOOL_GET_LINKED_WORK_ITEMS ->
					issueTracker.getLinkedWorkItems(context.applicationId(), context.version());
			case ReleaseNotesLlmPort.TOOL_GET_PROMOTION_HISTORY ->
					readModel.findApplicationPromotions(context.applicationId(), 0, HISTORY_PAGE_SIZE).items();
			case ReleaseNotesLlmPort.TOOL_SAVE_RELEASE_NOTES -> {
				saveReleaseNotes(context, (String) callTool.arguments().get("draftText"));
				yield null;
			}
			default -> throw new IllegalArgumentException("Unknown tool: " + callTool.toolName());
		};
	}

	private void saveReleaseNotes(AgentContext context, String draftText) {
		jdbcTemplate.update(
				"""
				INSERT INTO release_notes (promotion_id, application_id, version, draft_text, created_at)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT (promotion_id) DO UPDATE
				SET draft_text = EXCLUDED.draft_text, created_at = EXCLUDED.created_at
				""",
				context.promotionId().value(), context.applicationId().value(), context.version().value(), draftText,
				Timestamp.from(Instant.now()));
	}
}
