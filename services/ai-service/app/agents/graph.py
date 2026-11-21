import logging
from typing import Optional, Any
from langgraph.graph import StateGraph, START, END
from langgraph.checkpoint.memory import MemorySaver
from app.agents.redis_checkpointer import RedisSaver

from app.core.config import settings
from app.agents.state import AgentWorkflowState
from app.agents.nodes.planner import planner_node
from app.agents.nodes.researcher import researcher_node
from app.agents.nodes.synthesizer import synthesizer_node
from app.agents.nodes.critic import critic_node

logger = logging.getLogger("nexus.agents.graph")


def _should_continue_critic(state: AgentWorkflowState) -> str:
    """
    Routes a rejected draft back to the planner (which re-plans using the critic's feedback) until
    ``settings.max_agent_iterations`` critic passes have run; otherwise ends the workflow.
    """
    critique = state.get("critique", {}) or {}
    is_valid = critique.get("is_valid", False)
    iteration = int(state.get("iteration_count", 0) or 0)
    max_iterations = max(1, int(settings.max_agent_iterations))

    if not is_valid and iteration < max_iterations:
        logger.info(
            f"[Workflow Graph] Draft rejected (iteration {iteration}/{max_iterations}). Re-planning."
        )
        return "planner"

    if not is_valid:
        logger.warning(
            f"[Workflow Graph] Draft still rejected after {iteration} iteration(s); ending with the best available report."
        )
    return END


def create_agent_graph(checkpointer: Optional[Any] = None):
    """
    Constructs and compiles the enterprise multi-agent LangGraph state machine.
    Nodes: Planner -> Researcher -> Synthesizer -> Critic (-> Planner when the critic rejects the draft).
    """
    workflow = StateGraph(AgentWorkflowState)

    # Register workflow nodes
    workflow.add_node("planner", planner_node)
    workflow.add_node("researcher", researcher_node)
    workflow.add_node("synthesizer", synthesizer_node)
    workflow.add_node("critic", critic_node)

    # Define sequential transitions
    workflow.add_edge(START, "planner")
    workflow.add_edge("planner", "researcher")
    workflow.add_edge("researcher", "synthesizer")
    workflow.add_edge("synthesizer", "critic")

    # Define conditional feedback edge from Critic
    workflow.add_conditional_edges(
        "critic",
        _should_continue_critic,
        {
            "planner": "planner",
            END: END
        }
    )

    if checkpointer is None:
        try:
            cp = RedisSaver()
            logger.info("Using RedisSaver checkpointer for distributed state persistence under 'agent:checkpoint:{taskId}'.")
        except Exception as ex:
            logger.warning(f"Could not initialize RedisSaver: {ex}. Falling back to MemorySaver.")
            cp = MemorySaver()
    else:
        cp = checkpointer

    compiled = workflow.compile(checkpointer=cp)
    logger.info("Compiled LangGraph Multi-Agent Workflow StateGraph with checkpointer.")
    return compiled


# Global compiled workflow instance
_compiled_graph = None


def get_agent_graph():
    """Returns the singleton instance of the compiled agent graph."""
    global _compiled_graph
    if _compiled_graph is None:
        _compiled_graph = create_agent_graph()
    return _compiled_graph
