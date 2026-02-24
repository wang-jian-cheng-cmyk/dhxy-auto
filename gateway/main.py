from __future__ import annotations

import json
import os
import subprocess
import time
import uuid
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field


BASE_DIR = Path(__file__).resolve().parent
SYSTEM_PROMPT = (BASE_DIR / "system_prompt.txt").read_text(encoding="utf-8")
DEFAULT_GOALS = json.loads((BASE_DIR / "goals.json").read_text(encoding="utf-8"))
TMP_DIR = BASE_DIR / "tmp"
TMP_DIR.mkdir(exist_ok=True)


class GoalItem(BaseModel):
    id: str
    desc: str
    done: bool = False
    priority: int = 99


class HistoryItem(BaseModel):
    action: str
    x: int = 0
    y: int = 0
    result: str = "unknown"


class DecideRequest(BaseModel):
    session_id: str
    timestamp_ms: int
    goal_list: list[GoalItem]
    current_goal_id: str
    history: list[HistoryItem]
    screenshot_base64: str | None = None
    screenshot_file_path: str | None = None


class DecideResponse(BaseModel):
    action: Literal["tap", "swipe", "wait", "back", "stop"]
    x_norm: float = Field(ge=0.0, le=1.0)
    y_norm: float = Field(ge=0.0, le=1.0)
    swipe_to_x_norm: float = Field(ge=0.0, le=1.0)
    swipe_to_y_norm: float = Field(ge=0.0, le=1.0)
    duration_ms: int = Field(ge=50, le=1200)
    next_capture_ms: int = Field(ge=300, le=5000)
    goal_id: str
    confidence: float = Field(ge=0.0, le=1.0)
    reason: str


app = FastAPI(title="DHXY Local Gateway", version="0.1.0")
MOCK_MODE = os.getenv("MOCK_DECISION", "0") == "1"


@app.get("/health")
def health() -> dict:
    return {
        "ok": True,
        "default_goals": DEFAULT_GOALS,
    }


@app.post("/decide", response_model=DecideResponse)
async def decide(request: Request) -> DecideResponse:
    request_id = uuid.uuid4().hex[:12]
    started = time.time()
    try:
        req = await parse_decide_request(request)
        if MOCK_MODE:
            return mock_tap(req.current_goal_id)

        prompt = build_user_prompt(req)
        raw = call_opencode(prompt, req.screenshot_file_path)
        payload = extract_json(raw)

        try:
            response = DecideResponse(**payload)
        except Exception:
            response = fallback_wait(req.current_goal_id, "schema_fallback")

        if response.action in {"wait", "back", "stop"}:
            response.x_norm = 0
            response.y_norm = 0
            response.swipe_to_x_norm = 0
            response.swipe_to_y_norm = 0
        elif response.action != "swipe":
            response.swipe_to_x_norm = 0
            response.swipe_to_y_norm = 0

        elapsed_ms = int((time.time() - started) * 1000)
        print(
            f"request_id={request_id} decision action={response.action} x={response.x_norm:.3f} y={response.y_norm:.3f} "
            f"confidence={response.confidence:.2f} next={response.next_capture_ms} elapsed_ms={elapsed_ms} reason={response.reason}"
        )
        return response
    except HTTPException as e:
        detail = e.detail if isinstance(e.detail, dict) else {
            "error_code": "http_exception",
            "error_message": str(e.detail),
        }
        detail["request_id"] = request_id
        elapsed_ms = int((time.time() - started) * 1000)
        print(f"request_id={request_id} gateway_error code={detail.get('error_code')} elapsed_ms={elapsed_ms} detail={detail.get('error_message')}")
        return JSONResponse(status_code=e.status_code, content={"detail": detail})
    except Exception as e:
        elapsed_ms = int((time.time() - started) * 1000)
        detail = {
            "error_code": "internal_error",
            "error_message": str(e),
            "request_id": request_id,
        }
        print(f"request_id={request_id} gateway_error code=internal_error elapsed_ms={elapsed_ms} detail={e}")
        return JSONResponse(status_code=500, content={"detail": detail})


@app.post("/decide/mock", response_model=DecideResponse)
async def decide_mock(request: Request) -> DecideResponse:
    req = await parse_decide_request(request)
    return mock_tap(req.current_goal_id)


