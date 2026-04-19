import argparse
import json
from pathlib import Path
from typing import List

from models import EvalSeed
from sources.nq_source import NQSourceAdapter


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate synthetic testsets from configured sources")
    parser.add_argument("--sources", default="nq", help="Comma separated source ids")
    parser.add_argument("--size-per-source", type=int, default=300)
    parser.add_argument("--distribution", default="simple:0.4,reasoning:0.4,multi_context:0.2")
    parser.add_argument("--nq-path", default="./NQ-open.efficientqa.dev.1.1.sample.jsonl")
    parser.add_argument("--output-dir", default="./eval/output")
    parser.add_argument("--run-name", default="ragas-multisource-v1")
    parser.add_argument("--generator-llm-model", required=True)
    parser.add_argument("--critic-llm-model", required=True)
    parser.add_argument("--embedding-model", required=True)
    return parser.parse_args()


def build_seed_set_for_nq(path: str, size: int) -> List[EvalSeed]:
    adapter = NQSourceAdapter(path)
    seeds = adapter.load_eval_seeds()
    return seeds[:size]


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
        "note": "当前版本已完成多源生成框架入口；LLM生成与critic过滤将在下一步接入RAGAS TestsetGenerator。",
    }

    for source in sources:
        if source == "nq":
            seeds = build_seed_set_for_nq(args.nq_path, args.size_per_source)
            out = output_dir / f"testset_{source}.jsonl"
            with out.open("w", encoding="utf-8") as f:
                for idx, seed in enumerate(seeds, start=1):
                    row = {
                        "question_id": f"{source}-{idx}",
                        "source_id": source,
                        "difficulty": "simple",
                        "question": seed.question,
                        "ground_truths": seed.ground_truths,
                        "metadata": seed.metadata,
                    }
                    f.write(json.dumps(row, ensure_ascii=False) + "\n")
            generation_audit["sources"][source] = {
                "generated": len(seeds),
                "target": args.size_per_source,
                "status": "ok",
            }
        else:
            generation_audit["sources"][source] = {
                "generated": 0,
                "target": args.size_per_source,
                "status": "stub",
                "message": "Source adapter pipeline scaffolded; generation will be implemented next.",
            }

    audit_path = output_dir / "generation_audit.json"
    with audit_path.open("w", encoding="utf-8") as f:
        json.dump(generation_audit, f, ensure_ascii=False, indent=2)

    print(f"Generation finished. Audit: {audit_path}")


if __name__ == "__main__":
    main()
