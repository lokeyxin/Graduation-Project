import csv
import json
from pathlib import Path
from typing import List

from models import EvalSeed, KnowledgeDoc
from sources.base import SourceAdapter


class LocalFileSourceAdapter(SourceAdapter):
    def __init__(self, source_id: str, file_path: str):
        self._source_id = source_id
        self.file_path = Path(file_path)

    def source_id(self) -> str:
        return self._source_id

    def load_knowledge_docs(self) -> List[KnowledgeDoc]:
        docs: List[KnowledgeDoc] = []
        if self.file_path.suffix.lower() == ".jsonl":
            with self.file_path.open("r", encoding="utf-8") as f:
                for idx, line in enumerate(f, start=1):
                    if not line.strip():
                        continue
                    row = json.loads(line)
                    docs.append(
                        KnowledgeDoc(
                            source_id=self.source_id(),
                            title=str(row.get("title") or f"local-{idx}"),
                            content=str(row.get("content") or "").strip(),
                            metadata={k: v for k, v in row.items() if k not in {"title", "content"}},
                        )
                    )
        elif self.file_path.suffix.lower() == ".csv":
            with self.file_path.open("r", encoding="utf-8", newline="") as f:
                reader = csv.DictReader(f)
                for idx, row in enumerate(reader, start=1):
                    docs.append(
                        KnowledgeDoc(
                            source_id=self.source_id(),
                            title=str(row.get("title") or f"local-{idx}"),
                            content=str(row.get("content") or "").strip(),
                            metadata={k: v for k, v in row.items() if k not in {"title", "content"}},
                        )
                    )
        else:
            raise ValueError(f"Unsupported file suffix: {self.file_path.suffix}")

        return [d for d in docs if d.content]

    def load_eval_seeds(self) -> List[EvalSeed]:
        return []
