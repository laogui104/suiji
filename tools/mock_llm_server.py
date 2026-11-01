#!/usr/bin/env python3
"""本地模拟 OpenAI 兼容服务，用于验证 App 的完整调用链。
支持 /v1/chat/completions 与 /v1/audio/transcriptions。
仅用于联调测试，不参与交付。
"""
import json
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

VALID_KEY = "testkey-123456"
ASR_TEXT = "下周三下午三点和张总过报价单"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print("[mock] " + fmt % args, flush=True)

    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self._send(200, {"ok": True, "paths": ["/v1/chat/completions", "/v1/audio/transcriptions"]})

    def do_POST(self):
        auth = self.headers.get("Authorization", "")
        if not auth.startswith("Bearer "):
            self._send(401, {"error": {"message": "missing api key"}})
            return
        if auth.split(" ", 1)[1].strip() != VALID_KEY:
            self._send(401, {"error": {"message": "invalid api key"}})
            return

        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length)

        if self.path.rstrip("/").endswith("/audio/transcriptions"):
            ctype = self.headers.get("Content-Type", "")
            has_file = b'name="file"' in raw
            rgb = b"RIFF" in raw
            model = "?"
            for line in raw.split(b"\r\n"):
                if line.strip() and b"Content-Disposition" not in line and b"--" not in line:
                    pass
            idx = raw.find(b'name="model"')
            if idx > 0:
                seg = raw[idx: idx + 200].split(b"\r\n\r\n")
                if len(seg) > 1:
                    model = seg[1].split(b"\r\n")[0].decode("utf-8", "ignore")
            print(
                "[mock] ASR bytes=%d multipart=%s wav=%s model=%s"
                % (len(raw), has_file, rgb, model),
                flush=True,
            )
            if not has_file or not rgb:
                self._send(400, {"error": {"message": "missing wav file"}})
                return
            time.sleep(0.6)
            self._send(200, {"text": ASR_TEXT})
            return

        req = json.loads(raw.decode("utf-8"))
        user_msg = ""
        for m in req.get("messages", []):
            if m.get("role") == "user":
                user_msg = m.get("content", "")

        if "回复两个字" in user_msg:
            content = "在的"
        else:
            content = json.dumps(
                {
                    "type": "TODO",
                    "confidence": 0.94,
                    "title": "和张总过报价单",
                    "todos": [
                        {
                            "title": "和张总过报价单",
                            "dueAt": 1793775600000,
                            "duePrecision": "EXACT_TIME",
                            "rawTimeExpr": "下周三下午三点",
                            "counterparty": "张总",
                            "location": None,
                            "tags": ["商务"],
                        }
                    ],
                    "ideas": [],
                    "reason": "mock: 含明确时间与具体动作",
                },
                ensure_ascii=False,
            )

        self._send(
            200,
            {
                "id": "mock-1",
                "object": "chat.completion",
                "model": req.get("model", "mock-model"),
                "choices": [
                    {
                        "index": 0,
                        "finish_reason": "stop",
                        "message": {"role": "assistant", "content": content},
                    }
                ],
                "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20},
            },
        )


if __name__ == "__main__":
    print("mock server on 0.0.0.0:8000  key=%s" % VALID_KEY, flush=True)
    HTTPServer(("0.0.0.0", 8000), Handler).serve_forever()
