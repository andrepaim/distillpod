import json
import os
import re
import sqlite3
import subprocess
from datetime import datetime, timezone
from pathlib import Path

import markdown
import requests

CLAUDE_BIN = "/root/.local/bin/claude"
TAVILY_KEY = os.environ.get("TAVILY_API_KEY", "")
TG_TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN", "")
TG_CHAT = os.environ.get("TG_CHAT_ID", "")
REPORTS_DIR = "/root/distillpod/reports"
PUBLIC_BASE = "https://distillpod.duckdns.org/reports"
DB_PATH = "/root/distillpod/distillpod.db"


def _clean_text(text: str) -> str:
    """Strip accidental JSON blobs, code fences, and leading/trailing whitespace."""
    text = text.strip()
    # Remove ```json ... ``` or ``` ... ``` fences
    text = re.sub(r"^```[a-z]*\n?", "", text)
    text = re.sub(r"\n?```$", "", text)
    text = text.strip()
    # If the whole thing looks like a JSON object/array, try to extract text from it
    if text.startswith("{") or text.startswith("["):
        try:
            obj = json.loads(text)
            if isinstance(obj, dict):
                # Extract all string values and join them
                parts = [v for v in obj.values() if isinstance(v, str)]
                text = "\n\n".join(parts) if parts else text
            elif isinstance(obj, list):
                parts = []
                for item in obj:
                    if isinstance(item, str):
                        parts.append(item)
                    elif isinstance(item, dict):
                        parts.extend(v for v in item.values() if isinstance(v, str))
                text = "\n\n".join(parts) if parts else text
        except json.JSONDecodeError:
            pass  # Not JSON, keep as-is
    return text.strip()


def run_research_sync(
    research_id: str,
    gist_id: str,
    gist_text: str,
    gist_summary: str,
    episode_title: str,
):
    """Multi-turn research pipeline. All sync — called via asyncio.to_thread."""

    def db_update(status, **kwargs):
        conn = sqlite3.connect(DB_PATH)
        if kwargs:
            fields = ", ".join(f"{k} = ?" for k in kwargs)
            vals = list(kwargs.values()) + [research_id]
            conn.execute(
                f"UPDATE researches SET status = ?, {fields} WHERE id = ?",
                [status] + vals,
            )
        else:
            conn.execute(
                "UPDATE researches SET status = ? WHERE id = ?",
                [status, research_id],
            )
        conn.commit()
        conn.close()

    def claude(prompt: str) -> str:
        result = subprocess.run(
            [CLAUDE_BIN, "--print", prompt],
            capture_output=True,
            text=True,
            timeout=120,
        )
        return _clean_text(result.stdout)

    def tavily_search(query: str) -> list:
        try:
            resp = requests.post(
                "https://api.tavily.com/search",
                json={
                    "api_key": TAVILY_KEY,
                    "query": query,
                    "search_depth": "advanced",
                    "max_results": 5,
                },
                timeout=15,
            )
            return resp.json().get("results", [])
        except Exception:
            return []

    try:
        db_update("running")

        # Step 1: Extract 3-5 key topics
        source_text = gist_summary or gist_text
        topics_raw = claude(
            f"Extract 3 to 5 key topics from this podcast distillation. "
            f"Return ONLY a JSON array of short topic strings (e.g. [\"Topic One\", \"Topic Two\"]). "
            f"No explanation, no markdown, just the JSON array.\n\n{source_text}"
        )
        try:
            topics = json.loads(topics_raw)
            if not isinstance(topics, list):
                raise ValueError
            topics = [str(t) for t in topics if t]
        except Exception:
            # Fallback: split by newline or use source as single topic
            topics = [line.strip("- •*").strip() for line in topics_raw.split("\n") if line.strip()][:5]
            if not topics:
                topics = [source_text[:80]]

        # Step 2: Research each topic
        topic_reports = []
        all_sources = []
        for topic in topics[:5]:
            results = tavily_search(f"{topic} research 2024 2025")
            # Build source context — plain text only, no JSON
            source_snippets = []
            for r in results:
                title = r.get("title", "")
                content = r.get("content", "")[:600]
                url = r.get("url", "")
                source_snippets.append(f"Source: {title}\n{content}")
            sources_text = "\n\n---\n\n".join(source_snippets)
            all_sources.extend(results)

            synthesis = claude(
                f"You are writing a section of a research report. "
                f"Based on the web sources below, write a detailed prose analysis of: {topic}\n\n"
                f"Structure your response with these exact markdown headings:\n"
                f"### What the research says\n"
                f"### Key findings\n"
                f"### Counterarguments\n\n"
                f"Write in flowing prose paragraphs. Do NOT return JSON or bullet lists. "
                f"Do NOT include a title or introduction — start directly with the first heading.\n\n"
                f"--- Sources ---\n{sources_text}"
            )
            topic_reports.append({"topic": topic, "synthesis": synthesis, "sources": results})

        # Step 3: Final executive summary
        topics_combined = "\n\n".join(
            f"## {r['topic']}\n{r['synthesis']}" for r in topic_reports
        )
        executive_summary = claude(
            f"Write a 2 to 3 paragraph executive summary synthesizing the following research findings. "
            f"Write only flowing prose — no headings, no JSON, no bullet points. "
            f"Focus on the most important insights and their implications.\n\n"
            f"Episode: {episode_title}\n\n{topics_combined}"
        )

        # Step 4: Build HTML — convert all Claude text via markdown
        unique_sources = {s["url"]: s for s in all_sources}
        sources_html = "\n".join(
            f'<li><a href="{s["url"]}" target="_blank">{s["title"]}</a>'
            f' &mdash; {s.get("content", "")[:120]}...</li>'
            for s in unique_sources.values()
        )

        topics_html = ""
        for r in topic_reports:
            syn_html = markdown.markdown(r["synthesis"], extensions=["extra"])
            topics_html += (
                f'<section class="topic">'
                f'<h2>{r["topic"]}</h2>'
                f'<div class="synthesis">{syn_html}</div>'
                f'</section>\n'
            )

        exec_html = markdown.markdown(executive_summary, extensions=["extra"])
        generated_at = datetime.now().strftime("%B %d, %Y at %H:%M")

        html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Research: {episode_title}</title>
