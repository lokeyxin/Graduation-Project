import argparse
import json
import logging
import os
import traceback
from pathlib import Path
from typing import List

from langchain_core.documents import Document
from openai import OpenAI
from httpx import Timeout

from ragas.llms import llm_factory
from ragas.embeddings import OpenAIEmbeddings as RagasOpenAIEmbeddings
from ragas.testset import TestsetGenerator
from ragas.testset.synthesizers import (
    MultiHopAbstractQuerySynthesizer,
    MultiHopSpecificQuerySynthesizer,
    SingleHopSpecificQuerySynthesizer,
)
from ragas import RunConfig

from models import EvalSeed, KnowledgeDoc
from sources.hotpotqa_source import HotpotQASourceAdapter
from sources.local_file_source import LocalFileSourceAdapter
from sources.nq_source import NQSourceAdapter
from sources.ragserver_document_source import RAGserverDocumentSourceAdapter


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate synthetic testsets from configured sources")
    parser.add_argument("--sources", default="hotpotqa", help="Comma separated source ids")
    parser.add_argument("--size-per-source", type=int, default=1)
    parser.add_argument("--distribution", default="simple:0.4,reasoning:0.4,multi_context:0.2")
    parser.add_argument("--local-paths", default="", help="Comma separated local JSONL/CSV files")
    parser.add_argument("--document-ids", default="", help="Comma separated document IDs")
    parser.add_argument("--nq-path", default="./NQ-open.efficientqa.dev.1.1.sample.jsonl")
    parser.add_argument("--hotpotqa-path", default="../hotpot_sample_50.json")
    parser.add_argument("--output-dir", default="./output")
    parser.add_argument("--run-name", default="ragas-multisource-v1")
    # 修改：默认模型改为 DeepSeek V4 Flash
    parser.add_argument("--generator-llm-model", default="deepseek-v4-flash", required=False)
    parser.add_argument("--critic-llm-model", default="deepseek-v4-flash", required=False)
    # Embedding 保持阿里云（若需更换请调整）
    parser.add_argument("--embedding-model", default="text-embedding-v4", required=False)
    # 修改：LLM Base URL 改为 DeepSeek
    parser.add_argument("--llm-base-url", default="https://api.deepseek.com")
    # Embedding Base URL 保持阿里云
    parser.add_argument("--embedding-base-url", default="https://dashscope.aliyuncs.com/compatible-mode/v1")
    # 修改：LLM 密钥环境变量名改为 DEEPSEEK_API_KEY
    parser.add_argument("--llm-api-key-env", default="DEEPSEEK_API_KEY")
    parser.add_argument("--embedding-api-key-env", default="OPENAI_API_KEY")  # 若 Embedding 也用 DeepSeek 则需改为同一变量
    parser.add_argument("--max-workers", type=int, default=1, help="并发 worker 数")
    parser.add_argument("--timeout", type=int, default=300, help="RAGAS 内部操作超时（秒）")
    parser.add_argument("--max-retries", type=int, default=3)
    parser.add_argument("--verbose", action="store_true")
    parser.add_argument("--doc-limit", type=int, default=0, help="只使用前 N 个文档（0=全部）")
    return parser.parse_args()


def parse_distribution(value: str, synthesizer_llm):
    mapping = {
        "simple": SingleHopSpecificQuerySynthesizer(llm=synthesizer_llm),
        "reasoning": MultiHopAbstractQuerySynthesizer(llm=synthesizer_llm),
        "multi_context": MultiHopSpecificQuerySynthesizer(llm=synthesizer_llm),
    }
    distributions = []
    for token in value.split(","):
        token = token.strip()
        if not token:
            continue
        key, weight = token.split(":", 1)
        key = key.strip()
        weight = float(weight.strip())
        if key not in mapping:
            raise ValueError(f"Unsupported distribution key: {key}")
        distributions.append((mapping[key], weight))
    if not distributions:
        raise ValueError("distribution cannot be empty")
    return distributions


