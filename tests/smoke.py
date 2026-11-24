"""Exercise the packaged server against a real local Qdrant and a local OpenAI stub."""

import copy
import http.cookiejar
import json
import os
import re
import secrets
import select
import shlex
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from uuid import uuid4

ROOT = Path(__file__).resolve().parents[1]


class OpenAiStub(BaseHTTPRequestHandler):
    fail_summary = False
    fail_embeddings = False
    requests = []

    def do_POST(self):
        payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        type(self).requests.append((self.path, payload))
        if self.path == "/v1/embeddings" and not self.fail_embeddings:
            dimension = payload.get("dimensions", 1536)
            texts = payload["input"] if isinstance(payload["input"], list) else [payload["input"]]
            response = {"object": "list", "model": payload["model"], "data": [
                {"object": "embedding", "index": index, "embedding": [1.0] + [0.0] * (dimension - 1)}
                for index, _ in enumerate(texts)
            ], "usage": {"prompt_tokens": 1, "total_tokens": 1}}
        elif self.path == "/v1/responses" and not self.fail_summary:
            response = {"id": "resp_smoke", "status": "completed", "output": [
                {"type": "message", "role": "assistant", "id": "msg_smoke", "status": "completed",
                 "content": [{"type": "output_text", "text": "Проверка товарной позиции", "annotations": []}]}
            ]}
        else:
            self.send_response(503)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"error":{"message":"Temporary test outage","type":"server_error"}}')
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(response).encode())

    def log_message(self, *args):
        pass


def request(url, method="GET", body=None, headers=None, opener=None):
    headers = dict(headers or {})
    if body is not None and not isinstance(body, bytes):
        body = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    connection = urllib.request.Request(url, data=body, method=method, headers=headers)
    send = opener.open if opener else urllib.request.urlopen
    try:
        response = send(connection, timeout=35)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        text = response.read().decode("utf-8")
        return response.status, json.loads(text) if "application/json" in response.headers.get(
            "Content-Type", "") else text, response.headers


def free_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


@contextmanager
def launch(directory, environment):
    with (directory / "server.log").open("w+", encoding="utf-8") as logs:
        process = subprocess.Popen(
            ["java", "-jar", str(ROOT / "target/test-memory-mcp-0.1.0.jar")],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=logs, encoding="utf-8",
            env={**os.environ, **environment},
        )
        try:
            yield process
        except Exception:
            logs.seek(0)
            print(logs.read()[-8000:], file=sys.stderr)
            raise
        finally:
            process.stdin.close()
            try:
                process.wait(timeout=12)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                raise AssertionError("JVM did not shut down when the MCP client closed stdin")


def health(base):
    deadline = time.monotonic() + 10
    while True:
        try:
            return request(base + "/health")
        except urllib.error.URLError:
            if time.monotonic() >= deadline:
                raise
            time.sleep(0.1)


def rpc(process, method, params=None, message_id=None):
    message = {"jsonrpc": "2.0", "method": method, "params": params or {}}
    if message_id is not None:
        message["id"] = message_id
    process.stdin.write(json.dumps(message) + "\n")
    process.stdin.flush()
    if message_id is None:
        return None
    if not select.select([process.stdout], [], [], 40)[0]:
        raise AssertionError(f"No reply to {method}")
    line = process.stdout.readline()
    assert line, f"Server closed MCP stream for {method}"
    response = json.loads(line)
    assert response.get("id") == message_id and "result" in response, response
    return response["result"]


