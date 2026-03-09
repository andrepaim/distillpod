import json
import os
import re
import subprocess
import tempfile
from pathlib import Path

from config import settings
CLAUDE_BIN = settings.claude

def _format_time(seconds: float) -> str:
    m = int(seconds // 60)
    s = int(seconds % 60)
    return f'{m:02d}:{s:02d}'

def _words_to_segments(words: list[dict], chunk_sec: float = 30.0) -> list[dict]:
    '''Group words into ~30s segments for Claude context.'''
    if not words:
        return []
    segments = []
    current_words = []
    chunk_start = words[0]['start']
    for w in words:
        current_words.append(w['word'])
        if w['end'] - chunk_start >= chunk_sec:
            segments.append({
                'start': chunk_start,
                'end': w['end'],
                'text': ''.join(current_words).strip()
            })
            current_words = []
            chunk_start = w['end']
    if current_words:
        segments.append({
            'start': chunk_start,
            'end': words[-1]['end'],
            'text': ''.join(current_words).strip()
        })
    return segments

def detect_ads(words_json: str) -> list[dict]:
    '''
    Returns list of {start, end, reason} dicts for detected ad breaks.
    Uses Claude to identify sponsor reads/ads from transcript.
    '''
    words = json.loads(words_json) if isinstance(words_json, str) else words_json
    if not words or words[-1]['end'] < 120:  # skip very short episodes
        return []

    segments = _words_to_segments(words, chunk_sec=30)
    total_duration = words[-1]['end']

    # Cap context sent to Claude: ads live in first/last 30 min only.
    # For long episodes (>90 min) skip the middle to avoid timeouts.
    MAX_WINDOW_SEC = 30 * 60  # 30 minutes
    if total_duration > MAX_WINDOW_SEC * 3:
        head = [s for s in segments if s['start'] < MAX_WINDOW_SEC]
        tail = [s for s in segments if s['start'] >= total_duration - MAX_WINDOW_SEC]
        # Avoid duplicates if windows overlap
        tail = [s for s in tail if s not in head]
        segments = head + tail

    # Build transcript with timestamps for Claude
    lines = []
    for seg in segments:
        t = f'[{_format_time(seg["start"])}-{_format_time(seg["end"])}]'
        lines.append(f'{t} {seg["text"]}')
    transcript_text = '\n'.join(lines)

    prompt = (
        'You are analyzing a podcast transcript to identify advertisements and sponsor reads.\n'
        'Each line shows [start-end timestamp] followed by the spoken text.\n\n'
        'Return ONLY a JSON array of ad segments found. Each object must have:\n'
        '  start: number (seconds, match the timestamp shown)\n'
        '  end: number (seconds, match the timestamp shown)\n'
        '  reason: string (brief description e.g. "sponsor read for Squarespace")\n\n'
        'Rules:\n'
        '- Only flag clear ads, sponsor reads, and promotional content\n'
        '- Include pre-roll and mid-roll ads\n'
        '- Do NOT flag: episode intros, outros, calls-to-subscribe, host banter\n'
        '- If NO ads found, return exactly: []\n'
        '- Return ONLY the JSON array, nothing else\n\n'
        f'Total episode duration: {_format_time(total_duration)}\n\n'
        f'Transcript:\n{transcript_text}'
    )

    result = subprocess.run(
        [CLAUDE_BIN, '--print', prompt],
        capture_output=True, text=True, timeout=300
    )
    raw = result.stdout.strip()

    # Strip markdown fences if present
    raw = re.sub(r'^```(?:json)?', '', raw).strip()
    raw = re.sub(r'```$', '', raw).strip()

    try:
        ads = json.loads(raw)
        if not isinstance(ads, list):
            return []
        # Validate and clamp to episode duration
        validated = []
        for ad in ads:
            if isinstance(ad, dict) and 'start' in ad and 'end' in ad:
                start = max(0.0, float(ad['start']))
                end = min(float(ad['end']), total_duration)
                if end > start + 5:  # minimum 5s to count as ad
                    validated.append({
                        'start': start,
                        'end': end,
                        'reason': ad.get('reason', 'advertisement')
                    })
        return validated
    except (json.JSONDecodeError, ValueError):
        return []

def remove_ads_from_audio(audio_path: str, ads: list[dict], output_path: str) -> bool:
    '''
    Use ffmpeg to remove ad segments from audio.
    Returns True on success.
    '''
    if not ads or not Path(audio_path).exists():
        return False

    try:
        # Probe total duration
        probe = subprocess.run(
            ['ffprobe', '-v', 'error', '-show_entries', 'format=duration',
             '-of', 'default=noprint_wrappers=1:nokey=1', audio_path],
            capture_output=True, text=True, timeout=10
        )
        total_dur = float(probe.stdout.strip())
    except Exception:
        return False

    # Build list of keep segments (inverse of ads)
    sorted_ads = sorted(ads, key=lambda x: x['start'])
    keep_segments = []
    cursor = 0.0
    for ad in sorted_ads:
        ad_start = max(ad['start'] - 1.0, cursor)  # 1s buffer before
        ad_end = min(ad['end'] + 1.0, total_dur)   # 1s buffer after
        if ad_start > cursor + 2.0:
            keep_segments.append((cursor, ad_start))
        cursor = ad_end
    if cursor < total_dur - 2.0:
        keep_segments.append((cursor, total_dur))

    if not keep_segments:
        return False

    try:
        with tempfile.TemporaryDirectory() as tmpdir:
            # Cut each keep segment
            segment_files = []
            for i, (start, end) in enumerate(keep_segments):
                seg_path = f'{tmpdir}/seg_{i:03d}.mp3'
                subprocess.run([
                    'ffmpeg', '-y', '-ss', str(start), '-to', str(end),
                    '-i', audio_path,
                    '-acodec', 'copy', seg_path
                ], capture_output=True, timeout=120, check=True)
                segment_files.append(seg_path)

            # Write concat list
            list_path = f'{tmpdir}/concat.txt'
            with open(list_path, 'w') as f:
                for seg in segment_files:
                    f.write(f"file '{seg}'\n")

            # Concatenate
            subprocess.run([
                'ffmpeg', '-y', '-f', 'concat', '-safe', '0',
                '-i', list_path, '-acodec', 'copy', output_path
            ], capture_output=True, timeout=120, check=True)

        return Path(output_path).exists() and Path(output_path).stat().st_size > 0
    except subprocess.CalledProcessError:
        return False
