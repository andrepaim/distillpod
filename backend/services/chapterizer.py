"""
Chapterizer: uses Claude to segment a transcript into named chapters
and generate a short episode summary.

Input:  words_json (same format as transcripts table)
Output: {
    "summary": "One paragraph summary of the episode.",
    "chapters": [
        {"title": "Chapter name", "start_time": 0.0},
        ...
    ]
}

Chapters are based on the ORIGINAL audio timestamps — applies equally
to both original and ad-free versions (player adjusts for removed segments).
"""

import json
import subprocess
from pathlib import Path

CLAUDE_BIN = '/root/.local/bin/claude'
TIMEOUT_SECS = 240

# Max chars to send to Claude (keeps prompt manageable for long episodes)
MAX_TRANSCRIPT_CHARS = 12000


def _words_to_dense_segments(words: list[dict], chunk_sec: float = 120.0) -> list[dict]:
    """Group words into ~120s segments for Claude context."""
    if not words:
        return []
    segments = []
    current_words: list[str] = []
    chunk_start = words[0]['start']
    for w in words:
        current_words.append(w['word'])
        if w['end'] - chunk_start >= chunk_sec:
            segments.append({
                'start': round(chunk_start, 1),
                'end': round(w['end'], 1),
                'text': ''.join(current_words).strip()
            })
            current_words = []
            chunk_start = w['end']
    if current_words:
        segments.append({
            'start': round(chunk_start, 1),
            'end': round(words[-1]['end'], 1),
            'text': ''.join(current_words).strip()
        })
    return segments


def chapterize(words_json: str) -> dict:
    """
    Segment transcript into chapters and produce a summary.

    Returns dict with keys 'summary' (str) and 'chapters' (list of dicts).
    Raises ValueError if parsing fails.
    """
    words = json.loads(words_json)
    if not words:
        return {"summary": "", "chapters": []}

    total_duration = words[-1]['end']
    segments = _words_to_dense_segments(words, chunk_sec=120.0)

    # Format transcript with timestamps for Claude
    transcript_lines = []
    for seg in segments:
        m_start = int(seg['start'] // 60)
        s_start = int(seg['start'] % 60)
        transcript_lines.append(f"[{m_start:02d}:{s_start:02d}] {seg['text']}")
    transcript_text = '\n'.join(transcript_lines)

    # Trim to max chars — sample evenly across all segments so Claude sees the full arc
    if len(transcript_text) > MAX_TRANSCRIPT_CHARS:
        n = len(transcript_lines)
        # Keep as many lines as fit, sampled evenly
        chars_per_line = sum(len(l) for l in transcript_lines) / max(n, 1)
        max_lines = max(1, int(MAX_TRANSCRIPT_CHARS / chars_per_line))
        step = max(1, n // max_lines)
        transcript_lines = transcript_lines[::step]
        transcript_text = '\n'.join(transcript_lines)

    total_minutes = int(total_duration // 60)

    prompt = f"""You are analyzing a podcast transcript to create chapters and a summary.

The transcript below uses [MM:SS] timestamps. Total duration: {total_minutes} minutes.

TRANSCRIPT:
{transcript_text}

Respond with valid JSON only — no markdown, no extra text, no code fences.

Rules:
1. Identify 4–10 meaningful topic shifts as chapters. Fewer is fine for short episodes.
2. First chapter MUST start at 0.0 seconds (the very beginning).
3. Chapter titles should be concise (3–7 words), descriptive, and specific to the content — not generic like "Introduction" or "Conclusion".
4. Summary: 2–3 sentences capturing the core topic, key arguments, and main takeaway.
5. start_time values are floats (seconds).

Return exactly this JSON shape:
{{
  "summary": "Episode summary here.",
  "chapters": [
    {{"title": "Chapter name", "start_time": 0.0}},
    {{"title": "Next topic", "start_time": 245.5}}
  ]
}}"""

    result = subprocess.run(
        [CLAUDE_BIN, '--print', prompt],
        capture_output=True,
        text=True,
        timeout=TIMEOUT_SECS,
    )

    raw = result.stdout.strip()

    # Strip markdown code fences if Claude wraps output anyway
    if raw.startswith('```'):
        raw = '\n'.join(raw.split('\n')[1:])
        if raw.endswith('```'):
            raw = raw[:-3].strip()

    data = json.loads(raw)

    # Validate and normalise
    summary = str(data.get('summary', '')).strip()
    chapters = []
    for ch in data.get('chapters', []):
        title = str(ch.get('title', '')).strip()
        start_time = float(ch.get('start_time', 0.0))
        if title:
            chapters.append({'title': title, 'start_time': start_time})

    # Sort by start_time (Claude should already do this, but be safe)
    chapters.sort(key=lambda c: c['start_time'])

    # Ensure first chapter starts at 0
    if chapters and chapters[0]['start_time'] > 5.0:
        chapters.insert(0, {'title': 'Introduction', 'start_time': 0.0})

    return {'summary': summary, 'chapters': chapters}
