from typing import Any, Dict

import requests

from models import ChatEvalRequest, ChatEvalResult, RetrievedContextItem


class RAGserverClient:
    def __init__(self, base_url: str, timeout_sec: int = 60):
        self.base_url = base_url.rstrip("/")
        self.timeout_sec = timeout_sec
        self.token = ""

    def _headers(self) -> Dict[str, str]:
        if not self.token:
            return {"Content-Type": "application/json"}
        return {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {self.token}",
        }

    def _unwrap(self, resp: requests.Response) -> Any:
        resp.raise_for_status()
        body = resp.json()
        if not body.get("success"):
            raise RuntimeError(f"API error code={body.get('code')} message={body.get('message')}")
        return body.get("data")

    def login(self, username: str, password: str) -> str:
        url = f"{self.base_url}/api/v1/auth/login"
        payload = {"username": username, "password": password}
        resp = requests.post(url, json=payload, headers=self._headers(), timeout=self.timeout_sec)
        data = self._unwrap(resp)
        token = data.get("token")
        if not token:
            raise RuntimeError("Login success but no token returned")
        self.token = token
        return token

    def create_session(self, title: str) -> int:
        url = f"{self.base_url}/api/v1/sessions"
        resp = requests.post(url, json={"title": title}, headers=self._headers(), timeout=self.timeout_sec)
        data = self._unwrap(resp)
        return int(data["sessionId"])

    def chat_eval(self, request: ChatEvalRequest) -> ChatEvalResult:
        url = f"{self.base_url}/api/v1/chat/eval"
        payload = {
            "sessionId": request.session_id,
            "question": request.question,
            "questionId": request.question_id,
            "sourceId": request.source_id,
            "topK": request.top_k,
            "includeDebug": request.include_debug,
        }
        resp = requests.post(url, json=payload, headers=self._headers(), timeout=self.timeout_sec)
        data = self._unwrap(resp)
        context_items = [
            RetrievedContextItem(
                knowledge_id=item.get("knowledgeId"),
                content=item.get("content") or "",
                vector_score=float(item.get("vectorScore") or 0.0),
                rerank_score=float(item.get("rerankScore") or 0.0),
                final_score=float(item.get("finalScore") or 0.0),
                route=item.get("route") or "",
                rank=int(item.get("rank") or 0),
            )
            for item in data.get("retrievedContextItems") or []
        ]
        return ChatEvalResult(
            question_id=data.get("questionId"),
            source_id=data.get("sourceId"),
            session_id=int(data.get("sessionId")),
            question=data.get("question") or "",
            answer=data.get("answer") or "",
            retrieved_contexts=data.get("retrievedContexts") or [],
            retrieved_context_items=context_items,
            retrieved_count=int(data.get("retrievedCount") or 0),
            latency_ms=int(data.get("latencyMs") or 0),
            used_rag=bool(data.get("usedRag")),
            request_id=data.get("requestId") or "",
        )
