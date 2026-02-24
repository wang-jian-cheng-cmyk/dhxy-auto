from __future__ import annotations

import argparse
import json
from pathlib import Path

import requests


def main() -> None:
    parser = argparse.ArgumentParser(description="Send one screenshot to gateway /decide")
    parser.add_argument("--url", default="http://127.0.0.1:8787/decide", help="Gateway decide URL")
    parser.add_argument("--image", required=True, help="Path to screenshot jpg/png")
    args = parser.parse_args()

    image_path = Path(args.image)
    data = {
        "session_id": "manual-image-test",
        "timestamp_ms": "1730000000000",
        "goal_list_json": json.dumps(
            [
                {"id": "mainline_unlock", "desc": "主线推进", "done": False, "priority": 1},
                {"id": "one_click_build", "desc": "一键配点", "done": False, "priority": 2},
            ],
            ensure_ascii=False,
        ),
        "current_goal_id": "mainline_unlock",
        "history_json": json.dumps([{"action": "wait", "x": 0, "y": 0, "result": "ok"}], ensure_ascii=False),
    }

    files = {
        "screenshot_file": (image_path.name, image_path.read_bytes(), "image/png"),
    }

    resp = requests.post(args.url, data=data, files=files, timeout=90)
    print(resp.status_code)
    print(json.dumps(resp.json(), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
