"""Incremental repository-local Markdown targets/anchors; never fetch URLs."""
import re
from collections import Counter
from html import unescape
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit


class HtmlLinks(HTMLParser):
    def __init__(self):
        super().__init__()
        self.targets = []
        self.anchors = set()

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        self.anchors.update(values[key] for key in ['id', 'name'] if values.get(key))
        self.targets.extend(values[key] for key in ['href', 'src'] if values.get(key))


def without_fences(text):
    lines, fence = [], None
    for line in text.splitlines():
        match = re.match(r'^ {0,3}(`{3,}|~{3,})', line)
        if match:
            marker = match.group(1)
            if fence is None:
                fence = marker
            elif marker[0] == fence[0] and len(marker) >= len(fence) and not line[match.end():].strip():
                fence = None
            lines.append('')
        else:
            lines.append(line if fence is None else '')
    return '\n'.join(lines)


def inline_targets(text):
    # Balanced parentheses and angle-enclosed destinations cover repo Markdown.
    for match in re.finditer(r'(?<!\\)\]\(\s*', text):
        start = match.end()
        if start < len(text) and text[start] == '<':
            end = text.find('>', start + 1)
            if end >= 0:
                yield text[start + 1:end]
            continue
        token, depth, escaped = [], 0, False
        for char in text[start:]:
            if escaped:
                token.append(char)
                escaped = False
            elif char == '\\':
                escaped = True
            elif char == '(':
                depth += 1
                token.append(char)
            elif char == ')':
                if depth == 0:
                    break
                depth -= 1
                token.append(char)
            elif char.isspace() and depth == 0:
                break
            else:
                token.append(char)
        if token:
            yield ''.join(token)
    for match in re.finditer(r'^ {0,3}\[[^\]\n]+\]:\s*(?:<([^>]+)>|(\S+))', text, re.M):
        yield match.group(1) or match.group(2)


def anchors(text):
    text = without_fences(text)
    html = HtmlLinks()
    html.feed(re.sub(r'(`+).*?\1', '', text))
    result, seen = set(html.anchors), Counter()
    lines = text.splitlines()
    titles = []
    for i, line in enumerate(lines):
        match = re.match(r'^ {0,3}#{1,6}\s+(.+?)\s*#*\s*$', line)
        if match:
            titles.append(match.group(1))
        elif i and re.fullmatch(r' {0,3}(?:=+|-+)\s*', line) and lines[i - 1].strip():
            titles.append(lines[i - 1].strip())
    for title in titles:
        title = re.sub(r'\[([^\]]+)\]\([^)]*\)', r'\1', title)
        title = re.sub(r'<[^>]*>', '', unescape(title)).replace('`', '')
        slug = re.sub(r'[^\w\- ]', '', title.lower()).replace(' ', '-')
        ordinal = seen[slug]
        seen[slug] += 1
        result.add(slug if ordinal == 0 else f'{slug}-{ordinal}')
    return result


def check_links(root, markdown_paths, changed_paths):
    """Check changed sources and unchanged inbound links to changed targets.

    External URLs and non-Markdown fragments aren't dereferenced. No claim of
    complete CommonMark rendering is made; conventional inline/reference/image
    and HTML destinations are supported. Existing unrelated failures are left
    alone, while deleted/renamed targets and changed headings are checked.
    """
    root = Path(root).resolve()
    changed_paths = set(changed_paths)
    errors, anchor_cache = [], {}
    for source in sorted(markdown_paths):
        path = root / source
        if path.is_symlink() or not path.resolve().is_relative_to(root):
            errors.append(f'{source}: unsafe Markdown source')
            continue
        try:
            text = without_fences(path.read_text(encoding='utf-8'))
        except (OSError, UnicodeError):
            errors.append(f'{source}: unreadable Markdown source')
            continue
        text = re.sub(r'(`+).*?\1', '', text)
        html = HtmlLinks()
        html.feed(text)
        for number, target in enumerate([*inline_targets(text), *html.targets], 1):
            prefix = f'{source}: local link #{number}'
            try:
                url = urlsplit(unescape(target))
            except ValueError:
                if source in changed_paths:
                    errors.append(prefix + ' has an invalid destination')
                continue
            if url.scheme and url.scheme != 'file' or url.netloc:
                continue
            destination = unquote(url.path)
            resolved = (path.parent / destination).resolve() if destination else path.resolve()
            safe = resolved.is_relative_to(root) and '.git' not in resolved.relative_to(root).parts
            if not safe or url.scheme == 'file':
                if source in changed_paths:
                    errors.append(prefix + ' escapes the repository')
                continue
            relative = resolved.relative_to(root).as_posix()
            if source not in changed_paths and relative not in changed_paths:
                continue
            if not resolved.exists():
                errors.append(prefix + ' has a missing target')
            elif url.fragment and resolved.suffix.lower() == '.md':
                try:
                    if relative not in anchor_cache:
                        anchor_cache[relative] = anchors(resolved.read_text(encoding='utf-8'))
                    if unquote(url.fragment) not in anchor_cache[relative]:
                        errors.append(prefix + ' has a missing Markdown anchor')
                except (OSError, UnicodeError):
                    errors.append(prefix + ' has an unreadable target')
    return errors
