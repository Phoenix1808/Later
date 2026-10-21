"""
Later backend - turns a phone screenshot into one follow-up action.

People screenshot things "to deal with later" and never do. The Android app sends
each new screenshot here; an open-weight vision model reads it and decides what it
is (event, bill, to-do, place, link, note), extracts the details needed to act on
it in one tap, and flags anything private (OTPs, passwords, banking) so the app can
skip it. Nothing is stored on the server.

The model is reached through any OpenAI-compatible endpoint, so it's swappable:
    LLM_BASE_URL=https://api.groq.com/openai/v1   LLM_MODEL=qwen/qwen3.8-27b
    LLM_BASE_URL=http://localhost:11434/v1        LLM_MODEL=gemma3:4b
"""

import asyncio
import base64
import json
import logging
import os
import random
import re
import secrets

import httpx
from dotenv import load_dotenv
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel

load_dotenv()

LLM_BASE_URL = os.getenv("LLM_BASE_URL", "https://api.groq.com/openai/v1").rstrip("/")
LLM_API_KEY = os.getenv("LLM_API_KEY", "")
LLM_MODEL = os.getenv("LLM_MODEL", "qwen/qwen3.8-27b")

# Shared secret the Android app sends as X-App-Key. Keeps strangers who find the
# public URL from spending the free model quota. Unset = open (local development).
APP_KEY = os.getenv("APP_KEY", "")

MAX_IMAGE_BYTES = 8 * 1024 * 1024
MAX_RETRIES = 3               # rate-limit retries per model call
MAX_RETRY_WAIT_S = 45         # longest we'll quietly wait out a rate limit

KINDS = ("event", "bill", "todo", "place", "link", "note", "skip", "private")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
TIME_RE = re.compile(r"^([01]\d|2[0-3]):[0-5]\d$")

log = logging.getLogger("uvicorn.error")
app = FastAPI(title="Later API", version="1.0.0")


def require_app_key(x_app_key: str = Header(default="")):
    if APP_KEY and not secrets.compare_digest(x_app_key, APP_KEY):
        raise HTTPException(401, "This server only answers the Later app.")


class Analysis(BaseModel):
    kind: str
    title: str
    summary: str
    date: str | None = None       # YYYY-MM-DD
    time: str | None = None       # HH:MM, 24-hour
    location: str | None = None
    amount: str | None = None     # as written, e.g. "₹1,240"
    url: str | None = None


# ---------- LLM ----------

async def ask_llm(messages: list[dict], temperature: float = 0.2) -> dict:
    """Send a chat request to the open-weight model and parse its JSON reply."""
    if not LLM_API_KEY and "localhost" not in LLM_BASE_URL:
        raise HTTPException(500, "LLM_API_KEY is not set on the server")

    payload = {
        "model": LLM_MODEL,
        "temperature": temperature,
        "response_format": {"type": "json_object"},
        "messages": messages,
    }
    headers = {"Authorization": f"Bearer {LLM_API_KEY}"} if LLM_API_KEY else {}

    try:
        async with httpx.AsyncClient(timeout=60) as client:
            resp = await client.post(f"{LLM_BASE_URL}/chat/completions", json=payload, headers=headers)
            # Free tiers allow a few thousand tokens a minute (one screenshot is ~1.8k).
            # If the provider says "wait a few seconds", wait and retry, with jitter so
            # several waiting requests don't all retry at the same instant.
            waited = 0.0
            for _ in range(MAX_RETRIES):
                if resp.status_code != 429:
                    break
                wait = retry_after_seconds(resp)
                if wait is None or waited + wait > MAX_RETRY_WAIT_S:
                    break
                pause = wait + random.uniform(0.5, 3.0)
                await asyncio.sleep(pause)
                waited += pause
                resp = await client.post(f"{LLM_BASE_URL}/chat/completions", json=payload, headers=headers)
    except httpx.HTTPError as e:
        raise HTTPException(502, f"Could not reach the model provider: {e}") from e

    if resp.status_code == 429:
        raise HTTPException(429, "The AI is busy (free tier limit). Later will try again soon.")
    if resp.status_code != 200:
        raise HTTPException(502, f"Model provider error {resp.status_code}: {resp.text[:300]}")

    return parse_json(resp.json()["choices"][0]["message"]["content"])


def retry_after_seconds(resp: httpx.Response) -> float | None:
    header = resp.headers.get("retry-after")
    if header:
        try:
            return float(header)
        except ValueError:
            pass
    # Groq also says it in the message: "...Please try again in 7.66s."
    match = re.search(r"try again in ([\d.]+)s", resp.text)
    return float(match.group(1)) if match else None


