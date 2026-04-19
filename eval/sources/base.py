from abc import ABC, abstractmethod
from typing import List

from models import EvalSeed, KnowledgeDoc


class SourceAdapter(ABC):
    @abstractmethod
    def source_id(self) -> str:
        raise NotImplementedError

    @abstractmethod
    def load_knowledge_docs(self) -> List[KnowledgeDoc]:
        raise NotImplementedError

    @abstractmethod
    def load_eval_seeds(self) -> List[EvalSeed]:
        raise NotImplementedError
