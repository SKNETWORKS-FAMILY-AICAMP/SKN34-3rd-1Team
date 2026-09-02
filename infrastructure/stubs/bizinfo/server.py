import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


HOST = "0.0.0.0"
PORT = 8001
SEARCH_PATH = "/1421000/bizinfo/pblancBsnsService"
EXPECTED_QUERY = {
    "serviceKey": ["compose+verification/key="],
    "pageNo": ["1"],
    "numOfRows": ["1000"],
    "dataType": ["json"],
}
RESPONSE_BODY = Path("response.json").read_bytes()


class BizInfoStubHandler(BaseHTTPRequestHandler):
    def do_GET(self) -> None:
        request = urlparse(self.path)

        if request.path == "/health":
            self._respond(200, b'{"status":"up"}')
            return

        if request.path != SEARCH_PATH:
            self._respond(404, b'{"error":"unexpected path"}')
            return

        query = parse_qs(request.query, keep_blank_values=True)
        if any(query.get(name) != value for name, value in EXPECTED_QUERY.items()):
            body = json.dumps(
                {"error": "unexpected query", "receivedParameters": sorted(query)},
                ensure_ascii=False,
            ).encode("utf-8")
            self._respond(400, body)
            return

        self._respond(200, RESPONSE_BODY)

    def log_message(self, _format: str, *_args: object) -> None:
        # Never print the request target because its query contains serviceKey.
        print(f"{self.address_string()} - request handled", flush=True)

    def _respond(self, status: int, body: bytes) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    ThreadingHTTPServer((HOST, PORT), BizInfoStubHandler).serve_forever()
