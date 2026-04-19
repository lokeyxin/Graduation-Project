import json
from pathlib import Path
from typing import List

from models import EvalSeed, KnowledgeDoc
from sources.base import SourceAdapter


class NQSourceAdapter(SourceAdapter):
    def __init__(self, dataset_path: str):
        self.dataset_path = Path(dataset_path)

    def source_id(self) -> str:
        return "nq"

    def load_knowledge_docs(self) -> List[KnowledgeDoc]:
        docs: List[KnowledgeDoc] = []
        for i, seed in enumerate(self.load_eval_seeds(), start=1):
            answer = seed.ground_truths[0] if seed.ground_truths else ""
            docs.append(
                KnowledgeDoc(
                    source_id=self.source_id(),
                    title=f"nq-doc-{i}",
                    content=f"Q: {seed.question}\nA: {answer}",
                    metadata=seed.metadata,
                )
            )
        return docs

    def load_eval_seeds(self) -> List[EvalSeed]:
        seeds: List[EvalSeed] = []
        with self.dataset_path.open("r", encoding="utf-8") as f:
            for line_no, line in enumerate(f, start=1):
                line = line.strip()
                if not line:
                    continue
                record = json.loads(line)
                question = (record.get("question") or "").strip()
                answers = record.get("answers") or record.get("answer") or []
                if isinstance(answers, str):
                    answers = [answers]
                answers = [str(a).strip() for a in answers if str(a).strip()]
                if not question or not answers:
                    continue
                seeds.append(
                    EvalSeed(
                        source_id=self.source_id(),
                        question=question,
                        ground_truths=answers,
                        metadata={"line_no": line_no},
                    )
                )
        return seeds
