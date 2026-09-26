# Copyright (c) 2026 icarocamelo. All Rights Reserved.
#
# This program and the accompanying materials are made available under the
# terms of the Eclipse Public License v1.0 which accompanies this distribution,
# and is available at https://www.eclipse.org/legal/epl-v10.html
"""
NL -> draft intent extraction.

This module is the actual "translation" logic behind POST /v1/translate. It
covers exactly the two MVP use cases described in
docs/intent-driven-management.md:

  1. Transport QoS/isolation, e.g.:
     "Guarantee 200 Mbps and under 15 ms latency between siteA and siteB,
      isolated from other traffic."

  2. RAN coverage/capacity, e.g.:
     "Keep PRB utilization under 70% in cell cell-042."

Two extraction strategies are tried, in order, by ``extract_intent``:

  1. ``translate_with_model`` -- the seam for a real small language model.
     It always returns None today; see its docstring.
  2. The rule-based, regex/keyword extractor implemented in this file
     (``_extract_transport_targets`` / ``_extract_ran_targets`` and
     friends). This is what actually produces drafts in v1.

Everything here is deterministic and fully inspectable: no network calls,
no external LLM API, no calls into the managed network. It only ever
*drafts* an intent; nothing here writes anything or talks to a device.
"""

from __future__ import annotations

import re
import uuid
from typing import Optional

from app.inventory import fetch_inventory_context

# --- YANG identity / enum constants (must match the .yang files exactly) ---

DOMAIN_TRANSPORT = "lighty-intent-transport:transport"
DOMAIN_RAN = "lighty-intent-ran-cco:ran"

OBJ_MIN_BANDWIDTH = "lighty-intent-transport:min-bandwidth"
OBJ_MAX_LATENCY = "lighty-intent-transport:max-latency"
OBJ_MAX_PACKET_LOSS = "lighty-intent-transport:max-packet-loss"
OBJ_TRAFFIC_ISOLATION = "lighty-intent-transport:traffic-isolation"

OBJ_PRB_UTILIZATION_MAX = "lighty-intent-ran-cco:prb-utilization-max"
OBJ_CELL_COVERAGE_TARGET = "lighty-intent-ran-cco:cell-coverage-target"
OBJ_CELL_EDGE_THROUGHPUT_MIN = "lighty-intent-ran-cco:cell-edge-throughput-min"

SOURCE_NL_SLM = "NL_SLM"
VERB_ENSURE = "ENSURE"

IS_GREATER_THAN = "IS_GREATER_THAN"
IS_LESS_THAN = "IS_LESS_THAN"


def translate_with_model(text: str, context: dict) -> Optional[dict]:
    """Seam for the real small-language-model translator.

    This is intentionally a stub that always returns ``None`` today, so
    ``extract_intent`` always falls through to the deterministic rule-based
    extractor below. It exists so that swapping in a real model later is a
    one-function change with no change to the HTTP layer.

    What a real implementation would do instead of returning ``None``:

      1. Load a small (1-4B parameter) open-weight instruction model once at
         process startup -- e.g. Qwen2.5-3B-Instruct or Llama-3.2-3B-Instruct
         -- served locally via llama.cpp (GGUF, CPU/GPU) or vLLM (GPU), never
         a hosted third-party API, so this sidecar stays fully on-prem/
         air-gapped like the rest of lighty's control plane.
      2. Build a JSON-Schema-constrained decoding grammar from
         ``app/schema.py``'s ``load_intent_schema()`` (e.g. llama.cpp GBNF
         generated from JSON Schema, or vLLM's guided-decoding /
         xgrammar backend), so the model is *structurally* incapable of
         emitting anything outside the draft-intent shape -- it cannot,
         by construction, emit device-level configuration fields, because
         those fields do not exist in the grammar.
      3. Render a few-shot prompt: a short system preamble describing the
         two supported intent domains and their expectation-object-types,
         2-4 worked (nl-text -> draft-intent-json) examples covering both
         MVP use cases, then the operator's ``text`` and, if present,
         ``context.get("context_hint")``.
      4. Ground the prompt with retrieval: call
         ``inventory.fetch_inventory_context`` (there: a real RESTCONF GET
         against lighty's own northbound, not the static stub used here) and
         inject the handful of matching site/link/cell ids as retrieved
         context, so the model prefers an id that actually exists over
         hallucinating one.
      5. Run constrained decoding, parse the resulting JSON, and return it
         as a dict shaped exactly like this module's rule-based output
         (i.e. still validating against ``schema/intent.schema.json``). On
         low model confidence or a schema-invalid result, return ``None``
         (or a CLARIFICATION_NEEDED-shaped dict) so the caller can fall back
         or ask the operator to rephrase, exactly as the stub does now.

    Args:
        text: The verbatim operator natural-language request.
        context: Extra context for the model, e.g. {"context_hint": ...,
            "inventory": {...}}.

    Returns:
        None, always, in this v1 stub.
    """
    return None


