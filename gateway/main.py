from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path
from typing import Literal

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field


BASE_DIR = Path(__file__).resolve().parent
SYSTEM_PROMPT = (BASE_DIR / "system_prompt.txt").read_text(encoding="utf-8")
DEFAULT_GOALS = json.loads((BASE_DIR / "goals.json").read_text(encoding="utf-8"))


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
    screenshot_base64: str


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
def decide(req: DecideRequest) -> DecideResponse:
    if MOCK_MODE:
        return mock_tap(req.current_goal_id)

    prompt = build_user_prompt(req)
    raw = call_opencode(prompt)
    payload = extract_json(raw)

    try:
        response = DecideResponse(**payload)
    except Exception:
        response = fallback_wait(req.current_goal_id, "schema_fallback")

    if response.confidence < 0.75:
        return fallback_wait(response.goal_id, "low_confidence")

    if response.action in {"wait", "back", "stop"}:
        response.x_norm = 0
        response.y_norm = 0
        response.swipe_to_x_norm = 0
        response.swipe_to_y_norm = 0
    elif response.action != "swipe":
        response.swipe_to_x_norm = 0
        response.swipe_to_y_norm = 0

    return response


@app.post("/decide/mock", response_model=DecideResponse)
def decide_mock(req: DecideRequest) -> DecideResponse:
    return mock_tap(req.current_goal_id)


def build_user_prompt(req: DecideRequest) -> str:
    return json.dumps(
        {
            "task": "根据截图和固定搬砖目标，选择下一步动作",
            "current_goal_id": req.current_goal_id,
            "goal_list": [g.model_dump() for g in req.goal_list],
            "history": [h.model_dump() for h in req.history[-5:]],
            "screenshot_base64": req.screenshot_base64,
            "note": "只输出严格JSON对象，不要markdown，不要代码块。",
        },
        ensure_ascii=False,
    )


def call_opencode(user_prompt: str) -> str:
    combined_prompt = (
        "[SYSTEM_RULES]\n"
        f"{SYSTEM_PROMPT}\n\n"
        "[USER_CONTEXT]\n"
        f"{user_prompt}\n\n"
        "只输出一个JSON对象。"
    )

    attempts = [
        {
            "cmd": ["opencode", "run", combined_prompt],
            "stdin": None,
            "name": "positional_combined",
        },
        {
            "cmd": ["/root/.opencode/bin/opencode", "run", combined_prompt],
            "stdin": None,
            "name": "absolute_path_positional",
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
                timeout=40,
                check=False,
            )
        except Exception as exc:
            errors.append(f"{attempt['name']}: {type(exc).__name__}: {exc}")
            continue

        stdout = result.stdout.strip()
        stderr = (result.stderr or "").strip()
        if result.returncode == 0 and stdout:
            return stdout

        if stderr:
            errors.append(f"{attempt['name']}: rc={result.returncode}, err={stderr[:240]}")
        else:
            errors.append(f"{attempt['name']}: rc={result.returncode}, empty output")

    detail = " | ".join(errors)[:1400]
    raise HTTPException(status_code=503, detail=f"opencode run failed after fallbacks: {detail}")


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
        raise HTTPException(status_code=422, detail="model output has no JSON object")

    try:
        return json.loads(raw[start : end + 1])
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=422, detail=f"invalid JSON from model: {exc}") from exc


def fallback_wait(goal_id: str, reason: str) -> DecideResponse:
    return DecideResponse(
        action="wait",
        x_norm=0,
        y_norm=0,
        swipe_to_x_norm=0,
        swipe_to_y_norm=0,
        duration_ms=100,
        next_capture_ms=1400,
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