def verify(directory, environment, token, qdrant_url, collection):
    base = f"http://127.0.0.1:{environment['MCP_MEMORY_HTTP_PORT']}"
    endpoint = base + "/api/v1/memories"
    authorization = {"Authorization": "Bearer " + token}
    observation_id, other_id, timeout_id = (str(uuid4()) for _ in range(3))
    observation = {
        "serviceId": "catalog", "environment": "local", "runId": "run-42", "testCaseId": "item-404",
        "method": "GET", "route": "/items/{id}", "observedAt": "2026-11-23T08:30:00Z",
        "task": "Проверить отсутствующую позицию", "approach": "Использовать пустую фикстуру",
        "evidence": {"executionStatus": "COMPLETED", "passedAssertions": 2, "failedAssertions": 0,
                     "expected": "HTTP 404", "observed": "HTTP 404", "reportRef": "catalog/run-42"},
    }
    query = {"serviceId": "catalog", "environment": "local", "query": "Отсутствующая позиция",
             "method": "GET", "route": "/items/{id}"}
    path = f"{endpoint}/{observation_id}"

    with launch(directory, environment) as process:
        initialized = rpc(process, "initialize", {"protocolVersion": "2025-11-25", "capabilities": {},
                          "clientInfo": {"name": "integration-smoke", "version": "1"}}, 1)
        assert initialized["protocolVersion"] == "2025-11-25", initialized
        rpc(process, "notifications/initialized")
        tools = rpc(process, "tools/list", {}, 2)["tools"]
        assert {tool["name"] for tool in tools} == {"record_test_observation", "search_test_observations"}
        assert all("inputSchema" in tool and "outputSchema" in tool for tool in tools)
        state = health(base)
        assert state[0] == 200, state[:2]
        assert request(endpoint)[0] == 401
        assert request(endpoint, headers={**authorization, "Origin": "http://other.test"})[0] == 403
        result = rpc(process, "tools/call", {
            "name": "record_test_observation", "arguments": {"observationId": observation_id, **observation},
        }, 3)
        assert not result.get("isError"), result
        receipt = result["structuredContent"]
        assert receipt["observationId"] == observation_id
        assert json.loads(result["content"][0]["text"]) == receipt
        assert "fixture: lost Qdrant acknowledgement" in (directory / "server.log").read_text()

        with ThreadPoolExecutor(max_workers=2) as pool:
            retries = list(pool.map(lambda _: request(path, "PUT", observation, authorization), range(2)))
        assert all(status == 200 and result == receipt for status, result, _ in retries), retries
        assert request(path, "PUT", dict(observation, task="Другая попытка"), authorization)[0] == 409
        invalid = copy.deepcopy(observation)
        invalid["evidence"]["passedAssertions"] = 2.5
        assert request(path, "PUT", invalid, authorization)[0] == 400
        secret = request(path, "PUT", dict(observation, task="Authorization: Bearer sensitive-fixture"), authorization)
        assert secret[1]["code"] == "SENSITIVE_INPUT"
        assert len(OpenAiStub.requests) == 2, OpenAiStub.requests

        OpenAiStub.fail_summary = True
        other_path = f"{endpoint}/{other_id}"
        other = copy.deepcopy(observation)
        other["environment"] = "dev"
        other["evidence"]["failedAssertions"] = 1
        assert request(other_path, "PUT", other, authorization)[0] == 200
        assert request(other_path, headers=authorization)[1]["autoOutcome"] == "FAILED"
        timeout = {**observation, "evidence": {"executionStatus": "TIMEOUT", "expected": "HTTP 404"}}
        assert request(f"{endpoint}/{timeout_id}", "PUT", timeout, authorization)[0] == 200
        assert request(endpoint + "?outcome=INCONCLUSIVE", headers=authorization)[1]["items"][0][
            "observationId"] == timeout_id

        result = rpc(process, "tools/call", {"name": "search_test_observations", "arguments": query}, 4)
        assert not result.get("isError"), result
        hits = result["structuredContent"]["items"]
        assert [item["observationId"] for item in hits] == [observation_id], hits
        assert hits[0]["effectiveOutcome"] == "SUCCEEDED"
        assert request(endpoint + "/search", "POST", dict(query, route="/other"), authorization)[1]["items"] == []
        status, card, headers = request(path, headers=authorization)
        assert status == 200 and card["autoOutcome"] == "SUCCEEDED" and headers["ETag"] == '"1"'

        headers = {**authorization, "If-Match": '"1"'}
        patch = {"manualReview": {"verdict": "FAILED", "reason": "Проверка не покрывает ошибку каталога"}}
        with ThreadPoolExecutor(max_workers=2) as pool:
            patches = list(pool.map(lambda _: request(path, "PATCH", patch, headers), range(2)))
        assert sorted(status for status, _, _ in patches) == [200, 412], patches
        card = next(value for status, value, _ in patches if status == 200)
        assert card["revision"] == 2 and card["autoOutcome"] == "SUCCEEDED"
        assert card["effectiveOutcome"] == "FAILED" and card["outcomeSource"] == "MANUAL"
        assert request(path, "PATCH", {"status": "ARCHIVED"}, {**authorization, "If-Match": '"2"'})[0] == 200
        assert request(path, "PUT", observation, authorization)[1] == receipt
        assert request(endpoint + "/search", "POST", query, authorization)[1] == {"items": []}
        assert request(endpoint + "?status=ARCHIVED", headers=authorization)[1]["items"][0][
            "observationId"] == observation_id
        assert request(path, "PATCH", {"status": "ACTIVE", "manualReview": None},
                       {**authorization, "If-Match": '"3"'})[0] == 200
        inconclusive = request(endpoint + "/search", "POST", dict(query, outcomes=["INCONCLUSIVE"]), authorization)
        assert [item["observationId"] for item in inconclusive[1]["items"]] == [timeout_id]
        assert inconclusive[1]["items"][0]["observed"] is None

        status, page, _ = request(endpoint + "?limit=1", headers=authorization)
        assert status == 200 and len(page["items"]) == 1 and page["nextCursor"]
        cursor = page["nextCursor"]
        second = request(endpoint + "?limit=1&cursor=" + cursor, headers=authorization)[1]
        assert second["items"][0]["observationId"] != page["items"][0]["observationId"]
        assert request(endpoint + "?serviceId=elsewhere&cursor=" + cursor, headers=authorization)[0] == 400

        browser = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        login = request(base + "/login", opener=browser)
        csrf = re.search(r'name="_csrf"[^>]*value="([^"]+)"', login[1]).group(1)
        form = urllib.parse.urlencode({"username": "operator", "password": token, "_csrf": csrf}).encode()
        status, html, _ = request(base + "/login", "POST", form,
                                  {"Content-Type": "application/x-www-form-urlencoded"}, browser)
        assert status == 200 and observation["task"] in html, (status, html[:250])
        status, html, _ = request(base + "/ui/memories/" + observation_id, opener=browser)
        assert status == 200 and "Ручная оценка" in html
        assert request(base + "/ui/memories/" + observation_id, "POST", b"revision=4&action=archive",
                       {"Content-Type": "application/x-www-form-urlencoded"}, browser)[0] == 403
        csrf = re.search(r'name="_csrf"[^>]*value="([^"]+)"', html).group(1)
        form = urllib.parse.urlencode({"revision": "4", "action": "archive", "_csrf": csrf}).encode()
        status, html, _ = request(base + "/ui/memories/" + observation_id, "POST", form,
                                  {"Content-Type": "application/x-www-form-urlencoded"}, browser)
        assert status == 200 and "Восстановить" in html
        assert request(path, "PATCH", {"status": "ACTIVE"}, {**authorization, "If-Match": '"5"'})[0] == 200

        OpenAiStub.fail_embeddings = True
        assert request(endpoint + "/search", "POST", query, authorization)[0] == 503
        assert request(path, headers=authorization)[0] == 200
        assert request(base + "/health")[1]["embeddings"] == "DEPENDENCY_UNAVAILABLE"
        OpenAiStub.fail_embeddings = False

    with launch(directory, environment) as process:
        rpc(process, "initialize", {"protocolVersion": "2025-11-25", "capabilities": {},
            "clientInfo": {"name": "integration-smoke", "version": "1"}}, 1)
        assert health(base)[0] == 200
        status, card, _ = request(path, headers=authorization)
        assert status == 200 and card["revision"] == 6 and card["effectiveOutcome"] == "SUCCEEDED"

    status, points, _ = request(f"{qdrant_url}/collections/{collection}/points/count", "POST", {"exact": True},
                                {"api-key": environment["QDRANT_API_KEY"]})
    assert status == 200 and points["result"]["count"] == 3
    summary_call = next(payload for path, payload in OpenAiStub.requests if path == "/v1/responses")
    assert summary_call["store"] is False and "reportRef" not in summary_call["input"]
    print("Verified MCP STDIO, REST, UI, real Qdrant, retries, filters, revisions, restart and provider failure.")