def _new_intent_id() -> str:
    return f"intent-{uuid.uuid4()}"


def _new_expectation_id() -> str:
    return f"exp-{uuid.uuid4()}"


def _make_target(
    object_type: str,
    instance: str,
    condition: str,
    value: str,
    unit: str,
) -> dict:
    return {
        "expectation-object-type": object_type,
        "expectation-object-instance": instance,
        "target-condition": condition,
        "target-value-range": [value],
        "unit": unit,
    }


def _search_number(pattern: str, text: str) -> Optional[str]:
    match = re.search(pattern, text, re.IGNORECASE)
    if not match:
        return None
    return match.group(1)


def _extract_link_instance(text: str, inventory: dict) -> tuple[str, bool]:
    """Return (instance-id, was_explicit)."""
    match = re.search(
        r"between\s+([A-Za-z0-9_\-]+)\s+and\s+([A-Za-z0-9_\-]+)", text, re.IGNORECASE
    )
    if match:
        site_a, site_b = match.group(1), match.group(2)
        return f"link-{site_a}-{site_b}", True

    links = inventory.get("links") or []
    if links:
        return links[0]["id"], False
    return "link-unknown", False


def _extract_cell_instance(text: str, inventory: dict) -> tuple[str, bool]:
    """Return (instance-id, was_explicit)."""
    match = re.search(r"(?:cell|site)\s+([A-Za-z0-9_\-]+)", text, re.IGNORECASE)
    if match:
        raw = match.group(1)
        instance = raw if raw.lower().startswith(("cell-", "site")) else f"cell-{raw}"
        return instance, True

    cells = inventory.get("cells") or []
    if cells:
        return cells[0]["id"], False
    return "cell-unknown", False


def _extract_transport_targets(text: str, inventory: dict) -> tuple[list[dict], bool]:
    """Return (expectation-target list, any_instance_was_explicit)."""
    targets: list[dict] = []
    instance, explicit = _extract_link_instance(text, inventory)
    any_explicit = False

    bandwidth = _search_number(r"(\d+(?:\.\d+)?)\s*mbps", text)
    if bandwidth is not None:
        targets.append(
            _make_target(OBJ_MIN_BANDWIDTH, instance, IS_GREATER_THAN, bandwidth, "Mbps")
        )
        any_explicit = any_explicit or explicit

    latency = _search_number(
        r"(?:under|below|less than|max(?:imum)?)\s*(\d+(?:\.\d+)?)\s*ms", text
    )
    if latency is not None:
        targets.append(
            _make_target(OBJ_MAX_LATENCY, instance, IS_LESS_THAN, latency, "ms")
        )
        any_explicit = any_explicit or explicit

    packet_loss = _search_number(r"(\d+(?:\.\d+)?)\s*%\s*(?:packet\s*)?loss", text)
    if packet_loss is not None:
        targets.append(
            _make_target(OBJ_MAX_PACKET_LOSS, instance, IS_LESS_THAN, packet_loss, "%")
        )
        any_explicit = any_explicit or explicit

    if re.search(r"isolat", text, re.IGNORECASE):
        targets.append(
            _make_target(OBJ_TRAFFIC_ISOLATION, instance, "IS_EQUAL_TO", "true", "boolean")
        )
        any_explicit = any_explicit or explicit

    return targets, any_explicit