def build_generator(args: argparse.Namespace):
    # 使用指定的环境变量获取密钥
    llm_api_key = os.getenv(args.llm_api_key_env, "").strip()
    embedding_api_key = os.getenv(args.embedding_api_key_env, "").strip()
    if not llm_api_key:
        raise RuntimeError(f"Environment variable {args.llm_api_key_env} is empty")
    if not embedding_api_key:
        raise RuntimeError(f"Environment variable {args.embedding_api_key_env} is empty")

    # 设置 HTTP 超时（防止请求无限挂起）
    http_timeout = Timeout(connect=10.0, read=120.0, write=10.0, pool=10.0)

    # 创建 OpenAI 客户端 —— LLM 用 DeepSeek
    llm_client = OpenAI(
        api_key=llm_api_key,
        base_url=args.llm_base_url,          # https://api.deepseek.com
        timeout=http_timeout,
        max_retries=0,
    )
    # Embedding 客户端保持阿里云（或按需修改）
    embed_client = OpenAI(
        api_key=embedding_api_key,
        base_url=args.embedding_base_url,    # https://dashscope.aliyuncs.com/...
        timeout=http_timeout,
        max_retries=0,
    )

    generator_llm = llm_factory(
        args.generator_llm_model,
        client=llm_client,
        max_tokens=4096,          # ★ 增大输出长度，避免截断
        temperature=0.01,         # 保持低随机性，确保 JSON 格式稳定
        top_p=0.1
    )
    embeddings = RagasOpenAIEmbeddings(client=embed_client, model=args.embedding_model)

    if args.verbose:
        logging.info("Testing LLM connectivity (DeepSeek)...")
        try:
            test_resp = llm_client.chat.completions.create(
                model=args.generator_llm_model,
                messages=[{"role": "user", "content": "ping"}],
                max_tokens=1,
                timeout=30,
            )
            logging.info(f"LLM test OK: {test_resp.choices[0].message.content}")
        except Exception as e:
            logging.warning(f"LLM connectivity test failed: {e}")

        logging.info("Testing Embedding connectivity...")
        try:
            test_emb = embed_client.embeddings.create(
                model=args.embedding_model,
                input="test",
                timeout=30,
            )
            logging.info(f"Embedding test OK, dim={len(test_emb.data[0].embedding)}")
        except Exception as e:
            logging.warning(f"Embedding connectivity test failed: {e}")

    generator = TestsetGenerator(llm=generator_llm, embedding_model=embeddings)
    return generator, generator_llm


def to_langchain_docs(knowledge_docs: List[KnowledgeDoc]) -> List[Document]:
    docs: List[Document] = []
    for item in knowledge_docs:
        if not item.content:
            continue
        metadata = dict(item.metadata)
        metadata["source_id"] = item.source_id
        metadata["title"] = item.title
        docs.append(Document(page_content=item.content, metadata=metadata))
    return docs


def get_source_docs(source: str, args: argparse.Namespace) -> List[KnowledgeDoc]:
    if source == "hotpotqa":
        return HotpotQASourceAdapter(args.hotpotqa_path).load_knowledge_docs()
    if source == "nq":
        return NQSourceAdapter(args.nq_path).load_knowledge_docs()
    if source == "local":
        docs: List[KnowledgeDoc] = []
        local_paths = [x.strip() for x in args.local_paths.split(",") if x.strip()]
        for index, file_path in enumerate(local_paths, start=1):
            adapter = LocalFileSourceAdapter(f"local-{index}", file_path)
            docs.extend(adapter.load_knowledge_docs())
        return docs
    if source == "ragserver_doc":
        ids = [x.strip() for x in args.document_ids.split(",") if x.strip()]
        doc_ids = [int(x) for x in ids]
        return RAGserverDocumentSourceAdapter(doc_ids).load_knowledge_docs()
    return []


