# Copyright (c) 2026 icarocamelo. All Rights Reserved.
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v1.0 which accompanies this distribution,
# and is available at https://www.eclipse.org/legal/epl-v10.html
"""Tests for POST /v1/translate."""

from __future__ import annotations

import json

import jsonschema
import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.schema import load_intent_schema

client = TestClient(app)


def _validate_draft(draft_intent_json: str) -> dict:
    parsed = json.loads(draft_intent_json)
    jsonschema.validate(instance=parsed, schema=load_intent_schema())
    return parsed


def test_transport_qos_and_isolation_drafts_valid_intent():
    text = (
        "Guarantee 200 Mbps and under 15 ms latency between siteA and siteB, "
        "isolated from other traffic."
    )
    response = client.post("/v1/translate", json={"text": text, "context_hint": None})

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "INTENT_DRAFTED"
    assert body["draft_intent_json"] is not None
    assert 0.0 < body["confidence"] <= 1.0

    parsed = _validate_draft(body["draft_intent_json"])
    intent = parsed["lighty-intent:intent"][0]

    assert intent["domain"] == "lighty-intent-transport:transport"
    assert intent["source"] == "NL_SLM"
    assert intent["original-nl-text"] == text

    targets = intent["expectation"][0]["expectation-target"]
    by_type = {t["expectation-object-type"]: t for t in targets}

    assert "lighty-intent-transport:min-bandwidth" in by_type
    bw = by_type["lighty-intent-transport:min-bandwidth"]
    assert bw["target-value-range"] == ["200"]
    assert bw["unit"] == "Mbps"
    assert bw["target-condition"] == "IS_GREATER_THAN"

    assert "lighty-intent-transport:max-latency" in by_type
    lat = by_type["lighty-intent-transport:max-latency"]
    assert lat["target-value-range"] == ["15"]
    assert lat["unit"] == "ms"
    assert lat["target-condition"] == "IS_LESS_THAN"

    assert "lighty-intent-transport:traffic-isolation" in by_type

    # Explicit site names in the text should drive the instance id.
    assert bw["expectation-object-instance"] == "link-siteA-siteB"


def test_ran_prb_utilization_drafts_valid_intent():
    text = "Keep PRB utilization under 70% in cell cell-042."
    response = client.post("/v1/translate", json={"text": text})

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "INTENT_DRAFTED"
    assert body["draft_intent_json"] is not None

    parsed = _validate_draft(body["draft_intent_json"])
    intent = parsed["lighty-intent:intent"][0]

    assert intent["domain"] == "lighty-intent-ran-cco:ran"

    targets = intent["expectation"][0]["expectation-target"]
    by_type = {t["expectation-object-type"]: t for t in targets}

    assert "lighty-intent-ran-cco:prb-utilization-max" in by_type
    prb = by_type["lighty-intent-ran-cco:prb-utilization-max"]
    assert prb["target-value-range"] == ["70"]
    assert prb["unit"] == "%"
    assert prb["target-condition"] == "IS_LESS_THAN"
    assert prb["expectation-object-instance"] == "cell-042"


def test_nonsense_input_needs_clarification():
    response = client.post(
        "/v1/translate", json={"text": "the sky is a lovely shade of blue today"}
    )

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "CLARIFICATION_NEEDED"
    assert body["draft_intent_json"] is None
    assert len(body["clarification_questions"]) >= 1
    assert body["confidence"] == 0.0


@pytest.mark.parametrize(
    "payload",
    [
        {},  # missing required "text"
        {"text": 12345},  # wrong type
        {"text": ""},  # empty string violates min_length
    ],
)
def test_malformed_request_returns_4xx_not_500(payload):
    response = client.post("/v1/translate", json=payload)
    assert 400 <= response.status_code < 500


def test_malformed_json_body_returns_4xx_not_500():
    response = client.post(
        "/v1/translate",
        data="not json at all",
        headers={"Content-Type": "application/json"},
    )
    assert 400 <= response.status_code < 500