async def parse_decide_request(request: Request) -> DecideRequest:
    content_type = request.headers.get("content-type", "")
    if "multipart/form-data" in content_type:
        form = await request.form()
        try:
            session_id = str(form.get("session_id", "device-local"))
            timestamp_ms = int(form.get("timestamp_ms", "0"))
            current_goal_id = str(form.get("current_goal_id", "idle"))
            goal_list = json.loads(str(form.get("goal_list_json", "[]")))
            history = json.loads(str(form.get("history_json", "[]")))
            screenshot_file = form.get("screenshot_file")
        except Exception as exc:
            raise HTTPException(
                status_code=422,
                detail={
                    "error_code": "multipart_parse_failed",
                    "error_message": f"invalid multipart fields: {exc}",
                },
            ) from exc

        if screenshot_file is None:
            raise HTTPException(
                status_code=422,
                detail={
                    "error_code": "missing_screenshot_file",
                    "error_message": "missing screenshot_file",
                },
            )

        filename = f"frame-{session_id}-{timestamp_ms}.png"
        frame_path = TMP_DIR / sanitize_filename(filename)
        frame_path.parent.mkdir(parents=True, exist_ok=True)
        data = await screenshot_file.read()
        frame_path.write_bytes(data)

        return DecideRequest(
            session_id=session_id,
            timestamp_ms=timestamp_ms,
            current_goal_id=current_goal_id,
            goal_list=[GoalItem(**g) for g in goal_list],
            history=[HistoryItem(**h) for h in history],
            screenshot_file_path=str(frame_path),
        )

    body = await request.json()
    return DecideRequest(**body)


def sanitize_filename(name: str) -> str:
    return "".join(c for c in name if c.isalnum() or c in {"-", "_", "."})


def build_user_prompt(req: DecideRequest) -> str:
    data = {
        "task": "根据截图和固定搬砖目标，选择下一步动作",
        "current_goal_id": req.current_goal_id,
        "goal_list": [g.model_dump() for g in req.goal_list],
        "history": [h.model_dump() for h in req.history[-5:]],
        "note": "截图已作为附件传入。只输出严格JSON对象，不要markdown，不要代码块。",
    }
    if req.screenshot_file_path is None:
        data["screenshot_base64"] = req.screenshot_base64 or ""
    return json.dumps(data, ensure_ascii=False)


def call_opencode(user_prompt: str, screenshot_file_path: str | None) -> str:
    combined_prompt = (
        "[SYSTEM_RULES]\n"
        f"{SYSTEM_PROMPT}\n\n"
        "[USER_CONTEXT]\n"
        f"{user_prompt}\n\n"
        "只输出一个JSON对象。"
    )

    def build_cmd(binary: str) -> list[str]:
        cmd = [binary, "run"]
        if screenshot_file_path:
            cmd += ["--file", screenshot_file_path]
        return cmd

    attempts = [
        {"cmd": build_cmd("opencode"), "stdin": combined_prompt, "name": "stdin_default"},
        {
            "cmd": build_cmd("/root/.opencode/bin/opencode"),
            "stdin": combined_prompt,
            "name": "stdin_absolute",
        },
    ]

    errors: list[str] = []
    for attempt in attempts:
        try:
            result = subprocess.run(
                attempt["cmd"],
                input=attempt["stdin"],
                capture_output=True,
                text=True,
                timeout=60,
                check=False,
            )
        except Exception as exc:
            errors.append(f"{attempt['name']}: {type(exc).__name__}: {exc}")
            continue

        stdout = (result.stdout or "").strip()
        stderr = (result.stderr or "").strip()
        if result.returncode == 0 and stdout:
            return stdout

        if stderr:
            errors.append(f"{attempt['name']}: rc={result.returncode}, err={stderr[:240]}")
        else:
            errors.append(f"{attempt['name']}: rc={result.returncode}, empty output")

    detail = " | ".join(errors)[:1400]
    raise HTTPException(
        status_code=503,
        detail={
            "error_code": "opencode_failed",
            "error_message": f"opencode run failed after fallbacks: {detail}",
        },
    )


def extract_json(raw: str) -> dict:
    raw = raw.strip()
    if raw.startswith("```"):
        raw = raw.strip("`")
        start = raw.find("{")
        end = raw.rfind("}")
        if start >= 0 and end >= start:
            raw = raw[start : end + 1]

    start = raw.find("{")
    end = raw.rfind("}")
    if start < 0 or end < 0 or end < start:
        raise HTTPException(
            status_code=422,
            detail={
                "error_code": "model_json_missing",
                "error_message": "model output has no JSON object",
            },
        )

    try:
        return json.loads(raw[start : end + 1])
    except json.JSONDecodeError as exc:
        raise HTTPException(
            status_code=422,
            detail={
                "error_code": "model_json_invalid",
                "error_message": f"invalid JSON from model: {exc}",
            },
        ) from exc


def fallback_wait(goal_id: str, reason: str) -> DecideResponse:
    return DecideResponse(
        action="wait",
        x_norm=0,
        y_norm=0,
        swipe_to_x_norm=0,
        swipe_to_y_norm=0,
        duration_ms=100,
        next_capture_ms=800,
        goal_id=goal_id,
        confidence=0.2,
        reason=reason,
    )


def mock_tap(goal_id: str) -> DecideResponse:
    return DecideResponse(
        action="tap",
        x_norm=0.52,
        y_norm=0.78,
        swipe_to_x_norm=0,
        swipe_to_y_norm=0,
        duration_ms=120,
        next_capture_ms=1000,
        goal_id=goal_id,
        confidence=0.95,
        reason="mock_decision",
    )
