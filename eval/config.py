import os
from dataclasses import dataclass
from dotenv import load_dotenv


load_dotenv()


@dataclass
class LLMConfig:
    model: str
    base_url: str
    api_key: str


@dataclass
class EvalRuntimeConfig:
    base_url: str
    username: str
    password: str
    top_k: int
    timeout_sec: int


def read_env(name: str, default: str = "") -> str:
    value = os.getenv(name, default)
    return value.strip() if isinstance(value, str) else value


def load_runtime_config() -> EvalRuntimeConfig:
    return EvalRuntimeConfig(
        base_url=read_env("RAGSERVER_BASE_URL", "http://localhost:8080"),
        username=read_env("RAGSERVER_USERNAME", "demo01"),
        password=read_env("RAGSERVER_PASSWORD", "123456"),
        top_k=int(read_env("RAG_EVAL_TOP_K", "5")),
        timeout_sec=int(read_env("RAG_EVAL_TIMEOUT_SEC", "60")),
    )
