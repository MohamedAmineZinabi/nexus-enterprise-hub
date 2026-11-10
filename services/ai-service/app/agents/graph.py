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
    Evaluates critic output to determine whether to loop back for deeper research
    or conclude the workflow.
    """
    critique = state.get("critique", {})
    is_valid = critique.get("is_valid", False)
    iteration = state.get("iteration_count", 0)
    max_retries = settings.max_agent_iterations

    if not is_valid and iteration < max_retries:
        logger.info(
            f"[Workflow Graph] Critique validation failed (iteration {iteration}/{max_retries}). "
            f"Looping back to 'researcher' node."
        )
        return "researcher"

    logger.info(f"[Workflow Graph] Critique validation satisfied or max retries reached. Transitioning to END.")
    return END


def create_agent_graph(checkpointer: Optional[Any] = None):
    """
    Constructs and compiles the enterprise multi-agent LangGraph state machine.
    Nodes: Planner -> Researcher -> Synthesizer -> Critic (with conditional feedback loop).
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
            "researcher": "researcher",
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
