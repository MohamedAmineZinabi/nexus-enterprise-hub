import logging
from typing import Dict, Any, List
from app.agents.state import AgentWorkflowState

logger = logging.getLogger("nexus.agents.planner")


async def planner_node(state: AgentWorkflowState) -> Dict[str, Any]:
    """
    Planner Node: Decomposes the user query into 2-3 focused search and analysis sub-tasks.
    Dynamically adapts if looping back from the Critic node with refinement feedback.
    """
    user_prompt = state.get("user_prompt", "").strip()
    task_id = state.get("task_id", "")
    tenant_id = state.get("tenant_id", "")
    critique = state.get("critique", {})
    iteration = state.get("iteration_count", 0)

    logger.info(
        f"[Planner] Planning sub-tasks for task_id='{task_id}', tenant='{tenant_id}', "
        f"iteration={iteration}, prompt='{user_prompt}'"
    )

    plan: List[str] = []

    if critique and not critique.get("is_valid", False):
        feedback = critique.get("feedback", "Improve grounding and citation density.")
        logger.info(f"[Planner] Incorporating critic feedback: {feedback}")
        plan = [
            f"Address critic critique: {feedback}",
            f"Execute targeted semantic search to verify specific claims in '{user_prompt}'",
            f"Cross-reference shared volume file previews and document citations for '{user_prompt}'"
        ]
    else:
        # Standard initial decomposition
        plan = [
            f"Query vector database for core domain concepts and architecture specifications regarding '{user_prompt}'",
            f"Inspect enterprise shared files and metadata for empirical evidence supporting '{user_prompt}'",
            f"Analyze multi-tenant isolation, security policies, and performance constraints for '{user_prompt}'"
        ]

    logger.info(f"[Planner] Generated {len(plan)} sub-tasks: {plan}")

    return {
        "plan": plan,
        "current_node": "planner",
        "status": "PLANNING_COMPLETED"
    }