def _extract_ran_targets(text: str, inventory: dict) -> tuple[list[dict], bool]:
    """Return (expectation-target list, any_instance_was_explicit)."""
    targets: list[dict] = []
    instance, explicit = _extract_cell_instance(text, inventory)
    any_explicit = False

    prb = _search_number(
        r"prb\s+utilization\s+(?:under|below|less than|max(?:imum)?)\s*(\d+(?:\.\d+)?)\s*%",
        text,
    )
    if prb is not None:
        targets.append(
            _make_target(OBJ_PRB_UTILIZATION_MAX, instance, IS_LESS_THAN, prb, "%")
        )
        any_explicit = any_explicit or explicit

    coverage = _search_number(r"coverage[^\d-]*(-?\d+(?:\.\d+)?)\s*dbm", text)
    if coverage is not None:
        targets.append(
            _make_target(
                OBJ_CELL_COVERAGE_TARGET, instance, IS_GREATER_THAN, coverage, "dBm"
            )
        )
        any_explicit = any_explicit or explicit

    cell_edge = _search_number(
        r"cell[\s\-]edge\s+throughput[^\d]*(\d+(?:\.\d+)?)\s*mbps", text
    )
    if cell_edge is not None:
        targets.append(
            _make_target(
                OBJ_CELL_EDGE_THROUGHPUT_MIN, instance, IS_GREATER_THAN, cell_edge, "Mbps"
            )
        )
        any_explicit = any_explicit or explicit

    return targets, any_explicit


def _build_draft(domain: str, text: str, targets: list[dict]) -> dict:
    label = text.strip().splitlines()[0][:60] if text.strip() else "NL-drafted intent"
    return {
        "lighty-intent:intent": [
            {
                "intent-id": _new_intent_id(),
                "intent-name": label,
                "domain": domain,
                "source": SOURCE_NL_SLM,
                "original-nl-text": text,
                "expectation": [
                    {
                        "expectation-id": _new_expectation_id(),
                        "expectation-verb": VERB_ENSURE,
                        "expectation-target": targets,
                    }
                ],
            }
        ]
    }


def extract_intent(
    text: str, context_hint: Optional[str] = None
) -> tuple[Optional[dict], list[str], float]:
    """Translate operator NL text into a draft intent, deterministically.

    Args:
        text: Verbatim operator request.
        context_hint: Optional freeform disambiguation hint from the caller.

    Returns:
        A 3-tuple ``(draft_intent, clarification_questions, confidence)``.
        Exactly one of ``draft_intent`` / a non-empty ``clarification_questions``
        is meaningful: when a draft was produced, ``draft_intent`` is a dict
        ready to ``json.dumps`` into ``draft_intent_json`` (it validates
        against ``schema/intent.schema.json``) and ``clarification_questions``
        is empty; otherwise ``draft_intent`` is ``None`` and
        ``clarification_questions`` has at least one entry.
    """
    # Seam for the real model; always a no-op fallthrough in v1 (see its docstring).
    model_result = translate_with_model(text, {"context_hint": context_hint})
    if model_result is not None:
        return model_result, [], 0.99

    inventory = fetch_inventory_context(context_hint)

    transport_targets, transport_explicit = _extract_transport_targets(text, inventory)
    ran_targets, ran_explicit = _extract_ran_targets(text, inventory)

    if not transport_targets and not ran_targets:
        return (
            None,
            [
                "Which network domain is this about: transport (bandwidth/latency/"
                "isolation) or RAN (PRB utilization/coverage/cell-edge throughput)?",
                "What is the target value and unit (e.g. '200 Mbps', 'under 15 ms', "
                "'under 70%')?",
                "Which site, link or cell should this apply to?",
            ],
            0.0,
        )

    # MVP use cases don't mix domains in one request; prefer whichever matched
    # more targets, breaking ties toward transport.
    if len(ran_targets) > len(transport_targets):
        domain, targets, explicit = DOMAIN_RAN, ran_targets, ran_explicit
    else:
        domain, targets, explicit = DOMAIN_TRANSPORT, transport_targets, transport_explicit

    draft = _build_draft(domain, text, targets)

    confidence = 0.6 + 0.1 * len(targets)
    if explicit:
        confidence += 0.15
    confidence = min(confidence, 0.95)

    return draft, [], confidence