def parse_json(text: str) -> dict:
    """Models occasionally wrap JSON in prose or code fences - dig it out."""
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", text, re.DOTALL)
        if match:
            try:
                return json.loads(match.group(0))
            except json.JSONDecodeError:
                pass
    raise HTTPException(502, "Model returned an unreadable response, please retry")


# ---------- cleaning the model's answer ----------

def clean_text(value, limit: int) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    if not text or text.lower() in ("null", "none", "n/a", "unknown"):
        return None
    return text[:limit]


def to_analysis(data: dict) -> Analysis:
    kind = str(data.get("kind", "skip")).strip().lower()
    if kind not in KINDS:
        kind = "note"

    # Private screenshots keep no details at all - not even a title from the model
    if kind == "private":
        return Analysis(kind="private", title="Private screenshot", summary="Skipped: looked sensitive.")

    date = clean_text(data.get("date"), 10)
    time = clean_text(data.get("time"), 5)
    url = clean_text(data.get("url"), 500)
    if url and not url.startswith(("http://", "https://")):
        url = "https://" + url if "." in url and " " not in url else None

    return Analysis(
        kind=kind,
        title=clean_text(data.get("title"), 80) or "Screenshot",
        summary=clean_text(data.get("summary"), 200) or "",
        date=date if date and DATE_RE.match(date) else None,
        time=time if time and TIME_RE.match(time) else None,
        location=clean_text(data.get("location"), 160),
        amount=clean_text(data.get("amount"), 40),
        url=url,
    )


# ---------- endpoints ----------

@app.get("/")
async def health():
    return {"status": "ok", "model": LLM_MODEL, "provider": LLM_BASE_URL}


SYSTEM_PROMPT = (
    "You are the assistant inside 'Later', an app for people who screenshot things "
    "'to deal with later' and never do. Look at ONE phone screenshot and decide the single most "
    "useful follow-up action. Reply only with JSON: "
    "{\"kind\": one of event|bill|todo|place|link|note|skip|private, "
    "\"title\": short title, max 8 words, "
    "\"summary\": one sentence, max 20 words, saying what to do, "
    "\"date\": \"YYYY-MM-DD\" or null, \"time\": \"HH:MM\" 24-hour or null, "
    "\"location\": address or venue or null, \"amount\": money amount as written or null, "
    "\"url\": web address or null}.\n"
    "Kinds:\n"
    "- event: something happening at a date/time (meetup, class, match, party, deadline-free booking). "
    "Put its start in date/time and the venue in location.\n"
    "- bill: something to pay (invoice, due amount, recharge, rent). Put the due date in date and the amount in amount.\n"
    "- todo: a task to do, often by a deadline (submit a form, reply to someone, buy something). Deadline goes in date.\n"
    "- place: an address, shop, restaurant or location to visit or remember. Put it in location.\n"
    "- link: an article, product, video, repo or page to open later. Put the web address in url if visible.\n"
    "- note: useful information to keep (a quote, a recipe, instructions, a contact) that fits nothing above.\n"
    "- skip: memes, jokes, random app screens, games, or anything with no follow-up.\n"
    "- private: OTP or verification codes, passwords, bank or card details, account balances, "
    "ID documents. For private, leave every other field empty - never repeat the sensitive content.\n"
    "Resolve relative dates ('tomorrow', 'this Friday') using the date given by the user. "
    "If the year is missing, pick the next upcoming occurrence. Never invent dates, times, places or links "
    "that are not in the screenshot."
)


@app.post("/analyze", response_model=Analysis, dependencies=[Depends(require_app_key)])
async def analyze(
    image: UploadFile = File(...),
    today: str = Form(...),          # the phone's local date, e.g. 2026-10-21
    weekday: str = Form(""),         # e.g. Wednesday - helps with "this Friday"
):
    data = image.file.read(MAX_IMAGE_BYTES + 1)
    if len(data) > MAX_IMAGE_BYTES:
        raise HTTPException(413, "That screenshot is too large")
    mime = image.content_type if (image.content_type or "").startswith("image/") else "image/jpeg"

    messages = [
        {"role": "system", "content": SYSTEM_PROMPT},
        {
            "role": "user",
            "content": [
                {"type": "text", "text": f"Today is {weekday} {today}. What should I do about this screenshot?"},
                {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{base64.b64encode(data).decode()}"}},
            ],
        },
    ]
    return to_analysis(await ask_llm(messages))
