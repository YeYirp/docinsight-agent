#!/usr/bin/env python3
"""Long-lived faster-whisper worker used by the Java ingestion service."""

import argparse
import json
import sys
import traceback


def emit(payload):
    print(json.dumps(payload, ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="small")
    parser.add_argument("--device", default="auto")
    parser.add_argument("--compute-type", default="int8")
    parser.add_argument("--language", default="auto")
    args = parser.parse_args()

    try:
        from faster_whisper import WhisperModel

        model = WhisperModel(
            args.model,
            device=args.device,
            compute_type=args.compute_type,
        )
    except Exception as error:
        traceback.print_exc(file=sys.stderr)
        emit({"status": "error", "error": str(error)})
        return 2

    emit({"status": "ready", "model": args.model})

    for raw_line in sys.stdin:
        line = raw_line.strip()
        if not line:
            continue
        request_id = ""
        try:
            request = json.loads(line)
            request_id = request.get("id", "")
            language = request.get("language") or args.language
            if language in ("", "auto"):
                language = None

            segments, info = model.transcribe(
                request["path"],
                language=language,
                vad_filter=True,
                beam_size=5,
                condition_on_previous_text=True,
            )
            items = []
            for segment in segments:
                text = (segment.text or "").strip()
                if not text:
                    continue
                items.append({
                    "startSeconds": float(segment.start),
                    "endSeconds": float(segment.end),
                    "text": text,
                })

            emit({
                "id": request_id,
                "ok": True,
                "durationSeconds": float(info.duration),
                "language": getattr(info, "language", None),
                "segments": items,
            })
        except Exception as error:
            traceback.print_exc(file=sys.stderr)
            emit({"id": request_id, "ok": False, "error": str(error)})

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
