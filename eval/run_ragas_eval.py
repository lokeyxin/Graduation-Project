import argparse
import csv
import glob
import json
import math
import os
from pathlib import Path
from typing import Any, Dict, List

from datasets import Dataset
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from ragas import evaluate

from clients.ragserver_client import RAGserverClient
from models import ChatEvalRequest

try:
    # Note: deprecated import path in ragas 0.4.x, but returns initialized metric objects.
    from ragas.metrics import answer_relevancy as metric_answer_relevancy
except Exception:
    metric_answer_relevancy = None

try:
    from ragas.metrics import faithfulness as metric_faithfulness
except Exception:
    metric_faithfulness = None

try:
    from ragas.metrics import context_precision as metric_context_precision
except Exception:
    metric_context_precision = None

try:
    from ragas.metrics import context_recall as metric_context_recall
except Exception:
    metric_context_recall = None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run RAG evaluation against /api/v1/chat/eval")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--username", default="demo01")
    parser.add_argument("--password", default="123456")
    parser.add_argument("--testset-glob", default="./eval/output/testset_*.jsonl")
    parser.add_argument("--eval-llm-model", required=True)
    parser.add_argument("--embedding-model", required=True)
    parser.add_argument("--eval-llm-base-url", default=os.getenv("OPENAI_BASE_URL", ""))
    parser.add_argument("--embedding-base-url", default=os.getenv("OPENAI_BASE_URL", ""))
    parser.add_argument("--eval-llm-api-key-env", default="OPENAI_API_KEY")
    parser.add_argument("--embedding-api-key-env", default="OPENAI_API_KEY")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--timeout-sec", type=int, default=60)
    parser.add_argument("--output-dir", default="./eval/output")
    parser.add_argument("--run-name", default="ragas-multisource-v1")
    parser.add_argument("--include-debug", action="store_true")
    parser.add_argument("--dump-ragas-debug", action="store_true")
    parser.add_argument("--raise-exceptions", action="store_true")
    parser.add_argument("--max-eval-samples", type=int, default=0)
    parser.add_argument("--fallback-eval-llm-model", default="")
    parser.add_argument("--fallback-eval-llm-base-url", default="")
    parser.add_argument("--answer-relevancy-fallback-model", default="")
    parser.add_argument("--answer-relevancy-fallback-base-url", default="")
    parser.add_argument("--answer-relevancy-fallback-api-key-env", default="")
    return parser.parse_args()


