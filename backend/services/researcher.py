import json
import os
import sqlite3
import subprocess
from datetime import datetime, timezone
from pathlib import Path

import requests

CLAUDE_BIN = "/root/.local/bin/claude"
TAVILY_KEY = "***REMOVED***"
TG_TOKEN = "***REMOVED***"
TG_CHAT = "8592602749"
REPORTS_DIR = "/root/distillpod/reports"
PUBLIC_BASE = "https://distillpod.duckdns.org/reports"
DB_PATH = "/root/distillpod/distillpod.db"


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
        fields = ", ".join(f"{k} = ?" for k in kwargs)
        vals = list(kwargs.values()) + [research_id]
        if fields:
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

    def claude(prompt):
        r = subprocess.run(
            [CLAUDE_BIN, "--print", prompt],
            capture_output=True,
            text=True,
            timeout=120,
        )
        return r.stdout.strip()

    def tavily_search(query):
        r = requests.post(
            "https://api.tavily.com/search",
            json={
                "api_key": TAVILY_KEY,
                "query": query,
                "search_depth": "advanced",
                "max_results": 5,
            },
            timeout=15,
        )
        return r.json().get("results", [])

    try:
        db_update("running")

        # Step 1: Extract topics
        source_text = gist_summary or gist_text
        topics_raw = claude(
            f"Extract 3-5 key topics from this podcast distillation as a JSON array "
            f"of strings. Return ONLY the JSON array, nothing else.\n\n{source_text}"
        )
        try:
            topics = json.loads(topics_raw)
            if not isinstance(topics, list):
                raise ValueError
        except Exception:
            topics = [source_text[:100]]

        # Step 2: Research each topic
        topic_reports = []
        all_sources = []
        for topic in topics[:5]:
            results = tavily_search(f"{topic} research 2024 2025")
            sources_text = "\n\n".join(
                f'Title: {r["title"]}\nURL: {r["url"]}\nContent: {r["content"][:600]}'
                for r in results
            )
            all_sources.extend(results)
            synthesis = claude(
                f"Based on the following web search results, write a detailed analysis of: {topic}\n\n"
                f"Format with sections: What the research says, Key findings, Counterarguments.\n\n"
                f"Sources:\n{sources_text}"
            )
            topic_reports.append(
                {"topic": topic, "synthesis": synthesis, "sources": results}
            )

        # Step 3: Final synthesis
        topics_combined = "\n\n".join(
            f'## {r["topic"]}\n{r["synthesis"]}' for r in topic_reports
        )
        executive_summary = claude(
            f"Write a 2-3 paragraph executive summary synthesizing these research "
            f"findings about: {episode_title}\n\n{topics_combined}"
        )

        # Step 4: Build HTML
        unique_sources = {s["url"]: s for s in all_sources}
        sources_html = "\n".join(
            f'<li><a href="{s["url"]}" target="_blank">{s["title"]}</a> '
            f'&mdash; {s.get("content", "")[:120]}...</li>'
            for s in unique_sources.values()
        )
        topics_html = ""
        for r in topic_reports:
            syn_html = r["synthesis"].replace("\n", "<br>")
            topics_html += (
                f'<section class="topic"><h2>{r["topic"]}</h2>'
                f'<div class="synthesis">{syn_html}</div></section>\n'
            )

        generated_at = datetime.now().strftime("%B %d, %Y at %H:%M")
        html = f"""<!DOCTYPE html>
<html lang="en">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Research: {episode_title}</title>
<style>
  body {{ font-family: -apple-system, sans-serif; max-width: 800px; margin: 0 auto; padding: 2rem; background: #1a1a1a; color: #e5e5e5; }}
  h1 {{ color: #FFD700; border-bottom: 2px solid #FFD700; padding-bottom: 0.5rem; }}
  h2 {{ color: #FFD700; margin-top: 2rem; }}
  .meta {{ color: #888; font-size: 0.9rem; margin-bottom: 2rem; }}
  .summary {{ background: #242424; border-left: 4px solid #FFD700; padding: 1rem 1.5rem; border-radius: 0 8px 8px 0; margin-bottom: 2rem; }}
  .topic {{ background: #242424; border-radius: 8px; padding: 1.5rem; margin-bottom: 1.5rem; }}
  .synthesis {{ line-height: 1.7; }}
  .sources {{ background: #242424; border-radius: 8px; padding: 1.5rem; }}
  .sources ul {{ padding-left: 1.5rem; }}
  .sources li {{ margin-bottom: 0.5rem; }}
  a {{ color: #60a5fa; }}
  .footer {{ color: #666; font-size: 0.8rem; margin-top: 3rem; text-align: center; }}
</style></head>
<body>
<h1>&#x1f52c; Research Report</h1>
<div class="meta">Episode: {episode_title} &middot; Generated: {generated_at}</div>
<div class="summary"><h2 style="margin-top:0">Executive Summary</h2>{executive_summary.replace(chr(10), '<br>')}</div>
{topics_html}
<div class="sources"><h2>Sources</h2><ul>{sources_html}</ul></div>
<div class="footer">Generated by DistillPod &middot; Research powered by Claude AI and Tavily Search</div>
</body></html>"""

        # Step 5: Save file
        Path(REPORTS_DIR).mkdir(exist_ok=True)
        file_path = f"{REPORTS_DIR}/{research_id}.html"
        Path(file_path).write_text(html)
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
                    f"\U0001f52c Research ready!\n\n"
                    f"Episode: {episode_title}\n"
                    f"Topic: {source_text[:80]}...\n\n"
                    f"\U0001f4c4 {public_url}"
                ),
            },
            timeout=10,
        )

    except Exception as e:
        db_update("error", error=str(e)[:500])