<style>
  * {{ box-sizing: border-box; }}
  body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; max-width: 820px; margin: 0 auto; padding: 1.5rem 1rem; background: #1a1a1a; color: #e5e5e5; line-height: 1.7; }}
  h1 {{ color: #FFD700; border-bottom: 2px solid #FFD700; padding-bottom: 0.5rem; font-size: 1.6rem; }}
  h2 {{ color: #FFD700; margin-top: 0; font-size: 1.2rem; }}
  h3 {{ color: #ccc; font-size: 1rem; margin-top: 1.25rem; margin-bottom: 0.5rem; }}
  .meta {{ color: #888; font-size: 0.85rem; margin-bottom: 1.5rem; }}
  .summary {{ background: #242424; border-left: 4px solid #FFD700; padding: 1rem 1.5rem; border-radius: 0 8px 8px 0; margin-bottom: 2rem; }}
  .summary p {{ margin: 0.6rem 0; }}
  .topic {{ background: #242424; border-radius: 8px; padding: 1.25rem 1.5rem; margin-bottom: 1.25rem; }}
  .synthesis p {{ margin: 0.6rem 0; }}
  .synthesis ul, .synthesis ol {{ padding-left: 1.5rem; margin: 0.5rem 0; }}
  .synthesis li {{ margin-bottom: 0.3rem; }}
  .sources {{ background: #242424; border-radius: 8px; padding: 1.25rem 1.5rem; margin-top: 1.5rem; }}
  .sources ul {{ padding-left: 1.25rem; margin: 0.5rem 0; }}
  .sources li {{ margin-bottom: 0.4rem; font-size: 0.9rem; }}
  a {{ color: #60a5fa; word-break: break-all; }}
  hr {{ border: none; border-top: 1px solid #444; margin: 1rem 0; }}
  strong {{ color: #fff; }}
  .footer {{ color: #555; font-size: 0.8rem; margin-top: 2rem; text-align: center; }}
</style>
</head>
<body>
<h1>🔬 Research Report</h1>
<div class="meta">Episode: {episode_title} &nbsp;·&nbsp; Generated: {generated_at}</div>
<div class="summary">
  <h2 style="margin-top:0">Executive Summary</h2>
  {exec_html}
</div>
{topics_html}
<div class="sources">
  <h2>Sources</h2>
  <ul>{sources_html}</ul>
</div>
<div class="footer">Generated by DistillPod · Research powered by Claude AI and Tavily Search</div>
</body>
</html>"""

        # Step 5: Save file
        Path(REPORTS_DIR).mkdir(exist_ok=True)
        file_path = f"{REPORTS_DIR}/{research_id}.html"
        Path(file_path).write_text(html, encoding="utf-8")
        public_url = f"{PUBLIC_BASE}/{research_id}.html"

        db_update(
            "done",
            file_path=file_path,
            public_url=public_url,
            finished_at=datetime.now(timezone.utc).isoformat(),
        )

        # Step 6: Telegram notification
        requests.post(
            f"https://api.telegram.org/bot{TG_TOKEN}/sendMessage",
            data={
                "chat_id": TG_CHAT,
                "text": (
                    f"🔬 Research ready!\n\n"
                    f"Episode: {episode_title}\n"
                    f"Topic: {source_text[:80]}...\n\n"
                    f"📄 {public_url}"
                ),
            },
            timeout=10,
        )

    except Exception as e:
        db_update("error", error=str(e)[:500])
