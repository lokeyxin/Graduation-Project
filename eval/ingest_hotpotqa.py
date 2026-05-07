"""
将 hotpot_sample_50.json 的 context 段落摄入 RAGserver 数据库。

用法:
    python ingest_hotpotqa.py                          # 全部 50 个 item
    python ingest_hotpotqa.py --limit 5                # 只摄入前 5 个
    python ingest_hotpotqa.py --check-status           # 检查摄入进度
"""

import argparse
import json
import os
import re
import sys
import tempfile
import time
from pathlib import Path
from typing import List, Tuple

import requests


def parse_args():
    parser = argparse.ArgumentParser(description="Ingest HotPotQA documents into RAGserver")
    parser.add_argument("--hotpotqa-path", default="../hotpot_sample_50.json")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--username", default="demo01")
    parser.add_argument("--password", default="123456")
    parser.add_argument("--limit", type=int, default=0, help="只摄入前 N 个 item（0=全部）")
    parser.add_argument("--check-status", action="store_true", help="仅查看文档状态")
    parser.add_argument("--timeout-sec", type=int, default=30)
    return parser.parse_args()


def sanitize_filename(title: str, max_len: int = 40) -> str:
    """清理文件名中的非法字符"""
    name = re.sub(r'[\\/:*?"<>|]', "_", title)
    name = name.strip().strip(".").strip()
    if not name:
        name = "document"
    return name[:max_len]


def login(base_url: str, username: str, password: str, timeout: int) -> str:
    url = f"{base_url}/api/v1/auth/login"
    resp = requests.post(
        url,
        json={"username": username, "password": password},
        headers={"Content-Type": "application/json"},
        timeout=timeout,
    )
    resp.raise_for_status()
    body = resp.json()
    if not body.get("success"):
        raise RuntimeError(f"登录失败: {body.get('message')}")
    token = body["data"]["token"]
    print(f"登录成功, token={token[:20]}...")
    return token


def list_documents(base_url: str, token: str, timeout: int) -> List[dict]:
    url = f"{base_url}/api/v1/documents"
    resp = requests.get(
        url,
        headers={"Authorization": f"Bearer {token}"},
        timeout=timeout,
    )
    resp.raise_for_status()
    body = resp.json()
    if not body.get("success"):
        raise RuntimeError(f"获取文档列表失败: {body.get('message')}")
    return body.get("data", [])


def check_status(base_url: str, token: str, timeout: int):
    """打印当前文档摄入状态"""
    docs = list_documents(base_url, token, timeout)
    if not docs:
        print("当前无文档。")
        return
    status_map = {0: "待处理", 1: "已完成", 2: "处理中", 3: "失败"}
    print(f"共 {len(docs)} 个文档：")
    for d in docs:
        status_name = status_map.get(d.get("status"), str(d.get("status")))
        doc_id = d.get("documentId") or d.get("id") or "?"
        doc_name = d.get("documentName") or d.get("name") or "?"
        print(f"  id={doc_id}, name={doc_name}, status={status_name}")


def build_document_texts(hotpotqa_path: str, limit: int) -> List[Tuple[str, str]]:
    """
    读取 hotpotqa 数据，将每个 item 的所有 context 合并为一个文本。
    返回: [(filename, text), ...]
    """
    with open(hotpotqa_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    if limit > 0:
        data = data[:limit]

    items: List[Tuple[str, str]] = []

    for idx, item in enumerate(data):
        item_id = item.get("_id", f"item_{idx}")
        question = item.get("question", "").strip()
        contexts = item.get("context", [])

        # 用 item 标题作为文件名
        # 取 question 的前几个词作为标识
        safe_title = sanitize_filename(question, max_len=40) if question else item_id
        filename = f"hotpotqa_{idx:04d}_{safe_title}.txt"

        # 构建文档内容
        parts = []
        if question:
            parts.append(f"# Question: {question}\n")

        for ctx_idx, ctx in enumerate(contexts):
            title = ctx[0]
            sentences = ctx[1]
            content = "".join(sentences)
            parts.append(f"## {title}\n{content}\n")

        text = "\n".join(parts)
        if text.strip():
            items.append((filename, text))

    return items


def upload_file(base_url: str, token: str, filepath: str, filename: str, overwrite: bool, timeout: int) -> bool:
    """上传单个文件到 RAGserver"""
    url = f"{base_url}/api/v1/documents/upload"
    with open(filepath, "rb") as f:
        resp = requests.post(
            url,
            files={"file": (filename, f, "text/plain")},
            data={"overwrite": str(overwrite).lower()},
            headers={"Authorization": f"Bearer {token}"},
            timeout=timeout,
        )
    resp.raise_for_status()
    body = resp.json()
    if not body.get("success"):
        raise RuntimeError(f"上传失败: {body.get('message')}")
    doc_data = body.get("data", {})
    return doc_data.get("id")


def main():
    args = parse_args()

    token = login(args.base_url, args.username, args.password, args.timeout_sec)

    if args.check_status:
        check_status(args.base_url, token, args.timeout_sec)
        return

    items = build_document_texts(args.hotpotqa_path, args.limit)
    print(f"准备上传 {len(items)} 个文档...")

    # 写入临时目录后逐个上传
    tmpdir = Path(tempfile.mkdtemp(prefix="hotpotqa_ingest_"))
    print(f"临时目录: {tmpdir}")

    success = 0
    failed = []

    try:
        for i, (filename, text) in enumerate(items, 1):
            filepath = tmpdir / filename
            filepath.write_text(text, encoding="utf-8")

            try:
                doc_id = upload_file(
                    args.base_url, token,
                    str(filepath), filename,
                    overwrite=False,
                    timeout=args.timeout_sec,
                )
                print(f"[{i}/{len(items)}] 上传成功: {filename} -> doc_id={doc_id}")
                success += 1
            except Exception as e:
                print(f"[{i}/{len(items)}] 上传失败: {filename} - {e}")
                failed.append(filename)

            # 小间隔，避免压垮服务
            time.sleep(0.3)

    finally:
        # 清理临时文件
        import shutil
        shutil.rmtree(tmpdir, ignore_errors=True)
        print(f"已清理临时目录: {tmpdir}")

    print(f"\n--- 摄入完成 ---")
    print(f"成功: {success}/{len(items)}")
    if failed:
        print(f"失败列表: {failed}")

    # 打印当前状态
    print("\n当前文档状态:")
    time.sleep(2)
    check_status(args.base_url, token, args.timeout_sec)


if __name__ == "__main__":
    main()