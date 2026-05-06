package com.mohsenzamni.mrreviewer.tool;

/**
 * Marker interface implemented by every review tool callable by the orchestrator.
 *
 * <p>Tools are deterministic, independently testable services with a clear input/output
 * contract. They are called <em>programmatically</em> by the {@code ReviewService}
 * orchestrator — the orchestrator decides when to call them and feeds their results into
 * the LLM prompt as structured context.
 *
 * <p>This design keeps tools composable and reusable across different orchestration
 * strategies (single-shot review, multi-AC review, future agentic loops).
 */
public interface ReviewTool {

    /** Short machine-readable name, e.g. {@code "repo"} or {@code "search"}. */
    String name();

    /** Human-readable description of the tool's purpose. */
    String description();
}
