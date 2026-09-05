"""Deterministic HTTP test double; never a production AI fallback or quality benchmark."""

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def topic(text: str) -> int:
    if "AI" in text or "인공지능" in text:
        return 1
    if "수출" in text or "해외 진출" in text:
        return 0
    return 2


class Handler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        self.respond(200, {"status": "up"})

    def do_POST(self) -> None:
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if self.path.rstrip("/") == "/v1/embeddings":
            inputs = request["input"]
            if isinstance(inputs, str):
                inputs = [inputs]
            if not all(isinstance(value, str) for value in inputs):
                self.respond(400, {"error": {"message": "fixture expects string input"}})
                return
            dimensions = request.get("dimensions", 1536)
            data = []
            for index, value in enumerate(inputs):
                vector = [0.0] * dimensions
                vector[topic(value)] = 1.0
                data.append({"object": "embedding", "index": index, "embedding": vector})
            self.respond(200, {"object": "list", "model": request["model"], "data": data,
                               "usage": {"prompt_tokens": len(inputs), "total_tokens": len(inputs)}})
            return
        if self.path.rstrip("/") == "/v1/responses":
            messages = request["input"]
            if isinstance(messages, str):
                payload = json.loads(messages)
            else:
                user = next(message for message in reversed(messages) if message.get("role") == "user")
                content = user["content"]
                text = content if isinstance(content, str) else "".join(part.get("text", "") for part in content)
                payload = json.loads(text)
            rankings = []
            for candidate in payload["candidates"]:
                relevant = topic(candidate["title"] + " " + candidate["summary"]) == topic(payload["originalQuery"])
                rankings.append({
                    "programId": candidate["id"], "semanticRelevance": 40 if relevant else 0,
                    "targetFit": 25 if relevant else 0, "regionFit": 15 if relevant else 0,
                    "applicationStatusFit": 10 if relevant else 0, "supportTypeFit": 10 if relevant else 0,
                    "totalScore": 100 if relevant else 0,
                    "recommendationReasons": [candidate["title"][:100]],
                })
            self.respond(200, {
                "id": "resp_fixture", "created_at": 0, "object": "response", "model": request["model"],
                "error": None, "incomplete_details": None, "status": "completed", "parallel_tool_calls": False,
                "tool_choice": "none", "tools": [], "output": [{"id": "msg_fixture", "type": "message",
                    "role": "assistant", "status": "completed", "content": [{"type": "output_text",
                    "annotations": [], "text": json.dumps({"rankings": rankings}, ensure_ascii=False)}]}],
            })
            return
        self.respond(404, {"error": {"message": "unexpected fixture path"}})

    def respond(self, status: int, value: dict) -> None:
        data = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, _format: str, *_args: object) -> None:
        pass


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8002), Handler).serve_forever()
