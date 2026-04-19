from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


@dataclass
class KnowledgeDoc:
    source_id: str
    title: str
    content: str
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class EvalSeed:
    source_id: str
    question: str
    ground_truths: List[str]
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class ChatEvalRequest:
    session_id: int
    question: str
    question_id: Optional[str] = None
    source_id: Optional[str] = None
    top_k: Optional[int] = None
    include_debug: bool = False


@dataclass
class RetrievedContextItem:
    knowledge_id: Optional[int]
    content: str
    vector_score: float
    rerank_score: float
    final_score: float
    route: str
    rank: int


@dataclass
class ChatEvalResult:
    question_id: Optional[str]
    source_id: Optional[str]
    session_id: int
    question: str
    answer: str
    retrieved_contexts: List[str]
    retrieved_context_items: List[RetrievedContextItem]
    retrieved_count: int
    latency_ms: int
    used_rag: bool
    request_id: str
