import json
from pathlib import Path
from typing import List

from models import EvalSeed, KnowledgeDoc
from sources.base import SourceAdapter


class HotpotQASourceAdapter(SourceAdapter):
    def __init__(self, dataset_path: str):
        self.dataset_path = Path(dataset_path)

    def source_id(self) -> str:
        return "hotpotqa"

    def _load_data(self):
        with self.dataset_path.open("r", encoding="utf-8") as f:
            return json.load(f)

    def load_eval_seeds(self) -> List[EvalSeed]:
        seeds: List[EvalSeed] = []
        data = self._load_data()
        for item in data:
            question = (item.get("question") or "").strip()
            answer = (item.get("answer") or "").strip()
            if not question or not answer:
                continue
            
            seeds.append(
                EvalSeed(
                    source_id=self.source_id(),
                    question=question,
                    ground_truths=[answer],
                    metadata={
                        "id": item.get("_id"),
                        "type": item.get("type"),
                        "level": item.get("level")
                    }
                )
            )
        return seeds

    def load_knowledge_docs(self) -> List[KnowledgeDoc]:
        docs: List[KnowledgeDoc] = []
        data = self._load_data()
        
        for idx, item in enumerate(data[:30]):   # 保留数量限制
            contexts = item.get("context", [])
            item_id = item.get("_id", f"item_{idx}")
        
            for ctx in contexts:
                title = ctx[0]                  # 段落标题
                sentences = ctx[1]              # 句子列表
                content = title + "\n" + "".join(sentences)
            
                if content.strip():
                    docs.append(
                        KnowledgeDoc(
                            source_id=self.source_id(),
                            title=title,                # 直接用段落标题
                            content=content,
                            metadata={"headlines": [title]}  # 列表形式，只有一个标题
                        )
                    )
        return docs