def load_rows(pattern: str) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    for file_path in glob.glob(pattern):
        with open(file_path, "r", encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    rows.append(json.loads(line))
    return rows


def build_metrics():
    metrics = []
    if metric_faithfulness is not None:
        metrics.append(metric_faithfulness)
    if metric_answer_relevancy is not None:
        metrics.append(metric_answer_relevancy)
    if metric_context_precision is not None:
        metrics.append(metric_context_precision)
    if metric_context_recall is not None:
        metrics.append(metric_context_recall)
    if not metrics:
        raise RuntimeError("No RAGAS metrics could be imported")
    return metrics


def build_eval_models(args: argparse.Namespace):
    llm_api_key = os.getenv(args.eval_llm_api_key_env, "").strip()
    embedding_api_key = os.getenv(args.embedding_api_key_env, "").strip()
    if not llm_api_key:
        raise RuntimeError(f"Environment variable {args.eval_llm_api_key_env} is empty")
    if not embedding_api_key:
        raise RuntimeError(f"Environment variable {args.embedding_api_key_env} is empty")

    eval_llm = ChatOpenAI(
        model=args.eval_llm_model,
        api_key=llm_api_key,
        base_url=args.eval_llm_base_url or None,
        temperature=0.0,
    )
    eval_embeddings = OpenAIEmbeddings(
        model=args.embedding_model,
        api_key=embedding_api_key,
        base_url=args.embedding_base_url or None,
        tiktoken_enabled=False,
        check_embedding_ctx_length=False,
    )
    return eval_llm, eval_embeddings


def build_eval_llm(model: str, base_url: str, api_key: str) -> ChatOpenAI:
    return ChatOpenAI(
        model=model,
        api_key=api_key,
        base_url=base_url or None,
        temperature=0.0,
    )


def build_eval_embeddings(model: str, base_url: str, api_key: str) -> OpenAIEmbeddings:
    return OpenAIEmbeddings(
        model=model,
        api_key=api_key,
        base_url=base_url or None,
        tiktoken_enabled=False,
        check_embedding_ctx_length=False,
    )


def has_any_valid_scores(scores: Dict[str, Any]) -> bool:
    for key, value in scores.items():
        if key.endswith("_valid_count") and isinstance(value, int) and value > 0:
            return True
    return False


def build_input_preview_rows(ragas_rows: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    preview: List[Dict[str, Any]] = []
    for idx, row in enumerate(ragas_rows):
        contexts = row.get("retrieved_contexts") or []
        if not isinstance(contexts, list):
            contexts = [str(contexts)] if contexts else []

        lengths = [len(str(x)) for x in contexts]
        avg_len = (sum(lengths) / len(lengths)) if lengths else 0
        preview.append(
            {
                "index": idx,
                "user_input": str(row.get("user_input") or "")[:160],
                "reference": str(row.get("reference") or "")[:160],
                "response_len": len(str(row.get("response") or "")),
                "context_count": len(contexts),
                "context_avg_len": avg_len,
            }
        )
    return preview


def metric_name(metric: Any) -> str:
    if hasattr(metric, "name") and getattr(metric, "name"):
        return str(getattr(metric, "name"))
    if hasattr(metric, "__name__") and getattr(metric, "__name__"):
        return str(getattr(metric, "__name__"))
    return str(metric)


def is_valid_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not math.isnan(value)


def parse_float(value: Any) -> float | None:
    if is_valid_number(value):
        return float(value)
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return None
        try:
            number = float(text)
            if math.isnan(number):
                return None
            return number
        except Exception:
            return None
    return None


def clean_contexts(contexts: Any, max_items: int = 5, max_chars: int = 2000) -> List[str]:
    if not isinstance(contexts, list):
        contexts = [str(contexts)] if contexts else []

    cleaned: List[str] = []
    seen = set()
    for raw in contexts:
        text = str(raw or "").strip()
        if not text:
            continue
        if len(text) > max_chars:
            text = text[:max_chars]
        if text in seen:
            continue
        seen.add(text)
        cleaned.append(text)
        if len(cleaned) >= max_items:
            break

    return cleaned


def find_metric_value(row: Dict[str, Any], key: str) -> float | None:
    candidates = [
        key,
        f"{key}_score",
        f"metric_{key}",
        key.lower(),
        f"{key.lower()}_score",
    ]
    for candidate in candidates:
        if candidate in row:
            value = parse_float(row.get(candidate))
            if value is not None:
                return value
    return None


def summarize_ragas_result(ragas_result: Any, metrics: List[Any]) -> Dict[str, Any]:
    if not hasattr(ragas_result, "to_pandas"):
        try:
            raw = dict(ragas_result)
            return {
                k: (v if is_valid_number(v) else None)
                for k, v in raw.items()
                if isinstance(v, (int, float))
            }
        except Exception:
            return {}

    rows = ragas_result.to_pandas().to_dict("records")
    if not rows:
        return {}

    metric_keys = [metric_name(x) for x in metrics]
    summary: Dict[str, Any] = {}
    valid_counts: Dict[str, int] = {}

    for key in metric_keys:
        values = []
        for row in rows:
            value = find_metric_value(row, key)
            if value is not None:
                values.append(value)
        if values:
            summary[key] = sum(values) / len(values)
            valid_counts[f"{key}_valid_count"] = len(values)
        else:
            summary[key] = None
            valid_counts[f"{key}_valid_count"] = 0

    summary.update(valid_counts)
    return summary


def main() -> None:
    args = parse_args()
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    rows = load_rows(args.testset_glob)
    if not rows:
        raise RuntimeError(f"No testset rows found by pattern: {args.testset_glob}")
    if args.max_eval_samples > 0:
        rows = rows[: args.max_eval_samples]

    client = RAGserverClient(base_url=args.base_url, timeout_sec=args.timeout_sec)
    client.login(args.username, args.password)
    session_id = client.create_session(f"RAGAS Eval {args.run_name}")

    per_sample: List[Dict[str, Any]] = []
    ragas_rows: List[Dict[str, Any]] = []
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

        ground_truths = row.get("ground_truths", [])
        if isinstance(ground_truths, list) and ground_truths:
            ground_truth = str(ground_truths[0]).strip()
        else:
            ground_truth = ""

        cleaned_contexts = clean_contexts(result.retrieved_contexts)
        answer_text = str(result.answer or "").strip()
        if not answer_text:
            answer_text = "I don't know."

        ragas_rows.append(
            {
                # ragas>=0.4.x canonical field names
                "user_input": result.question,
                "response": answer_text,
                "retrieved_contexts": cleaned_contexts,
                "reference": ground_truth,
                # backward-compatible aliases for older ragas variants
                "question": result.question,
                "answer": answer_text,
                "contexts": cleaned_contexts,
                "ground_truth": ground_truth,
            }
        )

    eval_llm, eval_embeddings = build_eval_models(args)
    metrics = build_metrics()
    ragas_dataset = Dataset.from_list(ragas_rows)

    if args.dump_ragas_debug:
        with (output_dir / "ragas_eval_input_preview.json").open("w", encoding="utf-8") as f:
            json.dump(build_input_preview_rows(ragas_rows), f, ensure_ascii=False, indent=2)

    ragas_result = evaluate(
        dataset=ragas_dataset,
        metrics=metrics,
        llm=eval_llm,
        embeddings=eval_embeddings,
        raise_exceptions=args.raise_exceptions,
    )

    ragas_scores = summarize_ragas_result(ragas_result, metrics)
    raw_rows_main = ragas_result.to_pandas().to_dict("records") if hasattr(ragas_result, "to_pandas") else []
    fallback_used = False
    answer_relevancy_fallback_used = False
    answer_relevancy_fallback_rows: List[Dict[str, Any]] = []

    fallback_model = args.fallback_eval_llm_model.strip()
    if fallback_model and not has_any_valid_scores(ragas_scores):
        llm_api_key = os.getenv(args.eval_llm_api_key_env, "").strip()
        embedding_api_key = os.getenv(args.embedding_api_key_env, "").strip()
        fallback_base_url = (args.fallback_eval_llm_base_url or args.eval_llm_base_url).strip()
        fallback_llm = build_eval_llm(fallback_model, fallback_base_url, llm_api_key)
        fallback_embeddings = build_eval_embeddings(
            args.embedding_model,
            args.embedding_base_url,
            embedding_api_key,
        )
        fallback_result = evaluate(
            dataset=ragas_dataset,
            metrics=metrics,
            llm=fallback_llm,
            embeddings=fallback_embeddings,
            raise_exceptions=args.raise_exceptions,
        )
        fallback_scores = summarize_ragas_result(fallback_result, metrics)
        if has_any_valid_scores(fallback_scores):
            ragas_result = fallback_result
            ragas_scores = fallback_scores
            fallback_used = True
            raw_rows_main = ragas_result.to_pandas().to_dict("records") if hasattr(ragas_result, "to_pandas") else []

    answer_relevancy_key = "answer_relevancy"
    answer_relevancy_count_key = "answer_relevancy_valid_count"
    answer_fallback_model = args.answer_relevancy_fallback_model.strip()
    if (
        answer_fallback_model
        and metric_answer_relevancy is not None
        and int(ragas_scores.get(answer_relevancy_count_key, 0) or 0) == 0
    ):
        answer_fallback_api_key_env = (
            args.answer_relevancy_fallback_api_key_env.strip() or args.eval_llm_api_key_env
        )
        answer_fallback_api_key = os.getenv(answer_fallback_api_key_env, "").strip()
        if answer_fallback_api_key:
            answer_fallback_base_url = (
                args.answer_relevancy_fallback_base_url.strip() or args.eval_llm_base_url
            )
            answer_fallback_llm = build_eval_llm(
                answer_fallback_model,
                answer_fallback_base_url,
                answer_fallback_api_key,
            )
            answer_result = evaluate(
                dataset=ragas_dataset,
                metrics=[metric_answer_relevancy],
                llm=answer_fallback_llm,
                embeddings=eval_embeddings,
                raise_exceptions=args.raise_exceptions,
            )
            answer_scores = summarize_ragas_result(answer_result, [metric_answer_relevancy])
            answer_count = int(answer_scores.get(answer_relevancy_count_key, 0) or 0)
            if answer_count > 0:
                ragas_scores[answer_relevancy_key] = answer_scores.get(answer_relevancy_key)
                ragas_scores[answer_relevancy_count_key] = answer_count
                answer_relevancy_fallback_used = True
                if hasattr(answer_result, "to_pandas"):
                    answer_relevancy_fallback_rows = answer_result.to_pandas().to_dict("records")

    if args.dump_ragas_debug and hasattr(ragas_result, "to_pandas"):
        raw_rows = raw_rows_main
        enriched_rows: List[Dict[str, Any]] = []
        for idx, row in enumerate(raw_rows):
            row_copy = dict(row)
            row_copy["answer_relevancy_main"] = row.get("answer_relevancy")
            fallback_row = answer_relevancy_fallback_rows[idx] if idx < len(answer_relevancy_fallback_rows) else {}
            row_copy["answer_relevancy_fallback"] = fallback_row.get("answer_relevancy")
            if answer_relevancy_fallback_used and fallback_row:
                row_copy["answer_relevancy"] = fallback_row.get("answer_relevancy")
            enriched_rows.append(row_copy)

        raw_cols = list(raw_rows[0].keys()) if raw_rows else []
        with (output_dir / "ragas_raw_scores.json").open("w", encoding="utf-8") as f:
            json.dump(
                {
                    "columns": raw_cols,
                    "rows": enriched_rows,
                    "metric_names": [metric_name(x) for x in metrics],
                },
                f,
                ensure_ascii=False,
                indent=2,
            )

        failed = []
        metric_names = [metric_name(x) for x in metrics]
        for idx, row in enumerate(enriched_rows):
            all_missing = True
            for name in metric_names:
                if find_metric_value(row, name) is not None:
                    all_missing = False
                    break
            if all_missing:
                sample = per_sample[idx] if idx < len(per_sample) else {}
                failed.append(
                    {
                        "index": idx,
                        "question_id": sample.get("question_id"),
                        "question": sample.get("question"),
                        "reason": "all_metrics_missing_or_nan",
                        "row": row,
                    }
                )

        with (output_dir / "ragas_failed_samples.json").open("w", encoding="utf-8") as f:
            json.dump(failed, f, ensure_ascii=False, indent=2)

    summary = {
        "run_name": args.run_name,
        "total": len(per_sample),
        "note": "RAGAS evaluate enabled",
        "avg_latency_ms": sum(x["latency_ms"] for x in per_sample) / max(len(per_sample), 1),
        "rag_hit_rate": sum(1 for x in per_sample if x["used_rag"]) / max(len(per_sample), 1),
        "fallback_used": fallback_used,
        "answer_relevancy_fallback_used": answer_relevancy_fallback_used,
        "max_eval_samples": args.max_eval_samples,
        "metrics": ragas_scores,
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