def write_testset_jsonl(output_path: Path, source: str, rows: List[dict]) -> int:
    written = 0
    with output_path.open("w", encoding="utf-8") as f:
        for idx, row in enumerate(rows, start=1):
            question = (row.get("question") or row.get("user_input") or "").strip()
            if not question:
                continue
            gt = row.get("ground_truth") or row.get("reference") or row.get("answer")
            if isinstance(gt, list):
                ground_truths = [str(x).strip() for x in gt if str(x).strip()]
            elif gt is None:
                ground_truths = []
            else:
                value = str(gt).strip()
                ground_truths = [value] if value else []
            if not ground_truths:
                continue
            difficulty = str(row.get("evolution_type") or "generated")
            output_row = {
                "question_id": f"{source}-{idx}",
                "source_id": source,
                "difficulty": difficulty,
                "question": question,
                "ground_truths": ground_truths,
                "metadata": row.get("metadata") or {},
            }
            f.write(json.dumps(output_row, ensure_ascii=False) + "\n")
            written += 1
    return written


def main() -> None:
    # 关闭 RAGAS 遥测（避免 SSL 干扰）
    os.environ["RAGAS_DO_NOT_TRACK"] = "1"

    args = parse_args()
    log_level = logging.DEBUG if args.verbose else logging.INFO
    logging.basicConfig(
        level=log_level,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )
    if not args.verbose:
        logging.getLogger("openai").setLevel(logging.WARNING)
        logging.getLogger("httpx").setLevel(logging.WARNING)

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    sources = [s.strip() for s in args.sources.split(",") if s.strip()]
    generation_audit = {
        "run_name": args.run_name,
        "size_per_source": args.size_per_source,
        "distribution": args.distribution,
        "max_workers": args.max_workers,
        "timeout": args.timeout,
        "max_retries": args.max_retries,
        "doc_limit": args.doc_limit,
        "sources": {},
    }

    try:
        generator, synthesizer_llm = build_generator(args)
    except Exception as e:
        logging.fatal(f"Failed to build generator: {e}")
        return

    distributions = parse_distribution(args.distribution, synthesizer_llm=synthesizer_llm)

    for source in sources:
        logging.info(f"Processing source: {source}")
        try:
            docs = get_source_docs(source, args)
            lc_docs = to_langchain_docs(docs)

            # 测试用截断
            if args.doc_limit > 0 and len(lc_docs) > args.doc_limit:
                logging.info(f"Limiting documents from {len(lc_docs)} to {args.doc_limit} (for testing)")
                lc_docs = lc_docs[: args.doc_limit]

            if not lc_docs:
                generation_audit["sources"][source] = {
                    "generated": 0,
                    "target": args.size_per_source,
                    "status": "skipped",
                    "message": "No valid source documents",
                }
                logging.warning(f"Source {source}: no valid documents, skipping.")
                continue

            logging.info(f"Source {source}: using {len(lc_docs)} documents, generating {args.size_per_source} test samples.")
            run_config = RunConfig(
                timeout=args.timeout,
                max_retries=args.max_retries,
                max_workers=args.max_workers,
            )
            testset = generator.generate_with_langchain_docs(
                documents=lc_docs,
                testset_size=args.size_per_source,
                query_distribution=distributions,
                run_config=run_config,
            )

            out = output_dir / f"testset_{source}.jsonl"
            rows = testset.to_pandas().to_dict("records")
            generated = write_testset_jsonl(out, source, rows)
            logging.info(f"Source {source}: successfully generated {generated} samples.")
            generation_audit["sources"][source] = {
                "generated": generated,
                "target": args.size_per_source,
                "status": "ok" if generated > 0 else "empty",
            }
        except Exception as ex:
            logging.error(f"Source {source} failed: {ex}")
            if args.verbose:
                traceback.print_exc()
            generation_audit["sources"][source] = {
                "generated": 0,
                "target": args.size_per_source,
                "status": "error",
                "message": str(ex),
            }

    audit_path = output_dir / "generation_audit.json"
    with audit_path.open("w", encoding="utf-8") as f:
        json.dump(generation_audit, f, ensure_ascii=False, indent=2)

    logging.info(f"Generation finished. Audit: {audit_path}")


if __name__ == "__main__":
    main()