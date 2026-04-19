from typing import List

from models import EvalSeed, KnowledgeDoc
from sources.base import SourceAdapter


class RAGserverDocumentSourceAdapter(SourceAdapter):
    def __init__(self, document_ids: List[int]):
        self.document_ids = document_ids

    def source_id(self) -> str:
        return "ragserver_doc"

    def load_knowledge_docs(self) -> List[KnowledgeDoc]:
        # TODO: 可在后续版本中新增后端文档内容导出接口，当前先保留骨架。
        docs: List[KnowledgeDoc] = []
        for doc_id in self.document_ids:
            docs.append(
                KnowledgeDoc(
                    source_id=self.source_id(),
                    title=f"document-{doc_id}",
                    content="",
                    metadata={"document_id": doc_id},
                )
            )
        return docs

    def load_eval_seeds(self) -> List[EvalSeed]:
        return []
