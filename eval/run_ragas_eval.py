import argparse
import csv
import glob
import json
from dataclasses import asdict
from pathlib import Path
from typing import Any, Dict, List

from clients.ragserver_client import RAGserverClient
from models import ChatEvalRequest


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run RAG evaluation against /api/v1/chat/eval")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--username", default="demo01")
    parser.add_argument("--password", default="123456")
    parser.add_argument("--testset-glob", default="./eval/output/testset_*.jsonl")
    parser.add_argument("--eval-llm-model", required=True)
    parser.add_argument("--embedding-model", required=True)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--timeout-sec", type=int, default=60)
    parser.add_argument("--output-dir", default="./eval/output")
    parser.add_argument("--run-name", default="ragas-multisource-v1")
    parser.add_argument("--include-debug", action="store_true")
    return parser.parse_args()


def load_rows(pattern: str) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    for file_path in glob.glob(pattern):
        with open(file_path, "r", encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    rows.append(json.loads(line))
    return rows


def main() -> None:
    args = parse_args()
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    rows = load_rows(args.testset_glob)
    if not rows:
        raise RuntimeError(f"No testset rows found by pattern: {args.testset_glob}")

    client = RAGserverClient(base_url=args.base_url, timeout_sec=args.timeout_sec)
    client.login(args.username, args.password)
    session_id = client.create_session(f"RAGAS Eval {args.run_name}")

    per_sample: List[Dict[str, Any]] = []
    for row in rows:
        req = ChatEvalRequest(
            session_id=session_id,
            question=row.get("question", ""),
            question_id=row.get("question_id"),
            source_id=row.get("source_id"),
            top_k=args.top_k,
            include_debug=args.include_debug,
        )
        result = client.chat_eval(req)
        per_sample.append(
            {
                "question_id": result.question_id,
                "source_id": result.source_id,
                "question": result.question,
                "answer": result.answer,
                "ground_truths": row.get("ground_truths", []),
                "retrieved_contexts": result.retrieved_contexts,
                "retrieved_count": result.retrieved_count,
                "latency_ms": result.latency_ms,
                "used_rag": result.used_rag,
                "request_id": result.request_id,
                "difficulty": row.get("difficulty", "unknown"),
            }
        )

    summary = {
        "run_name": args.run_name,
        "total": len(per_sample),
        "note": "当前版本已完成后端打通与采样执行。RAGAS指标计算将在下一步接入 ragas.evaluate。",
        "avg_latency_ms": sum(x["latency_ms"] for x in per_sample) / max(len(per_sample), 1),
        "rag_hit_rate": sum(1 for x in per_sample if x["used_rag"]) / max(len(per_sample), 1),
    }

    with (output_dir / "summary_overall.json").open("w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)

    with (output_dir / "per_sample.json").open("w", encoding="utf-8") as f:
        json.dump(per_sample, f, ensure_ascii=False, indent=2)

    with (output_dir / "per_sample.csv").open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(
            f,
            fieldnames=[
                "question_id",
                "source_id",
                "difficulty",
                "question",
                "answer",
                "retrieved_count",
                "latency_ms",
                "used_rag",
                "request_id",
            ],
        )
        writer.writeheader()
        for row in per_sample:
            writer.writerow(
                {
                    "question_id": row["question_id"],
                    "source_id": row["source_id"],
                    "difficulty": row["difficulty"],
                    "question": row["question"],
                    "answer": row["answer"],
                    "retrieved_count": row["retrieved_count"],
                    "latency_ms": row["latency_ms"],
                    "used_rag": row["used_rag"],
                    "request_id": row["request_id"],
                }
            )

    print(f"Evaluation finished. Summary: {output_dir / 'summary_overall.json'}")


if __name__ == "__main__":
    main()
