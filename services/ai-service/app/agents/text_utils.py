import re
from typing import Iterable, List

_STOPWORDS = frozenset("""
a an and are as at be by can could did do does for from has have how i if in into is it its
me my of on or our should so than that the their them then there these this those to was
we were what when where which who why will with would you your about tell explain describe
list give show please much many must any some all
""".split())

_TOKEN = re.compile(r"[A-Za-z0-9][A-Za-z0-9_\-]*")


def keywords(text: str) -> List[str]:
    """Returns de-duplicated, lower-cased content words (stopwords removed) in original order."""
    seen, out = set(), []
    for tok in _TOKEN.findall(text or ""):
        low = tok.lower()
        if len(low) < 3 or low in _STOPWORDS or low in seen:
            continue
        seen.add(low)
        out.append(low)
    return out


def dedupe_queries(queries: Iterable[str], limit: int = 4) -> List[str]:
    """Normalizes whitespace, drops blanks/duplicates (case-insensitive) and caps the list length."""
    seen, out = set(), []
    for q in queries:
        clean = " ".join(str(q or "").split())
        key = clean.lower()
        if not clean or key in seen:
            continue
        seen.add(key)
        out.append(clean)
        if len(out) >= limit:
            break
    return out
