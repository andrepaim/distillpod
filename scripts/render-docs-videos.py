#!/usr/bin/env python3
"""Add a separate caption band to the synthetic documentation screen recordings."""

import argparse
import json
from pathlib import Path
import subprocess
import tempfile


def stamp(seconds):
    centiseconds = round(seconds * 100)
    return f"{centiseconds // 360000}:{centiseconds // 6000 % 60:02}:{centiseconds // 100 % 60:02}.{centiseconds % 100:02}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffmpeg", default="ffmpeg", help="FFmpeg with the subtitles/libass filter")
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    stories = {
        "headphones": ("Headphone capture", "headphone-capture", "Example AI output / simulated media key", [
            (0, 6, "Enable the previous-track gesture"),
            (6, 9, "Keep listening"),
            (9, 13, "Headphone command: moment saved"),
            (13, 21, "Revisit an example finished distill"),
            (21, 60, "Replay the original source"),
        ]),
        "distill": ("Distill from the player", "distillation", "Demo content / supplied example AI output", [
            (0, 5, "Tap Distill last 60 seconds"),
            (5, 9, "The source is saved before AI runs"),
            (9, 15, "An example takeaway, with its quote"),
            (15, 60, "Replay the original source"),
        ]),
    }
    for mode, (title, name, note, captions) in stories.items():
        timing = json.loads((args.input / (mode + "-timing.json")).read_text())
        duration = timing["duration_seconds"]
        header = """[Script Info]
ScriptType: v4.00+
PlayResX: 720
PlayResY: 1420
[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Title,DejaVu Sans,32,&H00FFB9B7,&H00FFFFFF,&H00160F0C,&H00160F0C,-1,0,0,0,100,100,0,0,1,0,0,8,16,16,12,1
Style: Step,DejaVu Sans,26,&H00F6F2F1,&H00FFFFFF,&H00160F0C,&H00160F0C,0,0,0,0,100,100,0,0,1,0,0,8,16,16,58,1
Style: Note,DejaVu Sans,18,&H00CBBAB0,&H00FFFFFF,&H00160F0C,&H00160F0C,0,0,0,0,100,100,0,0,1,0,0,8,16,16,105,1
[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
"""
        events = [(0, duration, "Title", title), (0, duration, "Note", note)]
        events += [(start, min(end, duration), "Step", text) for start, end, text in captions if start < duration]
        with tempfile.TemporaryDirectory() as directory:
            ass = Path(directory) / "captions.ass"
            ass.write_text(header + "".join(
                f"Dialogue: 0,{stamp(start)},{stamp(end)},{style},,0,0,0,,{text}\n"
                for start, end, style, text in events))
            subprocess.run([args.ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-ss", str(timing["start_offset_seconds"]), "-i", str(args.input / (mode + "-raw.mp4")),
                "-t", str(duration), "-vf", f"scale=720:1280,pad=720:1420:0:140:color=0x0C0F16,subtitles={ass}",
                "-r", "24", "-an", "-c:v", "libx264", "-crf", "24", "-preset", "medium",
                "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(args.output / (name + ".mp4"))], check=True)
        print(f"Rendered {name}.mp4 ({duration:.1f}s); inspect all captions and actions before publishing")


if __name__ == "__main__":
    main()
