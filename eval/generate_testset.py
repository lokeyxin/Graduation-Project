import argparse
import json
import os
from pathlib import Path
from typing import List

from langchain_core.documents import Document
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

try:
    # ragas>=0.4.x
    from ragas.llms import LangchainLLMWrapper
    from ragas.testset import TestsetGenerator
    from ragas.testset.synthesizers import (
        MultiHopAbstractQuerySynthesizer,
        MultiHopSpecificQuerySynthesizer,
        SingleHopSpecificQuerySynthesizer,
    )

    RAGAS_NEW_API = True
except ImportError:
    # Backward compatibility for older ragas versions
    from ragas.testset.generator import TestsetGenerator
    from ragas.testset.evolutions import multi_context, reasoning, simple

    RAGAS_NEW_API = False

from models import EvalSeed, KnowledgeDoc
from sources.local_file_source import LocalFileSourceAdapter
from sources.nq_source import NQSourceAdapter
from sources.ragserver_document_source import RAGserverDocumentSourceAdapter


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate synthetic testsets from configured sources")
    parser.add_argument("--sources", default="nq", help="Comma separated source ids")
    parser.add_argument("--size-per-source", type=int, default=300)
    parser.add_argument("--distribution", default="simple:0.4,reasoning:0.4,multi_context:0.2")
    parser.add_argument("--local-paths", default="", help="Comma separated local JSONL/CSV files")
    parser.add_argument("--document-ids", default="", help="Comma separated document IDs")
    parser.add_argument("--nq-path", default="./NQ-open.efficientqa.dev.1.1.sample.jsonl")
    parser.add_argument("--output-dir", default="./eval/output")
    parser.add_argument("--run-name", default="ragas-multisource-v1")
    parser.add_argument("--generator-llm-model", required=True)
    parser.add_argument("--critic-llm-model", required=True)
    parser.add_argument("--embedding-model", required=True)
    parser.add_argument("--llm-base-url", default=os.getenv("OPENAI_BASE_URL", ""))
    parser.add_argument("--embedding-base-url", default=os.getenv("OPENAI_BASE_URL", ""))
    parser.add_argument("--llm-api-key-env", default="OPENAI_API_KEY")
    parser.add_argument("--embedding-api-key-env", default="OPENAI_API_KEY")
    return parser.parse_args()


def build_seed_set_for_nq(path: str, size: int) -> List[EvalSeed]:
    adapter = NQSourceAdapter(path)
    seeds = adapter.load_eval_seeds()
    return seeds[:size]


def parse_distribution(value: str, synthesizer_llm=None):
    if RAGAS_NEW_API:
        if synthesizer_llm is None:
            raise ValueError("synthesizer_llm is required for ragas>=0.4.x")
        mapping = {
            "simple": SingleHopSpecificQuerySynthesizer(llm=synthesizer_llm),
            "reasoning": MultiHopAbstractQuerySynthesizer(llm=synthesizer_llm),
            "multi_context": MultiHopSpecificQuerySynthesizer(llm=synthesizer_llm),
        }
    else:
        mapping = {
            "simple": simple,
            "reasoning": reasoning,
            "multi_context": multi_context,
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
    llm_api_key = os.getenv(args.llm_api_key_env, "").strip()
    embedding_api_key = os.getenv(args.embedding_api_key_env, "").strip()
    if not llm_api_key:
        raise RuntimeError(f"Environment variable {args.llm_api_key_env} is empty")
    if not embedding_api_key:
        raise RuntimeError(f"Environment variable {args.embedding_api_key_env} is empty")

    generator_llm = ChatOpenAI(
        model=args.generator_llm_model,
        api_key=llm_api_key,
        base_url=args.llm_base_url or None,
        temperature=0.2,
    )
    critic_llm = ChatOpenAI(
        model=args.critic_llm_model,
        api_key=llm_api_key,
        base_url=args.llm_base_url or None,
        temperature=0.0,
    )
    embeddings = OpenAIEmbeddings(
        model=args.embedding_model,
        api_key=embedding_api_key,
        base_url=args.embedding_base_url or None,
    )
    generator = TestsetGenerator.from_langchain(generator_llm, critic_llm, embeddings)
    synthesizer_llm = LangchainLLMWrapper(generator_llm) if RAGAS_NEW_API else None
    return generator, synthesizer_llm


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
    args = parse_args()
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    sources = [s.strip() for s in args.sources.split(",") if s.strip()]
    generation_audit = {
        "run_name": args.run_name,
        "size_per_source": args.size_per_source,
        "distribution": args.distribution,
        "sources": {},
        "note": "RAGAS TestsetGenerator enabled",
    }

    generator, synthesizer_llm = build_generator(args)
    distributions = parse_distribution(args.distribution, synthesizer_llm=synthesizer_llm)

    for source in sources:
        try:
            docs = get_source_docs(source, args)
            lc_docs = to_langchain_docs(docs)
            if not lc_docs:
                generation_audit["sources"][source] = {
                    "generated": 0,
                    "target": args.size_per_source,
                    "status": "skipped",
                    "message": "No valid source documents",
                }
                continue

            if RAGAS_NEW_API:
                testset = generator.generate_with_langchain_docs(
                    documents=lc_docs,
                    testset_size=args.size_per_source,
                    query_distribution=distributions,
                )
            else:
                testset = generator.generate_with_langchain_docs(
                    documents=lc_docs,
                    test_size=args.size_per_source,
                    distributions=distributions,
                )

            out = output_dir / f"testset_{source}.jsonl"
            rows = testset.to_pandas().to_dict("records")
            generated = write_testset_jsonl(out, source, rows)

            generation_audit["sources"][source] = {
                "generated": generated,
                "target": args.size_per_source,
                "status": "ok" if generated > 0 else "empty",
            }
        except Exception as ex:
            generation_audit["sources"][source] = {
                "generated": 0,
                "target": args.size_per_source,
                "status": "error",
                "message": str(ex),
            }

    audit_path = output_dir / "generation_audit.json"
    with audit_path.open("w", encoding="utf-8") as f:
        json.dump(generation_audit, f, ensure_ascii=False, indent=2)

    print(f"Generation finished. Audit: {audit_path}")


if __name__ == "__main__":
    main()
