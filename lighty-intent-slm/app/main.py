# Copyright (c) 2026 icarocamelo. All Rights Reserved.
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v1.0 which accompanies this distribution,
# and is available at https://www.eclipse.org/legal/epl-v10.html
"""
lighty-intent-slm: the NL-to-intent translation microservice ("SLM sidecar")
that lighty's lighty-intent-nl-client (Java) module calls over HTTP.

Safety model
------------
This service's *only* job is to turn a natural-language operator request
into a draft structured intent (or a list of clarifying questions). It:

  * never talks to the managed network (no NETCONF/gNMI/A1/RESTCONF-to-devices
    calls, no sockets to anything but this HTTP server itself);
  * never produces device-level configuration -- only a draft
    '/intents/intent' entry, shaped per lighty-intent.yang, that a human
    still has to review, and that lighty-intent-core still has to validate,
    dry-run and get explicitly approved before anything is compiled or
    actuated;
  * never calls out to an external LLM API -- v1's extractor is a fully
    deterministic, inspectable, rule-based stand-in (see app/extractor.py).

See README.md for the full contract.
"""

from __future__ import annotations

import json
import logging
from typing import List, Optional

from fastapi import FastAPI
from pydantic import BaseModel, Field

from app.extractor import extract_intent

logger = logging.getLogger("lighty_intent_slm")

app = FastAPI(
    title="lighty-intent-slm",
    description=(
        "NL-to-intent translation sidecar. Drafts structured intents only; "
        "never produces device configuration."
    ),
    version="1.0.0",
)


class TranslateRequest(BaseModel):
    text: str = Field(..., min_length=1, description="Verbatim operator natural-language request.")
    context_hint: Optional[str] = Field(
        default=None, description="Optional freeform hint to help disambiguate the request."
    )


class TranslateResponse(BaseModel):
    status: str = Field(..., description="INTENT_DRAFTED | CLARIFICATION_NEEDED | ERROR")
    draft_intent_json: Optional[str] = Field(
        default=None,
        description="RFC 7951 JSON string of the drafted intent. Present only if status is INTENT_DRAFTED.",
    )
    clarification_questions: List[str] = Field(default_factory=list)
    confidence: float = Field(default=0.0, ge=0.0, le=1.0)


STATUS_INTENT_DRAFTED = "INTENT_DRAFTED"
STATUS_CLARIFICATION_NEEDED = "CLARIFICATION_NEEDED"
STATUS_ERROR = "ERROR"


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/v1/translate", response_model=TranslateResponse)
def translate(request: TranslateRequest) -> TranslateResponse:
    """Translate a natural-language operator request into a draft intent.

    This endpoint never raises out to a 500 for a bad-but-well-formed
    request (e.g. gibberish text): that case is reported as
    CLARIFICATION_NEEDED. An unexpected internal failure is reported as
    ERROR with confidence 0.0 rather than propagating, since the Java
    client treats any non-2xx as a transport-level failure distinct from a
    "the sidecar tried and couldn't" result.
    """
    try:
        draft_intent, questions, confidence = extract_intent(
            request.text, request.context_hint
        )
    except Exception:  # noqa: BLE001 - deliberately broad: never 500 to the caller.
        logger.exception("extract_intent failed for text=%r", request.text)
        return TranslateResponse(
            status=STATUS_ERROR,
            clarification_questions=[],
            confidence=0.0,
        )

    if draft_intent is not None:
        return TranslateResponse(
            status=STATUS_INTENT_DRAFTED,
            draft_intent_json=json.dumps(draft_intent),
            clarification_questions=[],
            confidence=confidence,
        )

    return TranslateResponse(
        status=STATUS_CLARIFICATION_NEEDED,
        draft_intent_json=None,
        clarification_questions=questions,
        confidence=confidence,
    )