def main():
    qdrant_url = os.environ.get("MCP_MEMORY_QDRANT_URL", "http://127.0.0.1:6333").rstrip("/")
    assert urllib.parse.urlsplit(qdrant_url).hostname == "127.0.0.1", "Use a local test Qdrant"
    qdrant_key = os.environ["QDRANT_API_KEY"]
    token = secrets.token_hex(32)
    collection = "memory_smoke_" + uuid4().hex

    with tempfile.TemporaryDirectory(prefix="memory-smoke-") as name:
        directory = Path(name)
        executable = directory / "python-stub"
        injection = directory / "lost_ack.py"
        injection.write_text(
            """import runpy
import sys
from qdrant_client import QdrantClient

upsert = QdrantClient.upsert
lose_ack = True

def upsert_with_lost_ack(self, *args, **kwargs):
    global lose_ack
    result = upsert(self, *args, **kwargs)
    if lose_ack and kwargs.get("update_mode") is not None:
        lose_ack = False
        print("fixture: lost Qdrant acknowledgement", file=sys.stderr, flush=True)
        raise TimeoutError("simulated loss after a committed Qdrant write")
    return result

QdrantClient.upsert = upsert_with_lost_ack
runpy.run_path(sys.argv[-1], run_name="__main__")
""",
            encoding="utf-8",
        )
        with ThreadingHTTPServer(("127.0.0.1", 0), OpenAiStub) as stub:
            test_url = f"http://127.0.0.1:{stub.server_port}/v1"
            executable.write_text(
                "#!/bin/sh\nOPENAI_BASE_URL=" + shlex.quote(test_url)
                + " exec " + shlex.quote(sys.executable) + " -u " + shlex.quote(str(injection))
                + ' "$@"\n', encoding="utf-8",
            )
            executable.chmod(0o700)
            thread = threading.Thread(target=stub.serve_forever, daemon=True)
            thread.start()
            environment = {
                "OPENAI_API_KEY": "fixture-openai-key", "QDRANT_API_KEY": qdrant_key,
                "MCP_MEMORY_QDRANT_URL": qdrant_url, "MCP_MEMORY_COLLECTION": collection,
                "MCP_MEMORY_PYTHON": str(executable), "MCP_MEMORY_OPERATOR_TOKEN": token,
                "MCP_MEMORY_HTTP_PORT": str(free_port()),
            }
            try:
                verify(directory, environment, token, qdrant_url, collection)
            finally:
                stub.shutdown()
                request(qdrant_url + "/collections/" + collection, "DELETE", headers={"api-key": qdrant_key})


if __name__ == "__main__":
    main()
